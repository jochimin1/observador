package com.ubers.observador

import com.ubers.observador.domain.backup.BackupCrypto
import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.domain.backup.FullExport
import com.ubers.observador.domain.distance.DistanceBucket
import com.ubers.observador.domain.distance.DistancePolicy
import com.ubers.observador.domain.distance.LocationFix
import com.ubers.observador.domain.gps.BatteryCodec
import com.ubers.observador.domain.gps.BatteryCondition
import com.ubers.observador.domain.gps.BatterySample
import com.ubers.observador.domain.gps.BatteryStats
import com.ubers.observador.domain.gps.FixVerdict
import com.ubers.observador.domain.gps.GpsFrequency
import com.ubers.observador.domain.gps.GpsPlan
import com.ubers.observador.domain.gps.MovementDetector
import com.ubers.observador.domain.gps.SignalCodec
import com.ubers.observador.domain.gps.SignalRecord
import com.ubers.observador.domain.gps.SignalSummary
import com.ubers.observador.domain.gps.SignalTracker
import com.ubers.observador.domain.health.HealthArea
import com.ubers.observador.domain.health.HealthFix
import com.ubers.observador.domain.health.HealthLevel
import com.ubers.observador.domain.health.HealthRules
import com.ubers.observador.domain.health.HealthSignals
import com.ubers.observador.domain.route.RouteClose
import com.ubers.observador.domain.route.RouteCoverage
import com.ubers.observador.domain.route.RouteFinish
import com.ubers.observador.domain.route.RoutePhase
import com.ubers.observador.domain.route.RoutePoint
import com.ubers.observador.domain.route.RoutePolicy
import com.ubers.observador.domain.route.TileCache
import com.ubers.observador.domain.route.TripRouteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.security.SecureRandom
import java.time.ZoneOffset

/** 1.45.0: GPS by phase, signal and battery accounting, full backups and exports, map cache. */
class GpsPrivacyTest {

    private val t0 = 1_800_000_000_000L
    private val sec = 1_000L
    private val min = 60_000L

    /** A position [m] metres north of a fixed point, at [t]. */
    private fun fix(t: Long, m: Double = 0.0, acc: Float = 5f) = LocationFix(t, 18.48 + m / 111_195.0, -69.93, acc)

    // ---- GPS plan ----

    @Test fun fixedFrequencyIsTheValidatedFiveSecondsEverywhere() {
        for (p in DistanceBucket.entries) for (moving in listOf(true, false)) {
            assertEquals(5_000L, GpsPlan.of(GpsFrequency.FIXED, p, moving).intervalMs)
        }
        assertEquals(GpsFrequency.FIXED, GpsFrequency.DEFAULT)
        assertEquals(GpsFrequency.FIXED, GpsFrequency.fromStorage(null))
        assertEquals(GpsFrequency.ADAPTIVE, GpsFrequency.fromStorage("ADAPTIVE"))
    }

    @Test fun adaptiveSlowsDownOnlyWhenStoppedOrWithoutTrip() {
        assertEquals(5_000L, GpsPlan.of(GpsFrequency.ADAPTIVE, DistanceBucket.PASSENGER, moving = true).intervalMs)
        assertEquals(5_000L, GpsPlan.of(GpsFrequency.ADAPTIVE, DistanceBucket.PICKUP, moving = true).intervalMs)
        assertEquals(10_000L, GpsPlan.of(GpsFrequency.ADAPTIVE, DistanceBucket.PASSENGER, moving = false).intervalMs)
        assertEquals(8_000L, GpsPlan.of(GpsFrequency.ADAPTIVE, DistanceBucket.EMPTY, moving = true).intervalMs)
        assertEquals(20_000L, GpsPlan.of(GpsFrequency.ADAPTIVE, DistanceBucket.EMPTY, moving = false).intervalMs)
        // Never so slow that every step becomes a signal gap
        for (p in DistanceBucket.entries) for (moving in listOf(true, false)) {
            val r = GpsPlan.of(GpsFrequency.ADAPTIVE, p, moving)
            assertTrue(r.intervalMs <= DistancePolicy().signalGapMs / 2)
            assertTrue(r.minIntervalMs in 1_000L..r.intervalMs)
        }
    }

