package com.ubers.observador

import com.ubers.observador.domain.AppTheme
import com.ubers.observador.domain.Contrast
import com.ubers.observador.domain.OfferQuality
import com.ubers.observador.domain.Palette
import com.ubers.observador.domain.ResolvedTheme
import com.ubers.observador.domain.ThemePalettes
import com.ubers.observador.domain.ThemeResolver
import com.ubers.observador.domain.ThemeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 8G: which theme is drawn, and every text color readable (4.5:1) in every theme. */
class ThemeTest {

    private val min = 4.5

    private fun pairs(p: Palette): List<Pair<String, Pair<Long, Long>>> {
        val fixed = listOf(
            "primary" to (p.onPrimary to p.primary),
            "primaryContainer" to (p.onPrimaryContainer to p.primaryContainer),
            "secondary" to (p.onSecondary to p.secondary),
            "secondaryContainer" to (p.onSecondaryContainer to p.secondaryContainer),
            "tertiary" to (p.onTertiary to p.tertiary),
            "tertiaryContainer" to (p.onTertiaryContainer to p.tertiaryContainer),
            "error" to (p.onError to p.error),
            "errorContainer" to (p.onErrorContainer to p.errorContainer),
            "background" to (p.onBackground to p.background)
        )
        // Text roles used on every surface of the app (cards, tiles, lists)
        val onSurfaces = p.containers.flatMapIndexed { i, bg ->
            listOf(
                "onSurface/$i" to (p.onSurface to bg),
                "onSurfaceVariant/$i" to (p.onSurfaceVariant to bg)
            )
        }
        // Accent colors used as text on the cards
        val accents = listOf(p.surface, p.surfaceContainerLow, p.surfaceContainerHighest).flatMapIndexed { i, bg ->
            listOf("primaryText/$i" to (p.primary to bg), "tertiaryText/$i" to (p.tertiary to bg), "errorText/$i" to (p.error to bg), "secondaryText/$i" to (p.secondary to bg))
        }
        return fixed + onSurfaces + accents
    }

    @Test fun everyThemeIsReadable() {
        mapOf("Claro" to ThemePalettes.LIGHT, "Oscuro" to ThemePalettes.DARK, "Sol" to ThemePalettes.SUN, "Noche" to ThemePalettes.NIGHT, "Violeta" to ThemePalettes.VIOLET).forEach { (name, p) ->
            pairs(p).forEach { (role, c) ->
                val r = Contrast.ratio(c.first, c.second)
                assertTrue("$name $role %.2f".format(r), r >= min)
            }
        }
    }

    @Test fun sunIsHigherContrastAndNightIsPureBlack() {
        assertTrue(Contrast.ratio(ThemePalettes.SUN.onSurface, ThemePalettes.SUN.surface) >= 20.0)
        assertTrue(Contrast.ratio(ThemePalettes.SUN.onSurfaceVariant, ThemePalettes.SUN.surfaceContainerHighest) >
            Contrast.ratio(ThemePalettes.LIGHT.onSurfaceVariant, ThemePalettes.LIGHT.surfaceContainerHighest))
        assertEquals(0xFF000000, ThemePalettes.NIGHT.background)
        assertEquals(0xFF000000, ThemePalettes.NIGHT.surface)
    }

    @Test fun verdictColorsAreFixedAndReadable() {
        OfferQuality.entries.forEach { q ->
            val r = Contrast.ratio(q.backgroundArgb.toLong() and 0xFFFFFFFFL, q.foregroundArgb.toLong() and 0xFFFFFFFFL)
            assertTrue("${q.label} %.2f".format(r), r >= min)
        }
    }

    @Test fun contrastFormula() {
        assertEquals(21.0, Contrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF777777, 0xFF777777), 0.0001)
    }

    @Test fun resolveTheChoice() {
        val s = ThemeSettings()
        assertEquals(ResolvedTheme.DARK, ThemeResolver.resolve(s, systemDark = true, hour = 12))
        assertEquals(ResolvedTheme.LIGHT, ThemeResolver.resolve(s, systemDark = false, hour = 12))
        assertEquals(ResolvedTheme.SUN, ThemeResolver.resolve(s.copy(theme = AppTheme.SUN), systemDark = true, hour = 12))
        assertEquals(ResolvedTheme.NIGHT, ThemeResolver.resolve(s.copy(theme = AppTheme.NIGHT), systemDark = false, hour = 12))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStorage("nonsense"))
        assertEquals(ResolvedTheme.VIOLET, ThemeResolver.resolve(s.copy(theme = AppTheme.VIOLET), systemDark = true, hour = 12))
        assertEquals(AppTheme.VIOLET, AppTheme.fromStorage("VIOLET"))
        assertTrue(ResolvedTheme.VIOLET.pastel && !ResolvedTheme.VIOLET.isDark && !ResolvedTheme.LIGHT.pastel)
    }

    @Test fun nightByTheHourCrossesMidnight() {
        val s = ThemeSettings(theme = AppTheme.SUN, autoNight = true, nightFromHour = 19, nightToHour = 6)
        assertEquals(ResolvedTheme.SUN, ThemeResolver.resolve(s, false, 18))
        assertEquals(ResolvedTheme.NIGHT, ThemeResolver.resolve(s, false, 19))
        assertEquals(ResolvedTheme.NIGHT, ThemeResolver.resolve(s, false, 0))
        assertEquals(ResolvedTheme.NIGHT, ThemeResolver.resolve(s, false, 5))
        assertEquals(ResolvedTheme.SUN, ThemeResolver.resolve(s, false, 6))
        assertTrue(ThemeResolver.isNight(14, 13, 15))
        assertFalse(ThemeResolver.isNight(15, 13, 15))
        assertFalse(ThemeResolver.isNight(3, 7, 7))
    }
}
