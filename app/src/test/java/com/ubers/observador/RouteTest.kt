package com.ubers.observador

import com.ubers.observador.data.db.toDomain
import com.ubers.observador.data.db.toEntity
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.distance.TripDistance
import com.ubers.observador.domain.distance.TripDistanceCoverage
import com.ubers.observador.domain.lifecycle.RideLifecycleState
import com.ubers.observador.domain.route.E7
import com.ubers.observador.domain.route.MapMath
import com.ubers.observador.domain.route.PlaceNames
import com.ubers.observador.domain.route.RouteClose
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RouteFiles
import com.ubers.observador.domain.route.RouteFinish
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RoutePolicy
import com.ubers.observador.domain.route.RouteRecorder
import com.ubers.observador.domain.route.RouteSimplifier
import com.ubers.observador.domain.route.RouteStatus
import com.ubers.observador.domain.route.RouteTrack
import com.ubers.observador.domain.route.RouteUpdate
import com.ubers.observador.domain.route.TripRouteSummary
import com.ubers.observador.domain.route.TripSheets
import com.ubers.observador.domain.route.WebMercator
import com.ubers.observador.domain.trips.Trip
import com.ubers.observador.domain.trips.TripOfferSnapshot
import com.ubers.observador.domain.trips.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Fase 8K: the route of the taken trips. Positions are made up around Santo Domingo. */
class RouteTest {

    private val sec = 1_000L
    private val min = 60 * sec
    private val t0 = 1_792_236_000_000L // 2026-10-17 07:20 in Santo Domingo
    private val zone = ZoneOffset.ofHours(-4)

    /** 0.0001° of latitude is about 11.1 m: one step every 5 s is about 8 km/h. */
    private val stepDeg = 0.0001
    private val baseLat = 18.4861
    private val baseLon = -69.9312

    private fun fix(t: Long, steps: Double, accuracy: Float = 5f, east: Double = 0.0) =
        LocationFix(t, baseLat + steps * stepDeg, baseLon + east * stepDeg, accuracy)

    /** Feeds [count] fixes, one step north every 5 s, from [from]. */
    private fun drive(r: RouteRecorder, from: Long, count: Int, startStep: Int = 0): RouteUpdate {
        var out = RouteUpdate.NOTHING
        repeat(count) { i -> out = out + r.onFix(fix(from + i * 5 * sec, (startStep + i).toDouble())) }
        return out
    }

    private fun points(u: RouteUpdate) = u.writes.flatMap { it.points }

    // ---- When it records ----

    @Test fun recordsOnlyWhileATakenTripIsOpen() {
        val r = RouteRecorder()
        assertTrue(drive(r, t0, 10).isEmpty) // online, no trip: nothing
        assertTrue(r.onRide(null, RideLifecycleState.OFFER_VISIBLE, t0 + 60 * sec).isEmpty)
        val open = r.onRide("s1", RideLifecycleState.TO_PICKUP, t0 + 70 * sec)
        assertEquals(listOf(t0 + 70 * sec), open.writes.mapNotNull { it.openAt })
        assertEquals("s1", r.recordingSession)
        val during = drive(r, t0 + 75 * sec, 7)
        val close = r.onRide(null, RideLifecycleState.IDLE, t0 + 120 * sec)
        val all = points(during) + points(close)
        assertEquals(7, all.size)
        assertTrue(all.all { it.phase == RoutePhase.PICKUP })
        assertNotNull(close.writes.single().close)
        assertNull(r.recordingSession)
        assertTrue(drive(r, t0 + 130 * sec, 10).isEmpty) // after the trip: nothing
    }

