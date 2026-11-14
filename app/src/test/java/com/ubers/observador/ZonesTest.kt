package com.ubers.observador

import com.ubers.observador.domain.backup.DecodedSettings
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.zones.ZoneCell
import com.ubers.observador.domain.zones.ZoneGrid
import com.ubers.observador.domain.zones.ZonePoints
import com.ubers.observador.domain.zones.ZoneRating
import com.ubers.observador.domain.zones.ZoneSettings
import com.ubers.observador.domain.zones.ZoneSettingsCodec
import com.ubers.observador.domain.zones.ZoneStats
import com.ubers.observador.domain.zones.ZoneTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.47.0: zones from the GPS of the trips taken, with names, private cells and minimum sample. */
class ZonesTest {

    private fun p(lat: Double, lng: Double, phase: RoutePhase = RoutePhase.PASSENGER, at: Long = 0) =
        RoutePoint.of(at, lat, lng, 5f, phase)

    // Two pickup places about 3 km apart, inside two different cells
    private val megacentro = 18.5068 to -69.8566
    private val gazcue = 18.4690 to -69.9100

    private val MIN = 60_000L

    /** A trip picked up at [from], dropped at [to], [minutes] long, paid [rd] pesos (null = not confirmed). */
    private fun trip(
        id: String, startAt: Long, from: Pair<Double, Double>, to: Pair<Double, Double>, minutes: Int = 20, rd: Long? = 200,
        address: String? = null
    ): ZoneTrip {
        val pickup = p(from.first, from.second)
        val drop = p(to.first, to.second)
        return ZoneTrip(
            tripId = id, startAt = startAt, endAt = startAt + minutes * MIN,
            acceptedAt = startAt, arrivedAt = startAt + 4 * MIN, tripStartedAt = startAt + 6 * MIN,
            pickup = pickup, dropoff = drop, routeStart = p(from.first + 0.005, from.second, RoutePhase.PICKUP),
            realMinor = rd?.times(100), pickupAddress = address
        )
    }

    @Test fun theGridIsFixedAndCellsAreAboutOneAndAHalfKilometres() {
        val c = ZoneGrid.cellOf(megacentro.first, megacentro.second)
        assertEquals(c, ZoneGrid.cellOf(megacentro.first + 0.0001, megacentro.second + 0.0001))
        assertEquals(c, ZoneGrid.parse(c.id))
        assertNotEquals(c, ZoneGrid.cellOf(gazcue.first, gazcue.second))
        val km = com.ubers.observador.domain.distance.Geo.distanceM(c.south, c.west, c.north, c.west) / 1000
        assertTrue(km in 1.4..1.6)
        val kmEast = com.ubers.observador.domain.distance.Geo.distanceM(c.south, c.west, c.south, c.east) / 1000
        assertTrue(kmEast in 1.4..1.6)
        // Just across the northern border is the next row
        assertEquals(c.row + 1, ZoneGrid.cellOf(c.north + 1e-6, c.centerLng).row)
        assertEquals(c.row, ZoneGrid.cellOf(c.north - 1e-6, c.centerLng).row)
        assertNull(ZoneGrid.parse("x:1"))
        assertNull(ZoneGrid.parse("1"))
    }

    @Test fun pickupIsTheFirstPointWithThePassenger() {
        val pts = listOf(p(1.0, 1.0, RoutePhase.PICKUP), p(2.0, 2.0, RoutePhase.PICKUP), p(3.0, 3.0), p(4.0, 4.0))
        assertEquals(3.0, ZonePoints.pickup(pts)!!.latitude, 1e-9)
        assertEquals(4.0, ZonePoints.dropoff(pts)!!.latitude, 1e-9)
        // No passenger phase: the last point on the way, and no drop-off
        val noPassenger = pts.take(2)
        assertEquals(2.0, ZonePoints.pickup(noPassenger)!!.latitude, 1e-9)
        assertNull(ZonePoints.dropoff(noPassenger))
        // Phases never told apart: nothing
        assertNull(ZonePoints.pickup(listOf(p(1.0, 1.0, RoutePhase.UNKNOWN))))
    }

    @Test fun fewTripsAreNeverRatedAndPerHourUsesConfirmedMoneyOnly() {
        val h = 3_600_000L
        val trips = (0 until 5).map { trip("m$it", it * h, megacentro, gazcue, minutes = 30, rd = 300) } +
            trip("m-unconfirmed", 6 * h, megacentro, gazcue, minutes = 30, rd = null) +
            (0 until 3).map { trip("g$it", (10 + it) * h, gazcue, megacentro, minutes = 30, rd = 100) }
        val r = ZoneStats.report(trips, ZoneSettings())
        val mega = r.zones.first { it.cells.single() == ZoneGrid.cellOf(megacentro.first, megacentro.second) }
        assertEquals(6, mega.pickups)
        assertEquals(5, mega.confirmed)
        // 5 trips × RD$300 in 5 × 30 min = RD$600/h
        assertEquals(600.0, mega.perHour!!, 1e-6)
        assertTrue(mega.enough)
        val gaz = r.zones.first { it.cells.single() == ZoneGrid.cellOf(gazcue.first, gazcue.second) }
        assertFalse(gaz.enough)
        assertEquals(ZoneRating.FEW, gaz.rating)
        // The zones with enough trips come first
        assertEquals(mega.key, r.zones.first().key)
        assertEquals(9, r.tripsWithGps)
        assertEquals(0, r.tripsWithoutGps)
        assertEquals(6.0, mega.avgPickupMin!!, 1e-9)
        assertEquals(2.0, mega.avgWaitMin!!, 1e-9)
    }

