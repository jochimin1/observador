package com.ubers.observador

import com.ubers.observador.domain.route.OfflineMap
import com.ubers.observador.domain.route.OfflineMap.Plan
import com.ubers.observador.domain.route.OfflineMap.Stored
import com.ubers.observador.domain.route.OfflineMap.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.58.0: saved map tiles, the zone download within OpenStreetMap's limit, and wider tiles offline. */
class OfflineMapTest {

    @Test fun theLeastRecentlyUsedGoFirstAndOnlyWhenFull() {
        val s = listOf(Stored("a", 40, 1), Stored("b", 40, 5), Stored("c", 40, 3))
        assertTrue(OfflineMap.toTrim(s, 120).isEmpty())
        assertEquals(listOf("a"), OfflineMap.toTrim(s, 100)) // 120 → 80 ≤ 90
        assertEquals(listOf("a", "c"), OfflineMap.toTrim(s, 60)) // down to 54
    }

    @Test fun aZoneDownloadStaysUnderTheLimitDroppingCloserZoomsFirst() {
        // 4 × 3 tiles visible at zoom 15: 12 + 48 + 192 = 252 > 250 → only two levels (60)
        val visible = (0 until 4).flatMap { x -> (0 until 3).map { y -> Tile(15, 1000 + x, 2000 + y) } }
        val p = OfflineMap.prefetchPlan(visible, emptySet()) as Plan.Ok
        assertEquals(15..16, p.zooms)
        assertEquals(60, p.tiles.size)
        assertTrue(p.tiles.all { it.z in 15..16 })
        // A small view gets three levels; what is saved is skipped
        val small = listOf(Tile(16, 10, 10), Tile(16, 11, 10))
        val q = OfflineMap.prefetchPlan(small, setOf("16/10/10")) as Plan.Ok
        assertEquals(16..18, q.zooms)
        assertEquals(2 + 8 + 32 - 1, q.tiles.size)
        // At the closest zoom there is nothing closer
        assertEquals(19..19, (OfflineMap.prefetchPlan(listOf(Tile(19, 1, 1)), emptySet()) as Plan.Ok).zooms)
        // Too wide even alone: refused, never cut down silently
        val wide = (0 until 20).flatMap { x -> (0 until 15).map { y -> Tile(14, x, y) } }
        assertEquals(Plan.TooBig(300), OfflineMap.prefetchPlan(wide, emptySet()))
    }

    @Test fun aMissingTileIsTakenFromAWiderSavedOne() {
        val p = OfflineMap.parentPart(Tile(16, 1003, 2006), 2)!!
        assertEquals(Tile(14, 250, 501), p.parent)
        assertEquals(0.75, p.left, 1e-9); assertEquals(0.5, p.top, 1e-9); assertEquals(0.25, p.size, 1e-9)
        assertNull(OfflineMap.parentPart(Tile(1, 0, 0), 2))
        assertEquals("12 partes del mapa guardadas · 3 MB de 100 MB", OfflineMap.status(12, 3L * 1024 * 1024, 100))
    }
}
