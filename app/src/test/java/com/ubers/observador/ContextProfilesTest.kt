package com.ubers.observador

import com.ubers.observador.domain.ContextNow
import com.ubers.observador.domain.ContextProfiles
import com.ubers.observador.domain.ProfileContext
import com.ubers.observador.domain.ProfileValues
import com.ubers.observador.domain.SettingsProfile
import com.ubers.observador.domain.SettingsProfileCodec
import com.ubers.observador.domain.SettingsProfiles
import com.ubers.observador.domain.ShiftContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.44.0: profiles by shift, modality and vehicle are only suggested, and stored compatibly. */
class ContextProfilesTest {

    private val values = ProfileValues.DEFAULTS
    private val day = SettingsProfile("d", "Día", values, ProfileContext(ShiftContext.DAY))
    private val night = SettingsProfile("n", "Noche", values.copy(minRdpPerHour = 400.0), ProfileContext(ShiftContext.NIGHT))
    private val flashNight = SettingsProfile("f", "Flash noche", values.copy(minRdpPerKm = 8.0), ProfileContext(ShiftContext.NIGHT, "Moto Flash"))
    private val plain = SettingsProfile("p", "Lluvia", values.copy(maxPickupKm = 3.0))
    private val profiles = listOf(day, night, flashNight, plain)

    private fun now(hour: Int, product: String? = null, vehicle: String? = "bike-1") = ContextNow(hour, product, vehicle)

    @Test fun shiftBoundaries() {
        assertTrue(ShiftContext.DAY.matches(6))
        assertTrue(ShiftContext.DAY.matches(18))
        assertFalse(ShiftContext.DAY.matches(19))
        assertTrue(ShiftContext.NIGHT.matches(19))
        assertTrue(ShiftContext.NIGHT.matches(0))
        assertTrue(ShiftContext.NIGHT.matches(5))
        assertFalse(ShiftContext.NIGHT.matches(6))
        assertTrue((0..23).all { ShiftContext.ANY.matches(it) })
    }

    @Test fun theProfileMadeForTheMomentIsSuggestedNotApplied() {
        val s = ContextProfiles.suggest(profiles, inUseId = "d", now(21))!!
        assertEquals("n", s.profile.id)
        assertEquals("Es de noche", s.reason)
        // The one in use already fits: nothing to suggest
        assertNull(ContextProfiles.suggest(profiles, inUseId = "n", now(21)))
        assertNull(ContextProfiles.suggest(profiles, inUseId = "d", now(10)))
        // A profile without context is never suggested
        assertNull(ContextProfiles.suggest(listOf(plain), inUseId = null, now(10)))
    }

    @Test fun theMostSpecificFittingProfileWins() {
        val s = ContextProfiles.suggest(profiles, inUseId = "p", now(22, "moto flash"))!!
        assertEquals("f", s.profile.id)
        assertEquals("Es de noche y te están llegando ofertas Moto Flash", s.reason)
        // Without recent Moto Flash offers, the plain night profile is the one that fits
        assertEquals("n", ContextProfiles.suggest(profiles, inUseId = "p", now(22, "Moto"))!!.profile.id)
    }

    @Test fun aVehicleProfileOnlyFitsThatVehicle() {
        val bike = SettingsProfile("b", "Honda", values, ProfileContext(vehicleId = "bike-2"))
        assertNull(ContextProfiles.suggest(listOf(bike), null, now(10, vehicle = "bike-1")))
        assertEquals("b", ContextProfiles.suggest(listOf(bike), null, now(10, vehicle = "bike-2"))!!.profile.id)
    }

    @Test fun contextIsStoredWithoutTouchingTheValuesAndOldLinesStillRead() {
        val text = SettingsProfileCodec.encode(profiles + SettingsProfile("x", "Moto | rara", values, ProfileContext(product = "Moto, Flash", vehicleId = "v|1")))
        val back = SettingsProfileCodec.decode(text)
        assertEquals(profiles.size + 1, back.size)
        assertEquals(profiles + SettingsProfile("x", "Moto | rara", values, ProfileContext(product = "Moto, Flash", vehicleId = "v|1")), back)
        // A line written by 1.43.0 or earlier has no context and reads as "always"
        val old = SettingsProfileCodec.encode(listOf(plain))
        assertFalse(old.contains("cshift"))
        assertTrue(SettingsProfileCodec.decode(old).single().context.isEmpty)
        // An unknown shift key falls back to any time instead of dropping the profile
        assertEquals(ShiftContext.ANY, SettingsProfileCodec.decode(old + ",cshift=dawn").single().context.shift)
    }

    @Test fun settingTheContextKeepsTheProfileValues() {
        val r = SettingsProfiles.setContext(profiles, "p", ProfileContext(ShiftContext.NIGHT, "  Moto Flash  ")) as SettingsProfiles.Result.Ok
        assertEquals(plain.values, r.profile.values)
        assertEquals("Moto Flash", r.profile.context.product)
        assertEquals(ShiftContext.NIGHT, r.profile.context.shift)
        val cleared = SettingsProfiles.setContext(r.profiles, "p", ProfileContext(product = "   ")) as SettingsProfiles.Result.Ok
        assertTrue(cleared.profile.context.isEmpty)
        assertTrue(SettingsProfiles.setContext(profiles, "missing", ProfileContext()) is SettingsProfiles.Result.Error)
        // Matching "in use" looks at values only, never at the context
        assertEquals("p", SettingsProfiles.matching(r.profiles, plain.values, "p")!!.id)
    }
}
