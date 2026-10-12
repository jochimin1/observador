package com.ubers.observador

import com.ubers.observador.domain.ProfileValues
import com.ubers.observador.domain.ScoreWeights
import com.ubers.observador.domain.SettingsProfile
import com.ubers.observador.domain.SettingsProfileCodec
import com.ubers.observador.domain.SettingsProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved settings profiles: exact round trip, safe names, never a value Ajustes would refuse. */
class SettingsProfilesTest {

    private val day = ProfileValues.DEFAULTS
    private val night = day.copy(
        fuelPricePerGallon = 293.6, kmPerGallon = 85.5, minRdpPerKm = 7.25, minRdpPerHour = 350.0,
        maxPickupKm = 4.0, maxPickupMin = 9, goodFrom = 65, excellentFrom = 85,
        weights = ScoreWeights(30, 40, 15, 15), showOverlay = false, overlaySeconds = 20, graceSeconds = 90, mode = "ASSISTED"
    )
    private var ids = 0
    private fun newId() = "p${++ids}"

    private fun ok(r: SettingsProfiles.Result) = r as SettingsProfiles.Result.Ok

    @Test fun roundTripKeepsEveryValueExactly() {
        val profiles = listOf(SettingsProfile("a", "Día", day), SettingsProfile("b", "Noche", night))
        assertEquals(profiles, SettingsProfileCodec.decode(SettingsProfileCodec.encode(profiles)))
    }

    @Test fun anyNameIsSafe() {
        val name = "Lluvia | 2,5 km = ok 🌧 ñ"
        val p = listOf(SettingsProfile("x", name, night))
        assertEquals(name, SettingsProfileCodec.decode(SettingsProfileCodec.encode(p)).single().name)
    }

    @Test fun brokenOrEmptyStorageNeverCrashes() {
        assertTrue(SettingsProfileCodec.decode(null).isEmpty())
        assertTrue(SettingsProfileCodec.decode("").isEmpty())
        val good = SettingsProfileCodec.encode(listOf(SettingsProfile("a", "Día", day)))
        val mixed = "basura\nP1|x|%%%|a=b\n$good\nP9|z|QQ|minh=1"
        assertEquals(listOf("Día"), SettingsProfileCodec.decode(mixed).map { it.name })
    }

    @Test fun missingKeysTakeDefaultsAndUnknownKeysAreIgnored() {
        val line = "P1|old|" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("Viejo".toByteArray()) + "|minh=420.0,futuro=1"
        val p = SettingsProfileCodec.decode(line).single()
        assertEquals(420.0, p.values.minRdpPerHour, 0.0)
        assertEquals(day.copy(minRdpPerHour = 420.0), p.values)
    }

    @Test fun invalidStoredWeightsFallBackToDefaults() {
        val line = SettingsProfileCodec.encode(listOf(SettingsProfile("a", "A", day))).replace("wkm=35", "wkm=90")
        assertEquals(ScoreWeights.DEFAULT, SettingsProfileCodec.decode(line).single().values.weights)
    }

    @Test fun namesAreTrimmedRequiredLimitedAndUnique() {
        val list = ok(SettingsProfiles.create(emptyList(), "  Noche   lluviosa ", night, ::newId)).profiles
        assertEquals("Noche lluviosa", list.single().name)
        assertEquals("Escribe un nombre", SettingsProfiles.nameProblem("   ", list))
        assertEquals("Ya tienes un perfil con ese nombre", SettingsProfiles.nameProblem("noche LLUVIOSA", list))
        assertTrue(SettingsProfiles.nameProblem("x".repeat(31), list)!!.startsWith("Máximo"))
        assertNull(SettingsProfiles.nameProblem("Noche lluviosa", list, exceptId = list.single().id))
    }

    @Test fun atMostTenProfiles() {
        var list = emptyList<SettingsProfile>()
        repeat(10) { list = ok(SettingsProfiles.create(list, "P$it", day, ::newId)).profiles }
        assertTrue(SettingsProfiles.create(list, "Once", day, ::newId) is SettingsProfiles.Result.Error)
        assertEquals(9, SettingsProfiles.delete(list, list.first().id).size)
    }

    @Test fun outOfRangeValuesAreNeverSaved() {
        assertFalse(night.copy(minRdpPerHour = 5.0).isValid)
        assertFalse(night.copy(mode = "SPACE").isValid)
        assertFalse(night.copy(weights = ScoreWeights(50, 50, 50, 50)).isValid)
        assertTrue(SettingsProfiles.create(emptyList(), "Mal", night.copy(maxPickupMin = 0), ::newId) is SettingsProfiles.Result.Error)
        assertTrue(day.isValid && night.isValid)
    }

    @Test fun overwriteKeepsIdAndNameRenameKeepsValues() {
        val created = ok(SettingsProfiles.create(emptyList(), "Día", day, ::newId))
        val updated = ok(SettingsProfiles.overwrite(created.profiles, created.profile.id, night))
        assertEquals(created.profile.copy(values = night), updated.profile)
        val renamed = ok(SettingsProfiles.rename(updated.profiles, created.profile.id, "Noche"))
        assertEquals("Noche", renamed.profile.name)
        assertEquals(night, renamed.profile.values)
        assertTrue(SettingsProfiles.rename(renamed.profiles, "nope", "X") is SettingsProfiles.Result.Error)
    }

    @Test fun matchingFindsTheProfileInUse() {
        val list = listOf(SettingsProfile("a", "Día", day), SettingsProfile("b", "Noche", night), SettingsProfile("c", "Copia", night))
        assertEquals("c", SettingsProfiles.matching(list, night, preferredId = "c")!!.id)
        assertEquals("b", SettingsProfiles.matching(list, night, preferredId = null)!!.id)
        assertNull(SettingsProfiles.matching(list, night.copy(minRdpPerHour = 351.0), "b")) // edited after applying
    }
}
