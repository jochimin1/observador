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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

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
    private val expiry = LruCache<String, Long>(48)
    private val gate = Semaphore(2)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val keyLocks = Array(64) { Mutex() }
    private val diskLock = Mutex()

    fun cached(key: String): ImageBitmap? = memory.get(key)?.takeIf { System.currentTimeMillis() < (expiry.get(key) ?: 0L) }

    /** The tile, from memory, disk or OpenStreetMap; null without internet (retried after a minute). */
    suspend fun load(context: Context, z: Int, x: Int, y: Int): ImageBitmap? =
        keyLocks[("$z/$x/$y".hashCode() and Int.MAX_VALUE) % keyLocks.size].withLock { loadLocked(context, z, x, y) }

    private suspend fun loadLocked(context: Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        cached(key)?.let { return@withContext it }
        val dir = File(context.cacheDir, "tiles")
        val file = File(dir, "$z/$x/$y.png")
        val metadataFile = File(file.path + ".meta")
        val metadata = java.util.Properties().apply {
            runCatching { metadataFile.inputStream().use { load(it) } }
        }
        val now = System.currentTimeMillis()
        val expires = metadata.getProperty("expires")?.toLongOrNull() ?: (file.lastModified() + MAX_AGE_MS)
        if (file.exists() && now < expires) {
            runCatching { decode(file.readBytes()) }.getOrNull()?.let { memory.put(key, it); expiry.put(key, expires); return@withContext it }
        }
        val failed = failedAt[key]
        val response = if (failed != null && now - failed < RETRY_AFTER_MS) null else gate.withPermit { download(z, x, y, file, metadata) }
        val bytes = response?.bytes
        if (bytes != null) {
            failedAt.remove(key)
            diskLock.withLock {
            com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess {
            try {
                file.parentFile?.mkdirs()
                if (response.store) {
                    file.writeBytes(bytes)
                    response.metadata.outputStreamTo(metadataFile)
                } else {
                    file.delete()
                    metadataFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not cache a tile", e)
            }
            trim(dir)
            }
            }
            decode(bytes)?.also { if (response.store) { memory.put(key, it); expiry.put(key, response.metadata.getProperty("expires").toLong()) } }
        } else {
            if (failedAt.size > 256) failedAt.entries.removeIf { now - it.value >= RETRY_AFTER_MS }
            failedAt[key] = now
            // Without internet an old tile is better than none
            if (file.exists()) runCatching { decode(file.readBytes()) }.getOrNull()?.also { memory.put(key, it) } else null
        }
    }

    private data class Response(val bytes: ByteArray, val metadata: java.util.Properties, val store: Boolean)

    private fun java.util.Properties.outputStreamTo(file: File) { file.outputStream().use { store(it, null) } }

    private fun download(z: Int, x: Int, y: Int, file: File, previous: java.util.Properties): Response? {
        val connection = try {
            URL(URL_FORMAT.format(z, x, y)).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "Observador/${BuildConfig.VERSION_NAME} (Android; app personal de un conductor)")
            if (file.exists()) {
                previous.getProperty("etag")?.let { connection.setRequestProperty("If-None-Match", it) }
                previous.getProperty("modified")?.let { connection.setRequestProperty("If-Modified-Since", it) }
            }
            val status = connection.responseCode
            val bytes = when (status) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes() }
                HttpURLConnection.HTTP_NOT_MODIFIED -> file.readBytes()
                else -> return null
            }
            val control = connection.getHeaderField("Cache-Control").orEmpty().lowercase(java.util.Locale.US)
            val age = Regex("(?:^|,)\\s*max-age=(\\d+)").find(control)?.groupValues?.get(1)?.toLongOrNull()
            val now = System.currentTimeMillis()
            val expires = when {
                "no-cache" in control -> now
                age != null -> now + (age - (connection.getHeaderField("Age")?.toLongOrNull() ?: 0)).coerceIn(0, 365L * 86400) * 1000
                connection.expiration > 0 -> connection.expiration
                else -> now + MAX_AGE_MS
            }
            val metadata = java.util.Properties().apply {
                setProperty("expires", expires.toString())
                (connection.getHeaderField("ETag") ?: previous.getProperty("etag").takeIf { status == 304 })?.let { setProperty("etag", it) }
                (connection.getHeaderField("Last-Modified") ?: previous.getProperty("modified").takeIf { status == 304 })?.let { setProperty("modified", it) }
            }
            Response(bytes, metadata, "no-store" !in control)
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

    /** Keeps the disk cache under its limit, oldest tiles first. Checked after every download. */
    private fun trim(dir: File) {
        try {
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            var total = files.sumOf { it.length() }
            if (total <= DISK_LIMIT_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= DISK_LIMIT_BYTES * 3 / 4) return
                val bytes = f.length()
                if (f.delete()) total -= bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trim the tile cache", e)
        }
    }
}
