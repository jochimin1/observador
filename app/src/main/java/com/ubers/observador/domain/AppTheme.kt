package com.ubers.observador.domain

/**
 * Fase 8G: the app's looks. Five themes, all built from Observador's own blue (#00639B); the
 * verdict colors (MALO, BUENO, EXCELENTE) never change with the theme. Pure, so the contrast of
 * every pair is tested on the JVM (at least 4.5:1, WCAG AA, as the Android Auto guidance asks).
 */
enum class AppTheme(val storageKey: String, val label: String, val description: String) {
    SYSTEM("SYSTEM", "Sistema", "Claro u oscuro, como tu teléfono"),
    LIGHT("LIGHT", "Claro", "Fondo claro siempre"),
    DARK("DARK", "Oscuro", "Fondo gris oscuro siempre"),
    NIGHT("NIGHT", "Noche", "Negro puro: menos brillo y batería de noche"),
    SUN("SUN", "Sol", "Alto contraste y letras más firmes para el sol");

    companion object {
        fun fromStorage(key: String?): AppTheme = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

/** The palette actually drawn. */
enum class ResolvedTheme(val isDark: Boolean) { LIGHT(false), DARK(true), NIGHT(true), SUN(false) }

/** The theme setting: a choice, plus "Noche" by the hour (from [nightFromHour] to [nightToHour]). */
data class ThemeSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val autoNight: Boolean = false,
    val nightFromHour: Int = 19,
    val nightToHour: Int = 6
)

object ThemeResolver {

    /** True when [hour] (0-23) is inside the night window; the window may cross midnight. */
    fun isNight(hour: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    fun resolve(settings: ThemeSettings, systemDark: Boolean, hour: Int): ResolvedTheme {
        if (settings.autoNight && isNight(hour, settings.nightFromHour, settings.nightToHour)) return ResolvedTheme.NIGHT
        return when (settings.theme) {
            AppTheme.SYSTEM -> if (systemDark) ResolvedTheme.DARK else ResolvedTheme.LIGHT
            AppTheme.LIGHT -> ResolvedTheme.LIGHT
            AppTheme.DARK -> ResolvedTheme.DARK
            AppTheme.NIGHT -> ResolvedTheme.NIGHT
            AppTheme.SUN -> ResolvedTheme.SUN
        }
    }
}

/** Every color role of a Material 3 scheme, as ARGB. */
data class Palette(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val error: Long, val onError: Long, val errorContainer: Long, val onErrorContainer: Long,
    val background: Long, val onBackground: Long, val surface: Long, val onSurface: Long,
    val surfaceVariant: Long, val onSurfaceVariant: Long, val outline: Long, val outlineVariant: Long,
    val surfaceContainerLowest: Long, val surfaceContainerLow: Long, val surfaceContainer: Long,
    val surfaceContainerHigh: Long, val surfaceContainerHighest: Long
) {
    val containers: List<Long>
        get() = listOf(surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
}

/**
 * Light and Dark are the 1.11.0 colors (Material tonal palettes of #00639B, teal accent). Sol is
 * the high-contrast light version; Noche the dark one on pure black, with grays for depth.
 */
object ThemePalettes {

    val LIGHT = Palette(
        0xFF00639B, 0xFFFFFFFF, 0xFFCEE5FF, 0xFF001D33,
        0xFF51606F, 0xFFFFFFFF, 0xFFD5E4F7, 0xFF0E1D2A,
        0xFF006A60, 0xFFFFFFFF, 0xFF9EF2E4, 0xFF00201C,
        0xFFBA1A1A, 0xFFFFFFFF, 0xFFFFDAD6, 0xFF410002,
        0xFFF8F9FF, 0xFF191C20, 0xFFF8F9FF, 0xFF191C20,
        0xFFDEE3EB, 0xFF42474E, 0xFF72787E, 0xFFC2C7CE,
        0xFFFFFFFF, 0xFFF2F3F9, 0xFFECEEF4, 0xFFE6E8EE, 0xFFE1E2E8
    )

    val DARK = Palette(
        0xFF96CCFF, 0xFF003353, 0xFF004A77, 0xFFCEE5FF,
        0xFFB9C8DA, 0xFF233240, 0xFF3A4857, 0xFFD5E4F7,
        0xFF82D5C8, 0xFF003731, 0xFF005048, 0xFF9EF2E4,
        0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
        0xFF111418, 0xFFE1E2E8, 0xFF111418, 0xFFE1E2E8,
        0xFF42474E, 0xFFC2C7CE, 0xFF8C9198, 0xFF42474E,
        0xFF0C0E12, 0xFF191C20, 0xFF1D2024, 0xFF272A2F, 0xFF32353A
    )

    /** High contrast (Material contrast level 1.0): near-black text, strong outlines. */
    val SUN = Palette(
        0xFF002F4C, 0xFFFFFFFF, 0xFF124D75, 0xFFFFFFFF,
        0xFF1F2D3B, 0xFFFFFFFF, 0xFF3C4B59, 0xFFFFFFFF,
        0xFF003732, 0xFFFFFFFF, 0xFF005048, 0xFFFFFFFF,
        0xFF600004, 0xFFFFFFFF, 0xFF98000A, 0xFFFFFFFF,
        0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000,
        0xFFDEE3EB, 0xFF1A1D21, 0xFF272D33, 0xFF444A50,
        0xFFFFFFFF, 0xFFEFF1F6, 0xFFE0E2E8, 0xFFD2D4DA, 0xFFC4C6CC
    )

    /** Pure black (Android Auto guidance): grays mark depth, text at about 96 % white. */
    val NIGHT = Palette(
        0xFF96CCFF, 0xFF003353, 0xFF004A77, 0xFFCEE5FF,
        0xFFB9C8DA, 0xFF233240, 0xFF3A4857, 0xFFD5E4F7,
        0xFF82D5C8, 0xFF003731, 0xFF005048, 0xFF9EF2E4,
        0xFFFFB4AB, 0xFF690005, 0xFF93000A, 0xFFFFDAD6,
        0xFF000000, 0xFFF5F5F5, 0xFF000000, 0xFFF5F5F5,
        0xFF2A2E33, 0xFFC6CBD3, 0xFF8C9198, 0xFF33373C,
        0xFF000000, 0xFF0E1012, 0xFF15181B, 0xFF1E2125, 0xFF282B2F
    )

    fun of(theme: ResolvedTheme): Palette = when (theme) {
        ResolvedTheme.LIGHT -> LIGHT
        ResolvedTheme.DARK -> DARK
        ResolvedTheme.SUN -> SUN
        ResolvedTheme.NIGHT -> NIGHT
    }
}

/** WCAG 2 contrast between two opaque colors. */
object Contrast {
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