    @Test fun phasesFollowTheRide() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.TO_PICKUP, t0)
        var out = drive(r, t0 + 5 * sec, 4)
        r.onRide("s1", RideLifecycleState.ARRIVED_OR_WAITING, t0 + 25 * sec)
        out += drive(r, t0 + 25 * sec, 2, startStep = 4)
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0 + 35 * sec)
        out += drive(r, t0 + 35 * sec, 5, startStep = 6)
        out += r.onRide("s1", RideLifecycleState.COMPLETED, t0 + 60 * sec)
        val phases = points(out).map { it.phase }
        assertEquals(List(6) { RoutePhase.PICKUP } + List(5) { RoutePhase.PASSENGER }, phases)
        val close = out.writes.mapNotNull { it.close }.single()
        assertFalse(close.cancelled)
        assertNull(close.trimAfterMs)
    }

    @Test fun pointsAreHandedOutEveryThirtySecondsAndAtTheEnd() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.TO_PICKUP, t0)
        // Fixes at 5..25 s wait in memory; the one at 30 s hands out all six
        val early = drive(r, t0 + 5 * sec, 5)
        assertTrue(early.isEmpty)
        val first = r.onFix(fix(t0 + 30 * sec, 5.0))
        assertEquals(6, points(first).size)
        assertEquals(6, first.writes.single().receivedDelta)
        val rest = drive(r, t0 + 35 * sec, 3, startStep = 6)
        assertTrue(rest.isEmpty)
        val end = r.onRide("s1", RideLifecycleState.CANCELLED, t0 + 50 * sec)
        assertEquals(3, points(end).size)
        assertTrue(end.writes.single().close!!.cancelled)
    }

    @Test fun usesTheSameFiltersAsTheKilometres() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.TO_PICKUP, t0)
        val kept = ArrayList<RoutePoint>()
        listOf(
            fix(t0 + 5 * sec, 0.0),
            fix(t0 + 10 * sec, 1.0, accuracy = 30f),       // worse than 25 m
            fix(t0 + 15 * sec, 2.0, accuracy = Float.NaN), // no accuracy
            fix(t0 + 20 * sec, 400.0),                     // 4.4 km in 15 s: a jump
            fix(t0 + 25 * sec, 0.2),                       // 2 m: jitter, standing still
            fix(t0 + 29 * sec, 1.0)                        // a real step
        ).forEach { kept += points(r.onFix(it)) }
        val end = r.onRide(null, RideLifecycleState.IDLE, t0 + 29 * sec)
        kept += points(end)
        assertEquals(listOf(0L, 1L), kept.map { Math.round((it.latitude - baseLat) / stepDeg) })
        assertEquals(6, end.writes.single().receivedDelta) // every position that came is counted
        assertEquals(50, kept[0].accuracyDm)
    }

    @Test fun aPhaseChangeIsStoredEvenStandingStill() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.ARRIVED_OR_WAITING, t0)
        var out = r.onFix(fix(t0 + 5 * sec, 0.0))
        out += r.onFix(fix(t0 + 10 * sec, 0.1)) // jitter, same phase: not stored
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0 + 12 * sec)
        out += r.onFix(fix(t0 + 15 * sec, 0.1)) // same spot, new phase: stored
        out += r.onRide("s1", RideLifecycleState.COMPLETED, t0 + 20 * sec)
        assertEquals(listOf(RoutePhase.PICKUP, RoutePhase.PASSENGER), points(out).map { it.phase })
    }

    @Test fun stopsAtThreeHours() {
        val policy = RoutePolicy()
        val r = RouteRecorder(policy)
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0)
        drive(r, t0 + 5 * sec, 3)
        val capped = r.onFix(fix(t0 + policy.maxTripMs + sec, 10.0))
        assertTrue(capped.writes.single().capped)
        assertNull(r.recordingSession)
        assertTrue(drive(r, t0 + policy.maxTripMs + 10 * sec, 20, startStep = 11).isEmpty)
        val close = r.onRide("s1", RideLifecycleState.COMPLETED, t0 + policy.maxTripMs + 5 * min)
        assertEquals(RouteCoverage.PARTIAL, close.writes.single().close!!.coverage)
    }

    @Test fun coverageIsFullOnlyFromThePickupScreenWithoutGaps() {
        fun run(first: RideLifecycleState, gapAt: Int?): RouteCoverage {
            val r = RouteRecorder()
            r.onRide("s1", first, t0)
            var t = t0
            repeat(20) { i ->
                t += if (i == gapAt) 90 * sec else 5 * sec
                r.onFix(fix(t, i.toDouble()))
            }
            return r.onRide("s1", RideLifecycleState.COMPLETED, t + 5 * sec).writes.single().close!!.coverage
        }
        assertEquals(RouteCoverage.FULL, run(RideLifecycleState.TO_PICKUP, null))
        assertEquals(RouteCoverage.PARTIAL, run(RideLifecycleState.TO_PICKUP, 8))
        assertEquals(RouteCoverage.PARTIAL, run(RideLifecycleState.IN_TRIP, null)) // seen mid-trip
        // The GPS never came: partial here, «Sin GPS» once closed with no point
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.TO_PICKUP, t0)
        val close = r.onRide("s1", RideLifecycleState.COMPLETED, t0 + 10 * min).writes.single().close!!
        assertEquals(RouteCoverage.PARTIAL, close.coverage)
        val done = RouteFinish.finish(TripRouteSummary("t1", openedAt = t0, updatedAt = t0), emptyList(), close, 5.0)
        assertEquals(RouteCoverage.NO_GPS, done.summary.coverage)
        assertEquals(RouteStatus.CLOSED, done.summary.status)
    }

    @Test fun aTripClosedBySearchingDropsItsLast45Seconds() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0)
        val out = drive(r, t0 + 5 * sec, 30) + r.onRide(null, RideLifecycleState.IDLE, t0 + 160 * sec)
        val close = out.writes.mapNotNull { it.close }.single()
        assertEquals(t0 + 115 * sec, close.trimAfterMs)
        val stored = points(out).mapIndexed { i, p -> p.copy(seq = i + 1) }
        val done = RouteFinish.finish(TripRouteSummary("t1", openedAt = t0, updatedAt = t0), stored, close, 5.0)
        assertTrue(done.points.all { it.atMs <= t0 + 115 * sec })
        assertEquals(t0 + 115 * sec, done.summary.endAt)
    }

    @Test fun lostFromViewPausesWithoutClosing() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0)
        var out = drive(r, t0 + 5 * sec, 2)
        out += r.onRide("s1", RideLifecycleState.UNKNOWN, t0 + 12 * sec)
        out += drive(r, t0 + 15 * sec, 3, startStep = 2) // not recorded
        out += r.onRide("s1", RideLifecycleState.IN_TRIP, t0 + 30 * sec)
        out += drive(r, t0 + 35 * sec, 2, startStep = 5)
        out += r.onRide("s1", RideLifecycleState.COMPLETED, t0 + 50 * sec)
        assertEquals(4, points(out).size)
        assertEquals(1, out.writes.count { it.close != null })
        assertEquals(RouteCoverage.PARTIAL, out.writes.mapNotNull { it.close }.single().coverage)
    }

    @Test fun switchedOffItRecordsNothingAndClosesTheTripUnderWay() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0)
        drive(r, t0 + 5 * sec, 3)
        val off = r.setEnabled(false, t0 + 20 * sec)
        assertEquals(3, points(off).size)
        assertNotNull(off.writes.single().close)
        assertTrue(r.onRide("s2", RideLifecycleState.TO_PICKUP, t0 + min).isEmpty)
        assertTrue(drive(r, t0 + min, 10).isEmpty)
        r.setEnabled(true, t0 + 2 * min)
        assertEquals(1, r.onRide("s3", RideLifecycleState.TO_PICKUP, t0 + 3 * min).writes.size)
    }

    @Test fun anotherTripClosesThePreviousOne() {
        val r = RouteRecorder()
        r.onRide("s1", RideLifecycleState.IN_TRIP, t0)
        val out = r.onRide("s2", RideLifecycleState.TO_PICKUP, t0 + min)
        assertEquals(listOf("s1", "s2"), out.writes.map { it.session })
        assertNotNull(out.writes[0].close)
        assertNotNull(out.writes[1].openAt)
    }

    // ---- Simplifying and closing ----

    private fun p(i: Int, lat: Double, lon: Double, phase: RoutePhase = RoutePhase.PASSENGER) =
        RoutePoint(seq = i + 1, atMs = t0 + i * 5 * sec, latE7 = E7.of(lat), lngE7 = E7.of(lon), accuracyDm = 50, phase = phase)

    @Test fun simplifyingKeepsEndsAndPhaseChangesAndStaysWithin5Metres() {
        // A wobbly street, then a turn, pickup first and passenger after
        val pts = (0 until 120).map { i ->
            val wobble = if (i % 2 == 0) 0.00001 else -0.00001 // ±1 m sideways
            val (lat, lon) = if (i < 60) Pair(baseLat + i * stepDeg, baseLon + wobble) else Pair(baseLat + 59 * stepDeg + wobble, baseLon + (i - 59) * stepDeg)
            p(i, lat, lon, if (i < 40) RoutePhase.PICKUP else RoutePhase.PASSENGER)
        }
        val kept = RouteSimplifier.simplify(pts, 5.0)
        assertTrue("kept ${kept.size}", kept.size in 4..8)
        assertEquals(pts.first(), kept.first())
        assertEquals(pts.last(), kept.last())
        assertTrue(kept.contains(pts[39]) && kept.contains(pts[40])) // the phase change
        // Every original point is within 5 m of the simplified line of its phase
        val runs = RouteSimplifier.runsOf(kept)
        pts.forEach { o ->
            val run = runs.first { it.first().phase == o.phase }
            val d = run.zipWithNext().minOf { (a, b) -> RouteSimplifier.distanceToSegmentM(o, a, b) }
            assertTrue("off by $d m", d <= 5.0 + 1e-6)
        }
        assertEquals(kept, RouteSimplifier.simplify(kept, 5.0)) // again: nothing changes
    }

    @Test fun closingFindsStartAndEndAndRenamesUndistinguishedPickup() {
        val pts = listOf(p(0, 18.48, -69.93, RoutePhase.PICKUP), p(1, 18.481, -69.93, RoutePhase.PICKUP), p(2, 18.482, -69.931, RoutePhase.PICKUP))
        val base = TripRouteSummary("t1", openedAt = t0, updatedAt = t0)
        val ended = RouteFinish.finish(base, pts, RouteClose(t0 + min, RouteCoverage.FULL), 5.0)
        assertTrue(ended.points.all { it.phase == RoutePhase.UNKNOWN })
        assertFalse(ended.summary.startWithPassenger)
        assertEquals(pts.first().latE7, ended.summary.startLatE7)
        assertEquals(pts.last().lngE7, ended.summary.endLngE7)
        assertEquals(RouteCoverage.FULL, ended.summary.coverage)
        // Cancelled: the pickup stays pickup
        val cancelled = RouteFinish.finish(base, pts, RouteClose(t0 + min, RouteCoverage.FULL, cancelled = true), 5.0)
        assertTrue(cancelled.points.all { it.phase == RoutePhase.PICKUP })
        // With a passenger point: the start is where the rider got in
        val withRider = pts + p(3, 18.483, -69.932, RoutePhase.PASSENGER) + p(4, 18.485, -69.935, RoutePhase.PASSENGER)
        val s = RouteFinish.finish(base.copy(coverage = RouteCoverage.PARTIAL), withRider, RouteClose(t0 + min, RouteCoverage.FULL), 5.0).summary
        assertTrue(s.startWithPassenger)
        assertEquals(t0 + 15 * sec, s.startAt)
        assertEquals(RouteCoverage.PARTIAL, s.coverage) // a restart left it partial
        // A place name goes when its point changes, and stays when it does not
        val named = s.copy(startPlace = "Calle X, Gazcue", endPlace = "Calle Y, Naco")
        val again = RouteFinish.withEnds(named, withRider.dropLast(1))
        assertEquals("Calle X, Gazcue", again.startPlace)
        assertNull(again.endPlace)
    }

    @Test fun cappedStaysCapped() {
        val base = TripRouteSummary("t1", status = RouteStatus.CAPPED, openedAt = t0, updatedAt = t0)
        val done = RouteFinish.finish(base, listOf(p(0, 18.48, -69.93)), RouteClose(t0 + min, RouteCoverage.PARTIAL), 5.0)
        assertEquals(RouteStatus.CAPPED, done.summary.status)
    }

    // ---- Files ----

    private fun track(name: String? = "Stefany & Co") = RouteTrack(
        tripId = "trip-1",
        riderName = name,
        title = "Stefany · 6:42 → 7:01",
        description = "Recogida (Uber): Calle 5, Los Ríos <norte>",
        summary = TripRouteSummary("trip-1", status = RouteStatus.CLOSED, openedAt = t0, updatedAt = t0, startPlace = "Calle 5, Los Ríos", endPlace = "Av. Lope de Vega, Naco"),
        points = listOf(p(0, 18.4861234, -69.9312001, RoutePhase.PICKUP), p(1, 18.4871, -69.9312, RoutePhase.PICKUP), p(2, 18.4881, -69.9302), p(3, 18.4891, -69.9292))
    )

    @Test fun e7IsExactBothWays() {
        assertEquals(184_861_234, E7.of(18.4861234))
        assertEquals("18.4861234", E7.text(184_861_234))
        assertEquals("-69.9312001", E7.text(E7.of(-69.9312001)))
        assertEquals("-0.0000005", E7.text(-5))
        assertEquals("0.0000000", E7.text(0))
        assertEquals(-1_800_000_000, E7.of(-180.0))
    }

    @Test fun gpxIsValidXmlWithEveryPointAndTheRider() {
        val gpx = RouteFiles.gpx(listOf(track()), t0)
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(gpx.byteInputStream())
        val trkpts = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(4, trkpts.length)
        assertEquals("18.4861234", trkpts.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("-69.9312001", trkpts.item(0).attributes.getNamedItem("lon").nodeValue)
        assertEquals(2, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length) // pickup, passenger
        assertEquals(2, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "wpt").length)
        assertTrue(gpx.contains("Pasajero: Stefany &amp; Co"))
        assertTrue(gpx.contains("&lt;norte&gt;"))
        assertTrue(gpx.contains("<time>2026-10-17T11:20:00Z</time>"))
    }

    @Test fun geoJsonIsValidWithLongitudeFirst() {
        val json = RouteFiles.geoJson(listOf(track()), t0)
        assertTrue(JsonCheck.valid(json.trim()))
        assertTrue(json.contains("[-69.9312001,18.4861234]"))
        assertTrue(json.contains("\"riderName\":\"Stefany & Co\""))
        assertTrue(json.contains("\"phase\":\"PASSENGER\""))
        assertTrue(json.contains("\"place\":\"Av. Lope de Vega, Naco\""))
        // A trip without points adds nothing
        val empty = RouteFiles.geoJson(listOf(track().copy(points = emptyList())), t0)
        assertTrue(JsonCheck.valid(empty.trim()))
        assertTrue(empty.contains("\"features\":[]"))
    }

    @Test fun routeFileNamesSayWhen() {
        assertEquals("observador_recorrido_2026-10-17_0720.gpx", RouteFiles.fileName(t0, zone, "gpx"))
    }

    // ---- The trip's card ----

    private fun trip(status: TripStatus, started: Long?, end: Long?) = Trip(
        id = "trip-1", lifecycleSessionId = "s1", relatedOfferHash = "h", status = status, recovered = false,
        startedFromKnownOffer = true, acceptedAt = t0, tripStartedAt = started,
        completedAt = end.takeIf { status == TripStatus.COMPLETED },
        cancelledAt = end.takeIf { status == TripStatus.CANCELLED },
        interruptedAt = end.takeIf { status == TripStatus.INTERRUPTED },
        createdAt = t0, updatedAt = t0,
        offer = TripOfferSnapshot(150.0, 1.2, 4, 3.9, 10, 70, t0 - 10 * sec, pickupAddress = "Calle 5, Los Ríos", dropoffAddress = "Av. Lope de Vega, Los Prados"),
        riderName = "Stefany"
    )

    private val distance = TripDistance("s1", pickupMm = 1_500_000, tripMm = 4_600_000, coverage = TripDistanceCoverage.FULL, updatedAt = t0)

    @Test fun theCardSaysWhoWhenAndWhere() {
        val t = trip(TripStatus.COMPLETED, started = t0 + 6 * min, end = t0 + 23 * min)
        val route = TripRouteSummary("trip-1", status = RouteStatus.CLOSED, openedAt = t0, updatedAt = t0, startPlace = "Calle 9, Los Ríos", endPlace = null)
        val s = TripSheets.of(t, route, distance)
        assertTrue(s.startDistinguished)
        assertEquals(t0 + 6 * min, s.startAt)
        assertEquals(t0 + 23 * min, s.endAt)
        assertEquals("Calle 9, Los Ríos", s.startShown)          // from the GPS
        assertEquals("Av. Lope de Vega, Los Prados", s.endShown)  // from Uber, no GPS name yet
        assertEquals(listOf("Recogida", "Viaje"), s.comparisons.map { it.label })
        assertEquals("Uber dijo 1.2 km y 4 min; hiciste 1.5 km y 6 min", s.comparisons[0].text)
        assertEquals("Uber dijo 3.9 km y 10 min; hiciste 4.6 km y 17 min", s.comparisons[1].text)
        assertEquals("Stefany · 7:26 → 7:43 · Los Ríos → Los Prados", TripSheets.oneLine(s, zone))
    }

    @Test fun withoutIniciarViajeTheCardSaysItAndComparesTheWhole() {
        val t = trip(TripStatus.INTERRUPTED, started = null, end = t0 + 30 * min)
        val route = TripRouteSummary("trip-1", status = RouteStatus.CLOSED, openedAt = t0, updatedAt = t0, endAt = t0 + 29 * min, startWithPassenger = false)
        val s = TripSheets.of(t, route, distance)
        assertFalse(s.startDistinguished)
        assertEquals(t0, s.startAt)
        assertEquals(t0 + 29 * min, s.endAt) // the route's last point, before the 45 s of «Buscando»
        assertEquals("Total (recogida + viaje)", s.comparisons.single().label)
        assertEquals("Uber dijo 5.1 km y 14 min; hiciste 6.1 km y 29 min", s.comparisons.single().text)
        // A trip still open has no end, and no route is needed
        val open = TripSheets.of(trip(TripStatus.TO_PICKUP, null, null), null, null)
        assertNull(open.endAt)
        assertEquals("Stefany · 7:20 · Los Ríos → Los Prados", TripSheets.oneLine(open, zone))
    }

    @Test fun placeNamesFromTheMapService() {
        assertEquals("Av. Winston Churchill 95, Piantini", PlaceNames.of("Av. Winston Churchill", "95", "Piantini", "Santo Domingo", "95"))
        assertEquals("Calle El Conde, Santo Domingo", PlaceNames.of("Calle El Conde", null, null, "Santo Domingo", null))
        assertEquals("Ágora Mall, Serrallés", PlaceNames.of(null, null, "Serrallés", "Santo Domingo", "Ágora Mall"))
        assertEquals("Los Alcarrizos", PlaceNames.of(null, null, null, "Los Alcarrizos", "123"))
        assertNull(PlaceNames.of(null, null, null, null, "7"))
        assertEquals("Los Alcarrizos", PlaceNames.short("Calle 5, Los Alcarrizos"))
        assertEquals("Piantini", PlaceNames.short("Piantini"))
        assertNull(PlaceNames.short("  "))
        assertEquals("1 h 05 min", TripSheets.duration(65 * min))
        assertEquals("17 min", TripSheets.duration(17 * min + 20 * sec))
    }

    @Test fun testRoutesOnlyForTestTripsAndLikeARealOne() {
        val real = trip(TripStatus.COMPLETED, started = t0 + 6 * min, end = t0 + 23 * min)
        assertNull(com.ubers.observador.data.debug.RouteTestSeeder.generate(real))
        val (summary, points) = com.ubers.observador.data.debug.RouteTestSeeder.generate(real.copy(id = "TEST-trip-1"))!!
        assertEquals("TEST-trip-1", summary.tripId)
        assertEquals(RouteStatus.CLOSED, summary.status)
        assertTrue(summary.startWithPassenger)
        assertEquals(t0 + 6 * min, summary.startAt)
        assertEquals(t0 + 23 * min, summary.endAt)
        assertEquals(points.size, summary.pointsKept)
        assertTrue(points.size in 4..60) // a few streets, simplified
        assertEquals(setOf(RoutePhase.PICKUP, RoutePhase.PASSENGER), points.map { it.phase }.toSet())
        // Without «Iniciar viaje» the whole route is not told apart
        val (_, undistinguished) = com.ubers.observador.data.debug.RouteTestSeeder.generate(
            trip(TripStatus.INTERRUPTED, started = null, end = t0 + 30 * min).copy(id = "TEST-trip-4")
        )!!
        assertTrue(undistinguished.all { it.phase == RoutePhase.UNKNOWN })
    }

    // ---- Privacy: the 8E rule, split ----

    @Test fun onlyTheRouteTablesHoldCoordinates() {
        // Whole words of the field name: «relatedOfferHash» is not a latitude
        val forbidden = setOf("lat", "lon", "lng", "latitude", "longitude", "coord", "coords", "coordinates")
        fun holds(name: String) = name.split(Regex("(?=[A-Z])")).any { it.lowercase() in forbidden }
        listOf(
            com.ubers.observador.data.db.TripEntity::class.java, com.ubers.observador.data.db.RideEventEntity::class.java,
            com.ubers.observador.data.db.OfferEntity::class.java, com.ubers.observador.data.db.DistanceSegmentEntity::class.java,
            com.ubers.observador.data.db.TripDistanceEntity::class.java
        ).forEach { cls ->
            cls.declaredFields.forEach { f -> assertFalse("${cls.simpleName}.${f.name}", holds(f.name)) }
        }
        // The route tables keep whole 1e-7 degrees, never a floating point
        listOf(com.ubers.observador.data.db.TripRoutePointEntity::class.java, com.ubers.observador.data.db.TripRouteEntity::class.java).forEach { cls ->
            val coords = cls.declaredFields.filter { f -> holds(f.name) }
            assertTrue(cls.simpleName, coords.size >= 2)
            coords.forEach { f -> assertTrue("${cls.simpleName}.${f.name}", f.type == Integer.TYPE || f.type == Integer::class.java) }
        }
    }

    @Test fun routeRowsSurviveTheDatabase() {
        val s = TripRouteSummary(
            "trip-1", RouteStatus.CAPPED, RouteCoverage.PARTIAL, t0, t0 + min, 40, 12, t0, t0 + min, 75,
            t0 + 5 * sec, 184_861_234, -699_312_001, true, t0 + min, 184_900_000, -699_000_000, "Calle 9, Los Ríos", null, t0 + min
        )
        assertEquals(s, s.toEntity().toDomain())
        val pt = p(3, 18.4861234, -69.9312001, RoutePhase.UNKNOWN)
        val e = pt.toEntity("trip-1", 7)
        assertEquals(7, e.seq)
        assertEquals(pt.copy(seq = 7), e.toDomain())
    }

    // ---- The street map ----

    @Test fun mercatorAndTilesAgree() {
        assertEquals(0.5, WebMercator.x(0.0), 1e-12)
        assertEquals(0.5, WebMercator.y(0.0), 1e-12)
        assertEquals(0.0, WebMercator.y(90.0), 1e-9) // clamped to the edge of the map
        // Santo Domingo at zoom 15 is tile 10018 / 14671 (OpenStreetMap numbering)
        val view = com.ubers.observador.domain.route.MapView(15.0, WebMercator.x(baseLon), WebMercator.y(baseLat))
        val tiles = MapMath.tiles(view, 256.0, 256.0, 256.0)
        val centre = tiles.first { it.left <= 128.0 && it.left + it.size > 128.0 && it.top <= 128.0 && it.top + it.size > 128.0 }
        assertEquals(10018, centre.x)
        assertEquals(14671, centre.y)
        // The tiles cover the whole map, with no hole
        val w = 1080.0
        val h = 700.0
        val cover = MapMath.tiles(view.copy(zoom = 15.6), w, h, 256.0)
        listOf(0.0 to 0.0, w - 1 to 0.0, 0.0 to h - 1, w - 1 to h - 1, w / 2 to h / 2).forEach { (x, y) ->
            assertTrue(cover.any { x >= it.left && x < it.left + it.size && y >= it.top && y < it.top + it.size })
        }
    }

    @Test fun fittingKeepsTheWholeRouteOnScreen() {
        val pts = track().points + p(9, 18.52, -69.88)
        val w = 1000.0
        val h = 600.0
        val view = MapMath.fit(pts, w, h, 256.0, 40.0)!!
        pts.forEach { pt ->
            val (x, y) = MapMath.toScreen(pt.longitude, pt.latitude, view, w, h, 256.0)
            assertTrue("x $x", x >= 39.9 && x <= w - 39.9)
            assertTrue("y $y", y >= 39.9 && y <= h - 39.9)
        }
        // One point alone: the closest zoom allowed
        assertEquals(17.0, MapMath.fit(listOf(pts[0]), w, h, 256.0, 40.0)!!.zoom, 1e-9)
        assertNull(MapMath.fit(emptyList(), w, h, 256.0, 40.0))
        // Dragging moves the centre the other way, zooming stays in range
        val moved = MapMath.pan(view, 100.0, 0.0, 256.0)
        assertTrue(moved.centerX < view.centerX)
        assertEquals(MapMath.MAX_ZOOM, MapMath.zoomBy(view, 1e9).zoom, 1e-9)
    }
}
