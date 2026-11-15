package com.ubers.observador.domain.route

/**
 * 1.45.0: when a map tile on disk can be used, must be asked again, or is missing. OpenStreetMap
 * asks apps to honour its caching: a tile younger than [FRESH_MS] is used without asking; an older
 * one with a validator (ETag or Last-Modified) is revalidated, which costs the server almost
 * nothing when it answers 304; one without validator is downloaded again. With no internet an old
 * tile is always better than none. Nothing is ever downloaded ahead of being looked at.
 */
object TileCache {

    const val FRESH_MS = 7L * 24 * 3_600_000L
    /** Beyond this a tile with no validator is fetched again. */
    const val MAX_AGE_MS = 30L * 24 * 3_600_000L
    const val DISK_LIMIT_BYTES = 40L * 1024 * 1024
    /** The disk limit is checked after this many new tiles (a long day keeps adding tiles). */
    const val TRIM_EVERY_WRITES = 50

    enum class Decision { USE, REVALIDATE, DOWNLOAD }

    fun decide(ageMs: Long?, hasValidator: Boolean): Decision = when {
        ageMs == null -> Decision.DOWNLOAD
        ageMs < FRESH_MS -> Decision.USE
        hasValidator -> Decision.REVALIDATE
        ageMs < MAX_AGE_MS -> Decision.USE
        else -> Decision.DOWNLOAD
    }

    /** One cached file: its path (as a key), size and age. */
    data class Entry(val key: String, val bytes: Long, val modifiedAt: Long)

    /** Oldest first until the cache is down to three quarters of [limit]; nothing when under it. */
    fun toTrim(entries: List<Entry>, limit: Long = DISK_LIMIT_BYTES): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= limit) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedBy { it.modifiedAt }) {
            if (total <= limit * 3 / 4) break
            total -= e.bytes
            out += e.key
        }
        return out
    }

    /** The validator line kept next to a tile («etag\nlast-modified»); blanks when the server sent none. */
    fun encodeValidator(etag: String?, lastModified: String?): String? {
        val e = etag?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && it.length <= 200 }
        val l = lastModified?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && it.length <= 100 }
        if (e == null && l == null) return null
        return (e ?: "") + "\n" + (l ?: "")
    }

    fun decodeValidator(text: String?): Pair<String?, String?>? {
        if (text == null) return null
        val parts = text.split('\n')
        if (parts.size != 2) return null
        val e = parts[0].ifBlank { null }
        val l = parts[1].ifBlank { null }
        return if (e == null && l == null) null else e to l
    }
}
