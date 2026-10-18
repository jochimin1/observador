package com.ubers.observador

import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.BackupManifest
import com.ubers.observador.domain.backup.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.31.0: the backup file. The rules of its format run on the JVM; the file work is checked on the phone. */
class BackupFormatTest {

    private val caracas = ZoneId.of("America/Caracas")

    // --- JSON ---

    @Test fun jsonRoundTripKeepsEveryKindOfValue() {
        val original = linkedMapOf<String, Any?>(
            "texto" to "comillas \" barra \\ salto \n tab \t ñandú 🚀 control \u0001",
            "entero" to 42L,
            "grande" to Long.MAX_VALUE,
            "negativo" to -7L,
            "decimal" to 0.1,
            "exp" to 1.5e10,
            "si" to true,
            "no" to false,
            "nada" to null,
            "lista" to listOf(1L, "dos", listOf(3L)),
            "mapa" to linkedMapOf<String, Any?>("a" to 1L, "b" to emptyList<Any?>(), "c" to emptyMap<String, Any?>())
        )
        assertEquals(original, MiniJson.parse(MiniJson.write(original)))
    }

    @Test fun jsonRejectsWhatIsNotJson() {
        listOf(
            "", "{", "[1,2", "{\"a\":}", "{\"a\" 1}", "{'a':1}", "[1,]", "{\"a\":1,}", "nul", "01x", "\"sin cerrar",
            "{\"a\":1}{\"b\":2}", "{\"a\":1,\"a\":2}", "\"\\x\"", "\"\\u12\"", "1e999", "99999999999999999999"
        ).forEach { assertNull(MiniJson.parseOrNull(it)) }
    }

    @Test fun jsonNestingIsCapped() {
        assertNull(MiniJson.parseOrNull("[".repeat(500) + "]".repeat(500)))
        assertNotNull(MiniJson.parseOrNull("[".repeat(20) + "]".repeat(20)))
    }

    @Test fun jsonWriterRefusesWhatItCannotWrite() {
        assertTrue(runCatching { MiniJson.write(Double.NaN) }.isFailure)
        assertTrue(runCatching { MiniJson.write(Any()) }.isFailure)
    }

    // --- file name ---

    @Test fun theFileNameCarriesTheLocalDate() {
        val at = ZonedDateTime.of(2026, 10, 18, 15, 30, 0, 0, caracas).toInstant().toEpochMilli()
        assertEquals("observador-respaldo-2026-10-18-1530.zip", BackupFormat.fileName(at, caracas))
        val late = ZonedDateTime.of(2026, 1, 2, 3, 4, 0, 0, caracas).toInstant().toEpochMilli()
        assertEquals("observador-respaldo-2026-01-02-0304.zip", BackupFormat.fileName(late, caracas))
    }

    // --- settings ---

    @Test fun theMomentsStateDoesNotTravel() {
        val all = mapOf<String, Any?>(
            "cost_per_km" to 4.5, "daily_goal_minor" to 235000L, "app_theme" to "VIOLET",
            "distance_manual" to true, "closed_by_user" to true, "observer_enabled" to false, "onboarding_done" to true,
            "last_backup_at" to 1L, "export_history" to "x", "goal_notified_day" to "2026-10-18",
            "goal_notified_week" to "2026-W42", "weekly_report_notified" to "2026-W41",
            "maintenance_notified_aceite" to "L1", "maintenance_notified_cadena" to "L2",
            "maintenance_interval_km_aceite" to 3000L
        )
        assertEquals(
            setOf("cost_per_km", "daily_goal_minor", "app_theme", "maintenance_interval_km_aceite"),
            BackupFormat.selectSettings(all).keys
        )
    }

    @Test fun unsupportedValuesAreLeftOut() {
        val picked = BackupFormat.selectSettings(mapOf("bytes" to ByteArray(2), "nulo" to null, "ok" to 1, "set" to setOf("a"), "setRaro" to setOf(1)))
        assertEquals(setOf("ok", "set"), picked.keys)
    }

    @Test fun everyKindOfSettingComesBackAsItWas() {
        val values = mapOf<String, Any>(
            "b" to true, "i" to -5, "l" to Long.MAX_VALUE, "f" to 1.25f, "d" to 4.1, "s" to "Ñ \"x\"", "set" to setOf("z", "a")
        )
        val back = BackupFormat.decodeSettings(BackupFormat.encodeSettings(values))!!
        assertTrue(back.rejected.isEmpty())
        assertEquals(values, back.values)
        // The types survive too, not just the numbers
        assertTrue(back.values["i"] is Int)
        assertTrue(back.values["l"] is Long)
        assertTrue(back.values["f"] is Float)
        assertTrue(back.values["d"] is Double)
        assertTrue(back.values["set"] is Set<*>)
    }