    @Test fun movementNeedsEvidenceBeforeSayingStopped() {
        val d = MovementDetector()
        assertTrue(d.moving)
        // Two positions in the same place are not enough
        d.onFix(fix(t0)); d.onFix(fix(t0 + 5 * sec))
        assertTrue(d.moving)
        // A minute and a half within a few metres: stopped
        var t = t0 + 10 * sec
        while (t <= t0 + 95 * sec) { d.onFix(fix(t, m = (t / 1000 % 3).toDouble())); t += 5 * sec }
        assertFalse(d.moving)
        // Bad positions do not change it
        assertFalse(d.onFix(fix(t, m = 500.0, acc = 80f)))
        assertFalse(d.moving)
        // Moving 50 m: moving again at once
        assertTrue(d.onFix(fix(t + 5 * sec, m = 50.0)))
        assertTrue(d.moving)
    }

    // ---- Signal ----

    @Test fun signalCountsVerdictsWithTheKilometreFilters() {
        val s = SignalTracker()
        s.onRequest(true, t0)
        s.onPhase(DistanceBucket.PASSENGER, t0)
        assertEquals(FixVerdict.KEPT, s.onFix(fix(t0 + 5 * sec)))
        assertEquals(FixVerdict.ACCURACY, s.onFix(fix(t0 + 10 * sec, m = 40.0, acc = 30f)))
        assertEquals(FixVerdict.JITTER, s.onFix(fix(t0 + 15 * sec, m = 3.0)))
        assertEquals(FixVerdict.KEPT, s.onFix(fix(t0 + 20 * sec, m = 60.0)))
        assertEquals(FixVerdict.JUMP, s.onFix(fix(t0 + 25 * sec, m = 2_000.0)))
        val r = s.flush(t0 + 30 * sec).single()
        assertEquals(DistanceBucket.PASSENGER, r.phase)
        assertEquals(5, r.received); assertEquals(2, r.kept)
        assertEquals(1, r.accuracy); assertEquals(1, r.jitter); assertEquals(1, r.jump)
        assertEquals(30 * sec, r.requestedMs)
        assertEquals(0, r.gaps)
        // Flushed: nothing left
        assertTrue(s.flush(t0 + 30 * sec).isEmpty())
    }

    @Test fun aSilenceOverAMinuteIsOneGapEvenAcrossFlushes() {
        val s = SignalTracker()
        s.onRequest(true, t0)
        s.onPhase(DistanceBucket.PICKUP, t0)
        s.onFix(fix(t0 + 5 * sec))
        // Silent from +5 s; a flush at +2 min writes the gap so far
        val first = s.flush(t0 + 2 * min).single()
        assertEquals(1, first.gaps)
        assertEquals(2 * min - 5 * sec, first.gapMs)
        // Signal back at +3 min: the same gap grows, it is not counted twice
        s.onFix(fix(t0 + 3 * min, m = 500.0))
        val second = s.flush(t0 + 3 * min).single()
        assertEquals(0, second.gaps)
        assertEquals(1 * min, second.gapMs)
        val sum = SignalSummary.of(listOf(first, second)).byPhase.getValue(DistanceBucket.PICKUP)
        assertEquals(3 * min - 5 * sec, sum.gapMs)
        assertEquals(3 * min, sum.requestedMs)
        assertEquals(2, SignalSummary.coveragePct(sum))
        // A short silence is not a gap
        s.onFix(fix(t0 + 3 * min + 50 * sec, m = 900.0))
        assertEquals(0, s.flush(t0 + 4 * min).single().gaps)
    }

    @Test fun phasesGetTheirOwnTimeAndReleasingStopsTheClock() {
        val s = SignalTracker()
        s.onRequest(true, t0)
        s.onPhase(DistanceBucket.PICKUP, t0)
        s.onFix(fix(t0 + 5 * sec))
        s.onPhase(DistanceBucket.PASSENGER, t0 + 20 * sec)
        s.onFix(fix(t0 + 25 * sec, m = 100.0))
        s.onRequest(false, t0 + 40 * sec)
        val out = s.flush(t0 + 10 * min).associateBy { it.phase }
        assertEquals(20 * sec, out.getValue(DistanceBucket.PICKUP).requestedMs)
        assertEquals(20 * sec, out.getValue(DistanceBucket.PASSENGER).requestedMs)
        assertTrue(out.values.all { it.gaps == 0 })
    }

