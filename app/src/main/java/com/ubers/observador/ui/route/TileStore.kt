package com.ubers.observador.ui.route

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ubers.observador.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The street tiles of the route map (Fase 8K, decision 7): OpenStreetMap's standard tiles, under
 * its usage policy — an identifying User-Agent, at most two downloads at a time, a disk cache kept
 * for 30 days and the «© OpenStreetMap» credit on the map. Only the tiles a driver looks at are
 * fetched; nothing is downloaded ahead.
 */
object TileStore {

    private const val TAG = "TileStore"
    private const val URL_FORMAT = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val MAX_AGE_MS = 30L * 24 * 3_600_000L
    private const val RETRY_AFTER_MS = 60_000L
    private const val DISK_LIMIT_BYTES = 40L * 1024 * 1024

    private val memory = LruCache<String, ImageBitmap>(48)
    private val gate = Semaphore(2)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val trimmed = AtomicBoolean(false)

    fun cached(key: String): ImageBitmap? = memory.get(key)

    /** The tile, from memory, disk or OpenStreetMap; null without internet (retried after a minute). */
    suspend fun load(context: Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return@withContext it }
        val dir = File(context.cacheDir, "tiles")
        val file = File(dir, "$z/$x/$y.png")
        val now = System.currentTimeMillis()
        if (file.exists() && now - file.lastModified() < MAX_AGE_MS) {
            decode(file.readBytes())?.let { memory.put(key, it); return@withContext it }
        }
        val failed = failedAt[key]
        val bytes = if (failed != null && now - failed < RETRY_AFTER_MS) null else gate.withPermit { download(z, x, y) }
        if (bytes != null) {
            failedAt.remove(key)
            try {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cache a tile", e)
            }
            if (trimmed.compareAndSet(false, true)) trim(dir)
            decode(bytes)?.also { memory.put(key, it) }
        } else {
            failedAt[key] = now
            // Without internet an old tile is better than none
            if (file.exists()) decode(file.readBytes())?.also { memory.put(key, it) } else null
        }
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? {
        val connection = try {
            URL(URL_FORMAT.format(z, x, y)).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Observador/${BuildConfig.VERSION_NAME} (Android; app personal de un conductor)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { it.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    /** Keeps the disk cache under its limit, oldest tiles first. Once per process. */
    private fun trim(dir: File) {
        try {
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= DISK_LIMIT_BYTES * 3 / 4) return
                total -= f.length()
                f.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trim the tile cache", e)
        }
    }
}
