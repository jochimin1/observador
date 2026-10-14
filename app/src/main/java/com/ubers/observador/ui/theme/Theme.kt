package com.ubers.observador.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.ubers.observador.domain.Palette
import com.ubers.observador.domain.ResolvedTheme
import com.ubers.observador.domain.ThemePalettes

private fun Palette.toScheme(dark: Boolean): ColorScheme {
    fun c(v: Long) = Color(v)
    return if (dark) {
        darkColorScheme(
            primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
            secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
            error = c(error), onError = c(onError), errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
            background = c(background), onBackground = c(onBackground), surface = c(surface), onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant), outline = c(outline), outlineVariant = c(outlineVariant),
            surfaceContainerLowest = c(surfaceContainerLowest), surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest)
        )
    } else {
        lightColorScheme(
            primary = c(primary), onPrimary = c(onPrimary), primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
            secondary = c(secondary), onSecondary = c(onSecondary), secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
            tertiary = c(tertiary), onTertiary = c(onTertiary), tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
            error = c(error), onError = c(onError), errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
            background = c(background), onBackground = c(onBackground), surface = c(surface), onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant), outline = c(outline), outlineVariant = c(outlineVariant),
            surfaceContainerLowest = c(surfaceContainerLowest), surfaceContainerLow = c(surfaceContainerLow),
            surfaceContainer = c(surfaceContainer), surfaceContainerHigh = c(surfaceContainerHigh),
            surfaceContainerHighest = c(surfaceContainerHighest)
        )
    }
}

private val schemes: Map<ResolvedTheme, ColorScheme> = ResolvedTheme.entries.associateWith { ThemePalettes.of(it).toScheme(it.isDark) }

/** Sol: the same sizes, one step heavier, so thin text survives the glare. */
private fun TextStyle.firmer(): TextStyle = copy(
    fontWeight = when (fontWeight ?: FontWeight.Normal) {
        FontWeight.Normal -> FontWeight.Medium
        FontWeight.Medium -> FontWeight.SemiBold
        FontWeight.SemiBold -> FontWeight.Bold
        else -> fontWeight
    }
)

private val SunTypography = Typography.run {
    copy(
        bodyLarge = bodyLarge.firmer(), bodyMedium = bodyMedium.firmer(), bodySmall = bodySmall.firmer(),
        labelLarge = labelLarge.firmer(), labelMedium = labelMedium.firmer(), labelSmall = labelSmall.firmer(),
        titleSmall = titleSmall.firmer(), titleMedium = titleMedium.firmer()
    )
}

/**
 * Observador's own colors (Fase 8G: Sistema, Claro, Oscuro, Noche and Sol, see
 * [com.ubers.observador.domain.ThemeResolver]). Wallpaper colors (dynamic color) are never used:
 * the app looks the same on every phone, and a wallpaper must never tint a verdict.
 */
@Composable
fun ObservadorTheme(
    theme: ResolvedTheme,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = schemes.getValue(theme),
        typography = if (theme == ResolvedTheme.SUN) SunTypography else Typography,
        content = content
    )
}