    @Test fun signalLinesRoundTripAndBadLinesAreSkipped() {
        val r = SignalRecord(t0, DistanceBucket.EMPTY, 60_000, 5_000, 1, 12, 9, 1, 1, 1)
        assertEquals(r, SignalCodec.decode(SignalCodec.encode(r)))
        assertNull(SignalCodec.decode("s1;x;EMPTY;1;1;1;1;1;1;1;1"))
        assertNull(SignalCodec.decode("s1;1;NOPE;1;1;1;1;1;1;1;1"))
        assertNull(SignalCodec.decode("s1;1;EMPTY;-1;1;1;1;1;1;1;1"))
        assertEquals(listOf(r), SignalCodec.decodeAll(SignalCodec.encode(r) + "\nbasura\n"))
        val line = SignalSummary.line(r)
        assertTrue(line, line.startsWith("En línea sin viaje: 91 % con señal · 1 hueco (5 s) · 12 posiciones"))
        assertTrue(line.contains("1 por precisión") && line.contains("1 salto"))
    }

    @Test fun summaryOfATripUsesOnlyItsWindow() {
        val a = SignalRecord(t0, DistanceBucket.PICKUP, requestedMs = 60_000)
        val b = SignalRecord(t0 + 10 * min, DistanceBucket.PICKUP, requestedMs = 30_000)
        val sum = SignalSummary.of(listOf(a, b), t0 + min, t0 + 20 * min)
        assertEquals(30_000L, sum.total!!.requestedMs)
    }

    // ---- Battery ----

    private fun sample(t: Long, pct: Int, uah: Long? = null, gps: Boolean = true, f: GpsFrequency = GpsFrequency.FIXED, charging: Boolean = false) =
        BatterySample(t, pct, uah, charging, screenOn = true, gpsOn = gps, frequency = f, phase = DistanceBucket.PASSENGER)

    @Test fun batteryRatesJoinOnlyComparableSteps() {
        val samples = ArrayList<BatterySample>()
        // 40 min of fixed GPS on battery: 100 → 92 %
        for (i in 0..40) samples += sample(t0 + i * min, 100 - i / 5, uah = 4_000_000L - i * 10_000L)
        // charging in between: left out
        samples += sample(t0 + 41 * min, 92, charging = true)
        samples += sample(t0 + 42 * min, 95, charging = true)
        // 10 min adaptive: not enough yet
        for (i in 0..10) samples += sample(t0 + 50 * min + i * min, 95 - i / 5, f = GpsFrequency.ADAPTIVE)
        val rates = BatteryStats.rates(samples).associateBy { it.condition }
        val fixed = rates.getValue(BatteryCondition.GPS_FIXED)
        assertEquals(40 * min, fixed.observedMs)
        assertEquals(8, fixed.percentUsed)
        assertEquals(12.0, fixed.percentPerHour!!, 0.001)
        assertEquals(600.0, fixed.mahPerHour!!, 0.001)
        assertTrue(fixed.enough)
        val adaptive = rates.getValue(BatteryCondition.GPS_ADAPTIVE)
        assertFalse(adaptive.enough)
        assertNull(adaptive.mahUsed)
        assertTrue(BatteryStats.line(adaptive).contains("muestra insuficiente"))
        assertTrue(BatteryStats.line(fixed), BatteryStats.line(fixed).startsWith("GPS fija (5 s): 12.0 %/h · 600 mAh/h"))
    }

    @Test fun batteryStepsAcrossAStopOrAConditionChangeAreNotJoined() {
        val rates = BatteryStats.rates(listOf(
            sample(t0, 80), sample(t0 + 10 * min, 70), // 10 min apart: service stopped
            sample(t0 + 11 * min, 70, gps = false), sample(t0 + 12 * min, 70, gps = true)
        ))
        assertTrue(rates.isEmpty())
    }