    @Test fun aDamagedSettingIsRejectedAndTheRestIsKept() {
        val text = """{"ok":{"t":"int","v":3},"mal":{"t":"int","v":"tres"},"raro":{"t":"color","v":1},"sin":5,"grande":{"t":"int","v":99999999999}}"""
        val d = BackupFormat.decodeSettings(text)!!
        assertEquals(mapOf<String, Any>("ok" to 3), d.values)
        assertEquals(setOf("mal", "raro", "sin", "grande"), d.rejected.toSet())
        assertNull(BackupFormat.decodeSettings("[1,2]"))
        assertNull(BackupFormat.decodeSettings("no es json"))
    }

    // --- control sheet ---

    private fun manifest() = BackupManifest(
        format = 1, appVersion = "1.31.0", appVersionCode = 36, dbVersion = 14, createdAtMs = 1_792_000_000_000L,
        rows = mapOf("detected_offers" to 1284, "trips" to 96, "expense_entries" to 10, "fuel_entries" to 2),
        sha256 = mapOf(BackupFormat.ENTRY_DB to "aa", BackupFormat.ENTRY_SETTINGS to "bb")
    )

    @Test fun theSheetComesBackAsItWasWritten() {
        val m = manifest()
        assertEquals(m, BackupFormat.decodeManifest(BackupFormat.encodeManifest(m)))
    }

    @Test fun aSheetThatIsNotOurs_OrIsIncomplete_IsRefused() {
        val good = BackupFormat.encodeManifest(manifest())
        assertNotNull(BackupFormat.decodeManifest(good))
        assertNull(BackupFormat.decodeManifest(good.replace("\"observador\"", "\"otra-app\"")))
        assertNull(BackupFormat.decodeManifest(good.replace("\"baseDeDatos\":14,", "")))
        assertNull(BackupFormat.decodeManifest(good.replace("\"baseDeDatos\":14", "\"baseDeDatos\":\"14\"")))
        assertNull(BackupFormat.decodeManifest(good.replace("\"trips\":96", "\"trips\":-1")))
        assertNull(BackupFormat.decodeManifest("{}"))
        assertNull(BackupFormat.decodeManifest("basura"))
    }

    @Test fun aChangedOrMissingFileIsReportedAsDamaged() {
        val m = manifest()
        assertTrue(BackupFormat.mismatches(m, mapOf(BackupFormat.ENTRY_DB to "AA", BackupFormat.ENTRY_SETTINGS to "bb")).isEmpty())
        assertEquals(listOf(BackupFormat.ENTRY_SETTINGS), BackupFormat.mismatches(m, mapOf(BackupFormat.ENTRY_DB to "aa", BackupFormat.ENTRY_SETTINGS to "cc")))
        assertEquals(listOf(BackupFormat.ENTRY_DB), BackupFormat.mismatches(m, mapOf(BackupFormat.ENTRY_SETTINGS to "bb")))
        // A sheet that does not list a file cannot vouch for it
        assertEquals(listOf(BackupFormat.ENTRY_SETTINGS), BackupFormat.mismatches(m.copy(sha256 = mapOf(BackupFormat.ENTRY_DB to "aa")), mapOf(BackupFormat.ENTRY_DB to "aa", BackupFormat.ENTRY_SETTINGS to "bb")))
    }

    @Test fun sha256MatchesTheKnownValues() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BackupFormat.sha256Hex("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", BackupFormat.sha256Hex(ByteArray(0)))
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(BackupFormat.sha256Hex(big), BackupFormat.sha256Hex(big.inputStream()))
    }

    // --- the line shown to the driver ---

    @Test fun theSummaryLineReadsLikeTheAppDoes() {
        val m = manifest()
        assertEquals("1,284 ofertas, 96 viajes, 12 gastos", BackupFormat.contentText(m.rows))
        assertEquals("1 oferta, 1 viaje, 1 gasto", BackupFormat.contentText(mapOf("detected_offers" to 1, "trips" to 1, "fuel_entries" to 1)))
        assertEquals("0 ofertas, 0 viajes, 0 gastos", BackupFormat.contentText(emptyMap()))
        assertEquals("Respaldo creado: 1,284 ofertas, 96 viajes, 12 gastos · 3.1 MB.", BackupFormat.createdText(m, 3_250_000))
        assertEquals("512 B", BackupFormat.sizeText(512))
        assertEquals("48 KB", BackupFormat.sizeText(48 * 1024L))
        assertFalse(BackupFormat.sizeText(1024 * 1024L).contains("KB"))
    }
}
