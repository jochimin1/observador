package com.ubers.observador

import com.ubers.observador.domain.backup.BackupFormat
import com.ubers.observador.domain.backup.DecodedSettings
import com.ubers.observador.domain.backup.RestoreCompat
import com.ubers.observador.domain.backup.RestoreRules
import com.ubers.observador.domain.backup.RestoreStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.32.0: the rules of a restore (compatibility, settings validation, order, texts). Files and Room are checked on the phone. */
class RestoreRulesTest {

    @Test fun equalVersionIsAcceptedAsIs() {
        assertEquals(RestoreCompat.SAME, RestoreRules.compat(14, 14))
        assertNull(RestoreRules.refusal(14, 14))
    }

    @Test fun olderVersionIsAcceptedAndMigrates() {
        assertEquals(RestoreCompat.OLDER, RestoreRules.compat(9, 14))
        assertNull(RestoreRules.refusal(9, 14))
    }

    @Test fun newerVersionIsRefusedWithTheUpdateMessage() {
        assertEquals(RestoreCompat.NEWER, RestoreRules.compat(15, 14))
        assertEquals("Actualiza Observador antes de restaurar este respaldo.", RestoreRules.refusal(15, 14))
    }

    @Test fun versionBelowOneIsDamaged() {
        assertNotNull(RestoreRules.refusal(0, 14))
    }

    @Test fun validSettingsPassWithTheirOwnTypes() {
        val r = RestoreRules.validateSettings(
            DecodedSettings(
                mapOf(
                    "cost_per_km" to 4.5, "max_pickup_min" to 12, "show_overlay" to false,
                    "daily_goal_minor" to 250_000L, "app_theme" to "VIOLET", "maintenance_interval_km_aceite" to 3000L
                ),
                emptyList()
            )
        )
        assertEquals(6, r.values.size)
        assertTrue(r.values["cost_per_km"] is Double)
        assertTrue(r.values["max_pickup_min"] is Int)
        assertTrue(r.values["daily_goal_minor"] is Long)
        assertTrue(r.defaulted.isEmpty())
    }

    @Test fun invalidSettingsGoBackToDefaultAndAreListed() {
        val r = RestoreRules.validateSettings(
            DecodedSettings(
                mapOf(
                    "cost_per_km" to -3.0,            // below the minimum
                    "max_pickup_min" to 9999,         // above the maximum
                    "app_theme" to "ROSA",            // not a theme
                    "show_overlay" to "si",           // wrong type
                    "min_rdp_per_km" to Double.NaN,   // not a number
                    "daily_goal_minor" to 5L,         // fine
                    "min_rdp_per_hour" to 600.0       // fine
                ),
                listOf("fuel_price_per_gallon")       // unreadable in the file
            )
        )
        assertEquals(setOf("daily_goal_minor", "min_rdp_per_hour"), r.values.keys)
        assertEquals(
            listOf("app_theme", "cost_per_km", "fuel_price_per_gallon", "max_pickup_min", "min_rdp_per_km", "show_overlay"),
            r.defaulted
        )
    }

    @Test fun volatileAndUnknownSettingsAreIgnoredNotWritten() {
        val r = RestoreRules.validateSettings(
            DecodedSettings(mapOf("observer_enabled" to false, "onboarding_done" to true, "ajuste_del_futuro" to 1L, "cost_per_km" to 5.0), emptyList())
        )
        assertEquals(setOf("cost_per_km"), r.values.keys)
        assertEquals(listOf("ajuste_del_futuro", "observer_enabled", "onboarding_done"), r.ignored)
        assertTrue(r.defaulted.isEmpty())
    }

    @Test fun pendingNoticeNeverTravels() {
        assertTrue(BackupFormat.isVolatile("pending_notice"))
    }

    @Test fun maintenanceIntervalIsLimited() {
        val r = RestoreRules.validateSettings(DecodedSettings(mapOf("maintenance_interval_km_llantas" to 500_000L), emptyList()))
        assertTrue(r.values.isEmpty())
        assertEquals(listOf("maintenance_interval_km_llantas"), r.defaulted)
    }

    @Test fun profilesMustBeReadable() {
        val bad = RestoreRules.validateSettings(DecodedSettings(mapOf("settings_profiles" to "esto no es un perfil"), emptyList()))
        assertEquals(listOf("settings_profiles"), bad.defaulted)
        val empty = RestoreRules.validateSettings(DecodedSettings(mapOf("settings_profiles" to ""), emptyList()))
        assertTrue(empty.defaulted.isEmpty())
    }

    @Test fun defaultedTextNamesWhatChanged() {
        assertNull(RestoreRules.defaultedText(emptyList()))
        val one = RestoreRules.defaultedText(listOf("cost_per_km"))!!
        assertTrue(one.startsWith("1 ajuste tenía un valor inválido"))
        assertTrue(one.contains("costo por km"))
        assertTrue(RestoreRules.defaultedText(listOf("cost_per_km", "app_theme"))!!.startsWith("2 ajustes tenían"))
    }

    @Test fun comparisonShowsBothSidesAndTheWarning() {
        val t = RestoreRules.comparisonText(
            "12/10/2026 8:30 p. m.",
            mapOf("detected_offers" to 1284, "trips" to 96, "expense_entries" to 10, "fuel_entries" to 2),
            mapOf("detected_offers" to 1, "trips" to 0, "expense_entries" to 0)
        )
        assertTrue(t.contains("Respaldo del 12/10/2026 8:30 p. m.: 1,284 ofertas, 96 viajes, 12 gastos."))
        assertTrue(t.contains("Ahora tienes: 1 oferta, 0 viajes, 0 gastos."))
        assertTrue(t.endsWith("Lo que tienes ahora se reemplaza."))
    }

    @Test fun restoredTextMentionsMigrationAndDefaults() {
        val t = RestoreRules.restoredText(mapOf("detected_offers" to 2, "trips" to 1), true, listOf("app_theme"))
        assertTrue(t.startsWith("Respaldo restaurado: 2 ofertas, 1 viaje, 0 gastos."))
        assertTrue(t.contains("se actualizó"))
        assertTrue(t.contains("tema"))
    }

    @Test fun stepsRunInTheSafeOrderAndRollbackStartsWhenDataIsReplaced() {
        val order = RestoreStep.entries
        assertTrue(order.indexOf(RestoreStep.VERIFY_FILE) < order.indexOf(RestoreStep.SAFETY_BACKUP))
        assertTrue(order.indexOf(RestoreStep.SAFETY_BACKUP) < order.indexOf(RestoreStep.PAUSE_READING))
        assertTrue(order.indexOf(RestoreStep.DRAIN_WRITES) < order.indexOf(RestoreStep.CLOSE_DATABASE))
        assertTrue(order.indexOf(RestoreStep.CLOSE_DATABASE) < order.indexOf(RestoreStep.REPLACE_DATABASE))
        assertFalse(RestoreStep.SAFETY_BACKUP.needsRollback)
        assertFalse(RestoreStep.CLOSE_DATABASE.needsRollback)
        assertTrue(RestoreStep.REPLACE_DATABASE.needsRollback)
        assertTrue(RestoreStep.REPLACE_SETTINGS.needsRollback)
        assertFalse(RestoreStep.RESTART.needsRollback)
    }
}