    @Test fun batteryLinesRoundTrip() {
        val s = sample(t0, 57, uah = 2_100_000, f = GpsFrequency.ADAPTIVE)
        assertEquals(s, BatteryCodec.decode(BatteryCodec.encode(s)))
        val noCounter = sample(t0, 57)
        assertEquals(noCounter, BatteryCodec.decode(BatteryCodec.encode(noCounter)))
        assertNull(BatteryCodec.decode("b1;1;101;;0;1;1;FIXED;PASSENGER"))
        assertNull(BatteryCodec.decode("otra cosa"))
    }

    // ---- Encryption ----

    private fun encrypt(data: ByteArray, password: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypting(out, password.toCharArray(), SecureRandom(), iterations = 10_000).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decrypt(data: ByteArray, password: String): ByteArray =
        BackupCrypto.decrypting(data.inputStream(), password.toCharArray()).use { it.readBytes() }

    @Test fun encryptedFilesRoundTripAtEverySize() {
        val r = java.util.Random(7)
        for (size in listOf(0, 1, 1000, BackupCrypto.CHUNK - 1, BackupCrypto.CHUNK, BackupCrypto.CHUNK + 1, 2 * BackupCrypto.CHUNK + 17)) {
            val data = ByteArray(size).also { r.nextBytes(it) }
            val enc = encrypt(data, "clave-larga-123")
            assertTrue(BackupCrypto.isEncrypted(enc))
            assertTrue("size $size", decrypt(enc, "clave-larga-123").contentEquals(data))
        }
        assertFalse(BackupCrypto.isEncrypted("PK\u0003\u0004".toByteArray()))
    }

    @Test fun aWrongPasswordOrATamperedOrCutFileIsRefused() {
        val data = ByteArray(BackupCrypto.CHUNK + 500) { (it % 251).toByte() }
        val enc = encrypt(data, "clave-larga-123")
        try { decrypt(enc, "otra-clave-999"); fail("wrong password accepted") } catch (_: BackupCrypto.WrongPassword) { }
        val tampered = enc.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        try { decrypt(tampered, "clave-larga-123"); fail("tampered accepted") } catch (_: BackupCrypto.Damaged) { }
        // Cut after the first (full) piece: the last piece is missing
        val firstPiece = 8 + 1 + 4 + 16 + 8 + 4 + 4 + BackupCrypto.CHUNK + 16
        try { decrypt(enc.copyOf(firstPiece), "clave-larga-123"); fail("cut accepted") } catch (_: BackupCrypto.Damaged) { }
        try { decrypt(enc + byteArrayOf(1, 2, 3), "clave-larga-123"); fail("extra accepted") } catch (_: BackupCrypto.Damaged) { }
    }

    @Test fun weakPasswordsAreRefused() {
        assertNotNull(BackupCrypto.passwordProblem("corta".toCharArray()))
        assertNotNull(BackupCrypto.passwordProblem("aaaaaaaaaa".toCharArray()))
        assertNull(BackupCrypto.passwordProblem("moto-2026-DO".toCharArray()))
    }

    // ---- Backup format 2: the app's own files travel ----

    @Test fun dataEntriesAreOnlyTheAppFoldersOneLevelDeep() {
        assertEquals("archivos/gps/senal-2026-11-13.txt", BackupFormat.dataEntry("gps", "senal-2026-11-13.txt"))
        assertEquals("bateria" to "2026-11-13.txt", BackupFormat.parseDataEntry("archivos/bateria/2026-11-13.txt"))
        for (bad in listOf("archivos/../observador.db", "archivos/gps/../x", "archivos/otra/x.txt", "archivos/gps/a/b.txt",
            "archivos/gps/.oculto", "archivos/gps/", "gps/x.txt")) assertNull(bad, BackupFormat.parseDataEntry(bad))
        assertNull(BackupFormat.entriesProblem(listOf("ficha.json", "ajustes.json", "observador.db", "archivos/cierres/2026-11-13.txt")))
        assertNotNull(BackupFormat.entriesProblem(listOf("ficha.json", "ajustes.json", "observador.db", "archivos/../x")))
        assertNotNull(BackupFormat.entriesProblem(listOf("ficha.json", "ajustes.json")))
        assertNotNull(BackupFormat.entriesProblem(listOf("ficha.json", "ajustes.json", "observador.db", "ficha.json")))
    }

    @Test fun everyDataFileMustMatchTheSheetBothWays() {
        val f = "archivos/gps/a.txt"
        val m = BackupManifest(2, "1.45.0", 54, 17, t0, mapOf("trips" to 1),
            mapOf(BackupFormat.ENTRY_DB to "aa", BackupFormat.ENTRY_SETTINGS to "bb", f to "cc"))
        val ok = mapOf(BackupFormat.ENTRY_DB to "aa", BackupFormat.ENTRY_SETTINGS to "bb", f to "cc")
        assertTrue(BackupFormat.mismatches(m, ok).isEmpty())
        assertEquals(listOf(f), BackupFormat.mismatches(m, ok - f))
        assertEquals(listOf("archivos/gps/b.txt"), BackupFormat.mismatches(m, ok + ("archivos/gps/b.txt" to "dd")))
        assertEquals(listOf(f), BackupFormat.dataFiles(m))
        assertEquals(m, BackupFormat.decodeManifest(BackupFormat.encodeManifest(m)))
        assertTrue(BackupFormat.createdText(m, 2048).contains("1 archivo"))
        assertEquals(2, BackupFormat.FORMAT_VERSION)
    }

    // ---- Full export ----

    @Test fun fullExportWritesEveryColumnAsIs() {
        val w = StringWriter()
        val t = FullExport.TableWriter(w, listOf("id", "rider_name", "lat_e7", "km", "blob", "nada"))
        t.row(listOf("t1", "Stefany, \"la de Engombe\"", 184_800_000, 4.5, byteArrayOf(1, 2), null))
        assertEquals(1, t.rows)
        assertEquals("﻿id,rider_name,lat_e7,km,blob,nada\r\nt1,\"Stefany, \"\"la de Engombe\"\"\",184800000,4.5,AQI=,\r\n", w.toString())
        assertEquals("3.0", FullExport.cell(3.0))
        assertEquals("0.0001", FullExport.cell(0.0001))
        try { t.row(listOf("x")); fail("short row") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fullExportKeepsDataTablesAndSaysItIsComplete() {
        assertTrue(FullExport.isDataTable("trips"))
        assertFalse(FullExport.isDataTable("sqlite_sequence"))
        assertFalse(FullExport.isDataTable("room_master_table"))
        assertFalse(FullExport.isDataTable("android_metadata"))
        assertFalse(FullExport.isDataTable("x; DROP"))
        assertEquals("datos/trip_route_points.csv", FullExport.tableEntry("trip_route_points"))
        val text = FullExport.readme(FullExport.Content(t0, "1.45.0", 17, mapOf("trips" to 3), 2, 900,
            mapOf("archivos/gps/a.txt" to 10L), 40, encrypted = false), ZoneOffset.UTC)
        assertTrue(text.contains("TODOS los datos"))
        assertTrue(text.contains("trips: 3 filas"))
        assertTrue(text.contains("NO está cifrado"))
        assertTrue(FullExport.fileName(t0, ZoneOffset.UTC, encrypted = true).endsWith(".zip.cifrado"))
    }

    // ---- Map cache ----

    @Test fun tilesAreRevalidatedNotRedownloaded() {
        val day = 24 * 3_600_000L
        assertEquals(TileCache.Decision.DOWNLOAD, TileCache.decide(null, false))
        assertEquals(TileCache.Decision.USE, TileCache.decide(2 * day, false))
        assertEquals(TileCache.Decision.REVALIDATE, TileCache.decide(8 * day, true))
        assertEquals(TileCache.Decision.USE, TileCache.decide(8 * day, false))
        assertEquals(TileCache.Decision.DOWNLOAD, TileCache.decide(31 * day, false))
        assertEquals("\"abc\"" to null, TileCache.decodeValidator(TileCache.encodeValidator("\"abc\"", null)))
        assertNull(TileCache.encodeValidator(" ", null))
        assertNull(TileCache.decodeValidator("roto"))
    }

    @Test fun trimmingRemovesTheOldestUntilThreeQuarters() {
        val e = (1..10).map { TileCache.Entry("t$it", 10, it.toLong()) }
        assertTrue(TileCache.toTrim(e, limit = 100).isEmpty())
        assertEquals(listOf("t1", "t2", "t3"), TileCache.toTrim(e + TileCache.Entry("t0", 10, 100), limit = 80).take(3))
        val cut = TileCache.toTrim(e, limit = 80)
        assertEquals(e.sumOf { it.bytes } - cut.size * 10, 60L)
    }

    // ---- Health: GPS failures after the request ----

    private val now = t0 + 60 * min
    private val asked = HealthSignals(locationPermitted = true, gpsRequestedAt = now - 10 * min, lastFixAt = now - 5 * sec)

    private fun gps(s: HealthSignals) = HealthRules.evaluate(s, now).of(HealthArea.GPS)!!

    @Test fun approximateLocationIsNamed() {
        val idle = gps(HealthSignals(locationPermitted = false, approximateOnly = true))
        assertEquals("Solo ubicación aproximada", idle.title)
        assertEquals(HealthFix.APP_SETTINGS, idle.fix)
        assertFalse(idle.level.isProblem)
        val measuring = gps(asked.copy(locationPermitted = false, approximateOnly = true))
        assertEquals(HealthLevel.FAIL, measuring.level)
    }

    @Test fun refusedRequestsAndLocationOffAreFailures() {
        assertEquals(HealthLevel.OK, gps(asked).level)
        val off = gps(asked.copy(locationServicesOn = false))
        assertEquals("Ubicación del teléfono apagada", off.title)
        assertEquals(HealthFix.LOCATION_SETTINGS, off.fix)
        val refused = gps(asked.copy(gpsRequestFailedAt = now - min, gpsRequestFailure = "ajustes de ubicación"))
        assertEquals(HealthLevel.FAIL, refused.level)
        assertEquals(HealthFix.RETRY_SERVICE, refused.fix)
        assertTrue(refused.detail.contains("ajustes de ubicación"))
        // A failure from before the current request is old news
        assertEquals(HealthLevel.OK, gps(asked.copy(gpsRequestFailedAt = now - 20 * min)).level)
    }

    @Test fun noSignalAndBatteryRestrictionAreExplained() {
        val noSignal = gps(asked.copy(lastFixAt = now - 90 * sec, gpsUnavailableSince = now - 80 * sec))
        assertEquals("Sin señal GPS", noSignal.title)
        assertEquals(HealthLevel.WARN, noSignal.level)
        assertEquals(HealthLevel.OK, gps(asked.copy(gpsUnavailableSince = now - 10 * sec, lastFixAt = now - 15 * sec)).level)
        val silent = gps(asked.copy(lastFixAt = now - 5 * min, batteryRestricted = true))
        assertTrue(silent.detail.contains("ahorro de batería"))
        assertEquals(HealthFix.BATTERY_SETTINGS, silent.fix)
        assertEquals(HealthFix.RETRY_SERVICE, gps(asked.copy(gpsBackgroundLimited = true)).fix)
    }

    // ---- Routes: every point is kept ----

    @Test fun closingARouteNoLongerThinsItsStoredPoints() {
        val base = TripRouteSummary("t1", openedAt = t0, updatedAt = t0)
        val pts = (0 until 20).map { i ->
            RoutePoint(i + 1, t0 + i * 5 * sec, 184_800_000 + i * 100, -699_300_000, 50, RoutePhase.PASSENGER)
        }
        val policy = RoutePolicy()
        assertFalse(policy.simplifyStored)
        val all = RouteFinish.finish(base, pts, RouteClose(t0 + 2 * min, RouteCoverage.FULL), 0.0)
        assertEquals(20, all.points.size)
        assertEquals(20, all.summary.pointsKept)
        // The old behaviour is still there for the drawing: a straight line needs two points
        assertEquals(2, RouteFinish.finish(base, pts, RouteClose(t0 + 2 * min, RouteCoverage.FULL), 5.0).points.size)
    }
}