    @Test fun ratingIsAgainstTheDriversOwnMedian() {
        val h = 3_600_000L
        val far = 18.55 to -69.80
        val trips = (0 until 5).map { trip("a$it", it * h, megacentro, gazcue, minutes = 60, rd = 600) } +
            (0 until 5).map { trip("b$it", (10 + it) * h, gazcue, megacentro, minutes = 60, rd = 400) } +
            (0 until 5).map { trip("c$it", (20 + it) * h, far, megacentro, minutes = 60, rd = 200) }
        val r = ZoneStats.report(trips, ZoneSettings())
        assertEquals(400.0, r.medianPerHour!!, 1e-6)
        assertEquals(listOf(ZoneRating.HIGH, ZoneRating.MID, ZoneRating.LOW), r.zones.map { it.rating })
    }

    @Test fun afterADropOffTheNextTripCountsOnlyWithinAnHour() {
        val t1 = trip("1", 0, gazcue, megacentro, minutes = 20)
        val t2 = trip("2", 20 * MIN + 8 * MIN, megacentro, gazcue, minutes = 20)
        val t3 = trip("3", t2.endAt!! + 2 * 60 * MIN, gazcue, megacentro)
        val r = ZoneStats.report(listOf(t3, t1, t2), ZoneSettings())
        val mega = r.zones.first { it.cells.single() == ZoneGrid.cellOf(megacentro.first, megacentro.second) }
        // t1 and t3 ended in Megacentro: only t1 was followed within an hour (8 min)
        assertEquals(2, mega.dropoffs)
        assertEquals(1, mega.nextOffers)
        assertEquals(8.0, mega.avgNextOfferMin!!, 1e-9)
        assertTrue(mega.avgEmptyKm!! > 0)
    }

    @Test fun namedCellsAddUpAndPrivateCellsAreHidden() {
        val a = ZoneGrid.cellOf(megacentro.first, megacentro.second)
        val b = ZoneCell(a.row, a.col + 1)
        val bPoint = b.centerLat to b.centerLng
        val trips = listOf(
            trip("1", 0, megacentro, gazcue), trip("2", 10 * 60 * MIN, bPoint, gazcue),
            trip("3", 20 * 60 * MIN, gazcue, megacentro, address = "C. 3 #4, Gazcue, Santo Domingo")
        )
        val named = ZoneStats.report(trips, ZoneSettings(names = mapOf(a.id to "Megacentro", b.id to "megacentro")))
        val zone = named.zones.first { it.name != null }
        assertEquals(2, zone.cells.size)
        assertEquals(2, zone.pickups)
        val gaz = named.zones.first { it.name == null && it.cells.single() == ZoneGrid.cellOf(gazcue.first, gazcue.second) }
        assertEquals("Gazcue", gaz.uberSays)

        val hidden = ZoneStats.report(trips, ZoneSettings(privateCells = setOf(ZoneGrid.cellOf(gazcue.first, gazcue.second).id)))
        assertTrue(hidden.zones.none { z -> z.cells.any { it == ZoneGrid.cellOf(gazcue.first, gazcue.second) } })
        assertEquals(1, hidden.hiddenPrivate)
        // The other end of those trips still counts
        assertEquals(2, hidden.zones.sumOf { it.pickups })
    }

    @Test fun noTripsMeansNoZonesNotBadZones() {
        val r = ZoneStats.report(emptyList(), ZoneSettings())
        assertTrue(r.zones.isEmpty())
        assertNull(r.medianPerHour)
        val withoutGps = ZoneStats.report(listOf(trip("1", 0, megacentro, gazcue).copy(pickup = null, dropoff = null)), ZoneSettings())
        assertEquals(1, withoutGps.tripsWithoutGps)
        assertTrue(withoutGps.zones.isEmpty())
    }

    @Test fun settingsAreStrict() {
        val names = mapOf("1370:-4920" to "Megacentro")
        assertEquals(names, ZoneSettingsCodec.decodeNames(ZoneSettingsCodec.encodeNames(names)))
        assertEquals(setOf("1:2", "-3:4"), ZoneSettingsCodec.decodePrivate(ZoneSettingsCodec.encodePrivate(setOf("1:2", "-3:4"))))
        assertEquals(emptyMap<String, String>(), ZoneSettingsCodec.decodeNames(null))
        assertNull(ZoneSettingsCodec.decodeNames("""{"no":"Megacentro"}"""))
        assertNull(ZoneSettingsCodec.decodeNames("""{"1:2":""}"""))
        assertNull(ZoneSettingsCodec.decodePrivate("""["1:2", 3]"""))
        val v = RestoreRules.validateSettings(DecodedSettings(mapOf("zone_names" to """{"1:2":"Casa"}""", "zone_private" to "[{]"), emptyList()))
        assertEquals("""{"1:2":"Casa"}""", v.values["zone_names"])
        assertEquals(listOf("zone_private"), v.defaulted)
    }
}
