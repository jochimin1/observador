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
import com.ubers.observador.domain.route.OfflineMap

/**
 * The street tiles of the route map (Fase 8K, decision 7): OpenStreetMap's standard tiles, under
 * its usage policy — an identifying User-Agent, at most two downloads at a time, the server's
 * caching honoured and the «© OpenStreetMap» credit on the map. 1.58.0: saved in the app's own
 * storage up to the driver's size (least recently used first), usable without internet; only the
 * tiles looked at, plus «Guardar esta zona» within OpenStreetMap's 250-tile limit.
 */
object TileStore {

    private const val TAG = "TileStore"
    private const val URL_FORMAT = "https://tile.openstreetmap.org/%d/%d/%d.png"
    private const val MAX_AGE_MS = 30L * 24 * 3_600_000L
    private const val RETRY_AFTER_MS = 60_000L
    /** 1.58.0: checked every this many saved tiles (walking the folder on every download is wasted work). */
    private const val TRIM_EVERY = 25
    private const val PREFS = "mapas"
    @Volatile private var writes = 0

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
        val dir = dir(context)
        val file = File(dir, "$z/$x/$y.png")
        val metadataFile = File(file.path + ".meta")
        val metadata = java.util.Properties().apply {
            runCatching { metadataFile.inputStream().use { load(it) } }
        }
        val now = System.currentTimeMillis()
        val expires = metadata.getProperty("expires")?.toLongOrNull() ?: (file.lastModified() + MAX_AGE_MS)
        if (file.exists() && now < expires) {
            runCatching { decode(file.readBytes()) }.getOrNull()?.let { touch(metadataFile, now); memory.put(key, it); expiry.put(key, expires); return@withContext it }
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
            if (++writes % TRIM_EVERY == 0) trim(context, dir)
            }
            }
            decode(bytes)?.also { if (response.store) { memory.put(key, it); expiry.put(key, response.metadata.getProperty("expires").toLong()) } }
        } else {
            if (failedAt.size > 256) failedAt.entries.removeIf { now - it.value >= RETRY_AFTER_MS }
            failedAt[key] = now
            // Without internet an old tile is better than none
            if (file.exists()) runCatching { decode(file.readBytes()) }.getOrNull()?.also { touch(metadataFile, now); memory.put(key, it) } else null
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

    // ---- 1.58.0 «Mapa sin internet» ----

    /** Tiles live in the app's own storage, which Android does not empty on its own (the cache was moved once). */
    fun dir(context: Context): File {
        val app = context.applicationContext
        val target = File(app.filesDir, "mapas")
        if (!target.exists()) {
            val old = File(app.cacheDir, "tiles")
            if (!(old.exists() && old.renameTo(target))) target.mkdirs()
        }
        return target
    }

    fun budgetMb(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("budget_mb", OfflineMap.DEFAULT_BUDGET_MB)

    fun setBudgetMb(context: Context, mb: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("budget_mb", mb).apply()
    }

    /** When a tile is used, its .meta file's date says so (at most once an hour): the least used go first. */
    private fun touch(meta: File, now: Long) {
        try { if (meta.exists() && now - meta.lastModified() > 3_600_000L) meta.setLastModified(now) } catch (_: Exception) { }
    }

    private fun storedTiles(dir: File): List<Pair<File, OfflineMap.Stored>> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".png") }.map { f ->
            val meta = File(f.path + ".meta")
            f to OfflineMap.Stored(f.path, f.length() + (if (meta.exists()) meta.length() else 0), if (meta.exists()) meta.lastModified() else f.lastModified())
        }.toList()

    /** Keeps the saved map under the driver's size, least recently used first. */
    private fun trim(context: Context, dir: File) {
        try {
            val stored = storedTiles(dir)
            val remove = OfflineMap.toTrim(stored.map { it.second }, budgetMb(context) * 1024L * 1024L).toSet()
            stored.filter { it.second.key in remove }.forEach { (f, _) -> f.delete(); File(f.path + ".meta").delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trim the saved map", e)
        }
    }

    /** A saved tile of any age, never from the internet. */
    suspend fun peekStored(context: Context, z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(dir(context), "$z/$x/$y.png")
        if (file.exists()) runCatching { decode(file.readBytes()) }.getOrNull() else null
    }

    /** A tile to draw: the whole image, or the part of a wider one that covers the missing tile. */
    data class Drawn(val image: ImageBitmap, val srcLeft: Int = 0, val srcTop: Int = 0, val srcSize: Int = image.width, val wider: Boolean = false)

    /** The tile, or (without it) the part of a saved wider tile, enlarged. Null when neither exists. */
    suspend fun loadOrWider(context: Context, z: Int, x: Int, y: Int): Drawn? {
        load(context, z, x, y)?.let { return Drawn(it) }
        for (levels in 1..OfflineMap.MAX_PARENT_LEVELS) {
            val part = OfflineMap.parentPart(OfflineMap.Tile(z, x, y), levels) ?: break
            val img = peekStored(context, part.parent.z, part.parent.x, part.parent.y) ?: continue
            val w = img.width
            return Drawn(img, (part.left * w).toInt(), (part.top * w).toInt(), maxOf(1, (part.size * w).toInt()), wider = true)
        }
        return null
    }

    fun savedKeys(context: Context): Set<String> {
        val dir = dir(context)
        return storedTiles(dir).map { it.first.relativeTo(dir).path.replace(File.separatorChar, '/').removeSuffix(".png") }.toSet()
    }

    /** (tiles, bytes) saved now. */
    fun stats(context: Context): Pair<Int, Long> = storedTiles(dir(context)).let { l -> l.size to l.sumOf { it.second.bytes } }

    /** «Guardar esta zona»: downloads the planned tiles one by one (two at a time at most, as always). */
    suspend fun prefetch(context: Context, tiles: List<OfflineMap.Tile>, progress: (Int) -> Unit): Int {
        var ok = 0
        tiles.forEachIndexed { i, t ->
            if (load(context, t.z, t.x, t.y) != null) ok++
            progress(i + 1)
        }
        return ok
    }

    /** «Vaciar mapa guardado»: only map tiles (they can be downloaded again), never the driver's data. */
    fun clear(context: Context) = com.ubers.observador.domain.backup.MaintenanceGate.shared.tryAccess {
        memory.evictAll(); expiry.evictAll()
        dir(context).deleteRecursively()
    }
}
