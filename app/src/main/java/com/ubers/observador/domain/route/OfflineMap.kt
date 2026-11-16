package com.ubers.observador.domain.route

/**
 * 1.58.0 «Mapa sin internet». Map tiles are kept in the app's own storage (not the cache Android
 * may empty) up to a size the driver picks, and the ones used least recently go first when it is
 * full — so the streets of the usual work zone stay. Without internet a missing tile is drawn from
 * a saved tile of a wider zoom, enlarged. «Guardar esta zona» downloads the visible area at up to
 * two closer zooms, never more than [MAX_PREFETCH] tiles: OpenStreetMap forbids bulk downloads
 * (over 250 tiles at zoom 13 or more for later use). Map tiles are not the driver's data: they are
 * not in backups and can be downloaded again.
 */
object OfflineMap {
    const val MAX_PREFETCH = 250
    const val EXTRA_ZOOMS = 2
    const val MAX_ZOOM = 19
    val BUDGETS_MB = listOf(50, 100, 200, 500)
    const val DEFAULT_BUDGET_MB = 100
    /** How many zoom levels up a missing tile may be taken from. */
    const val MAX_PARENT_LEVELS = 4

    data class Tile(val z: Int, val x: Int, val y: Int) {
        val key: String get() = "$z/$x/$y"
    }

    data class Stored(val key: String, val bytes: Long, val lastUsedAt: Long)

    /** Least recently used first, until the store is at 90 % of [budgetBytes]; nothing when under it. */
    fun toTrim(stored: List<Stored>, budgetBytes: Long): List<String> {
        var total = stored.sumOf { it.bytes }
        if (total <= budgetBytes) return emptyList()
        val out = ArrayList<String>()
        for (s in stored.sortedBy { it.lastUsedAt }) {
            if (total <= budgetBytes * 9 / 10) break
            total -= s.bytes
            out += s.key
        }
        return out
    }

    sealed interface Plan {
        data class Ok(val tiles: List<Tile>, val zooms: IntRange) : Plan
        data class TooBig(val needed: Int) : Plan
    }

    /**
     * The tiles of the area covered by [visible] (all at one zoom) at that zoom and up to
     * [EXTRA_ZOOMS] closer ones, without those already saved. The closest zooms are dropped first
     * when the total would pass [MAX_PREFETCH].
     */
    fun prefetchPlan(visible: List<Tile>, saved: Set<String>): Plan {
        if (visible.isEmpty()) return Plan.Ok(emptyList(), 0..0)
        val z = visible.first().z
        val minX = visible.minOf { it.x }; val maxX = visible.maxOf { it.x }
        val minY = visible.minOf { it.y }; val maxY = visible.maxOf { it.y }
        for (extra in minOf(EXTRA_ZOOMS, MAX_ZOOM - z) downTo 0) {
            val all = (0..extra).flatMap { k ->
                val f = 1 shl k
                (minY * f until (maxY + 1) * f).flatMap { y -> (minX * f until (maxX + 1) * f).map { x -> Tile(z + k, x, y) } }
            }
            if (all.size <= MAX_PREFETCH) return Plan.Ok(all.filter { it.key !in saved }, z..z + extra)
            if (extra == 0) return Plan.TooBig(all.size)
        }
        return Plan.TooBig(Int.MAX_VALUE)
    }

    /** Where a missing tile is inside a saved tile [levels] zooms wider: the parent and the part to enlarge (fractions 0..1). */
    data class ParentPart(val parent: Tile, val left: Double, val top: Double, val size: Double)

    fun parentPart(t: Tile, levels: Int): ParentPart? {
        if (levels < 1 || levels > t.z) return null
        val f = 1 shl levels
        val px = t.x / f; val py = t.y / f
        val size = 1.0 / f
        return ParentPart(Tile(t.z - levels, px, py), (t.x - px * f) * size, (t.y - py * f) * size, size)
    }

    fun sizeText(bytes: Long): String = when {
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    fun status(count: Int, bytes: Long, budgetMb: Int): String =
        "$count partes del mapa guardadas · ${sizeText(bytes)} de $budgetMb MB"
}
