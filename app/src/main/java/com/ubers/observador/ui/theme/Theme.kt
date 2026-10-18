package com.ubers.observador.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ubers.observador.R
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

/** Lexend (SIL Open Font License, docs/licencias), one variable file for every weight. */
private val Lexend = FontFamily(
    Font(R.font.lexend, FontWeight.Light),
    Font(R.font.lexend, FontWeight.Normal),
    Font(R.font.lexend, FontWeight.Medium),
    Font(R.font.lexend, FontWeight.SemiBold),
    Font(R.font.lexend, FontWeight.Bold)
)

private fun TextStyle.lexend(): TextStyle = copy(fontFamily = Lexend)

/** Violeta: every style in Lexend, same sizes. */
private val VioletTypography = Typography.run {
    copy(
        displayLarge = displayLarge.lexend(), displayMedium = displayMedium.lexend(), displaySmall = displaySmall.lexend(),
        headlineLarge = headlineLarge.lexend(), headlineMedium = headlineMedium.lexend(), headlineSmall = headlineSmall.lexend(),
        titleLarge = titleLarge.lexend(), titleMedium = titleMedium.lexend(), titleSmall = titleSmall.lexend(),
        bodyLarge = bodyLarge.lexend(), bodyMedium = bodyMedium.lexend(), bodySmall = bodySmall.lexend(),
        labelLarge = labelLarge.lexend(), labelMedium = labelMedium.lexend(), labelSmall = labelSmall.lexend()
    )
}

/** Violeta: rounder corners, like the white cards of the chosen design. */
private val VioletShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** True while the theme draws a pastel background behind the screens (Violeta). */
val LocalPastelBackground = staticCompositionLocalOf { false }

/**
 * The color each screen's Scaffold fills with: its theme's background, or transparent over the
 * pastel background of Violeta, so the soft color shows through.
 */
@Composable
fun screenBackground(): Color =
    if (LocalPastelBackground.current) Color.Transparent else MaterialTheme.colorScheme.background

/** Soft green, yellow, lilac and blue patches, as in the design (drawn once, no animation). */
private fun Modifier.pastelBackground(base: Color): Modifier = background(base).drawBehind {
    fun patch(color: Long, x: Float, y: Float, r: Float) = drawCircle(
        Brush.radialGradient(listOf(Color(color), Color(color).copy(alpha = 0f)), center = Offset(size.width * x, size.height * y), radius = size.minDimension * r),
        radius = size.minDimension * r,
        center = Offset(size.width * x, size.height * y)
    )
    patch(0xCCDDF6E8, 0.05f, 0.18f, 0.55f) // mint, top left
    patch(0xCCFFF3C9, 0.95f, 0.08f, 0.50f) // yellow, top right
    patch(0xB3E3DAFF, 0.90f, 0.55f, 0.65f) // lilac, right
    patch(0x99DCEBFF, 0.10f, 0.85f, 0.60f) // blue, bottom left
    patch(0x80FFF1D6, 0.85f, 0.98f, 0.45f) // warm, bottom right
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
    val scheme = schemes.getValue(theme)
    MaterialTheme(
        colorScheme = scheme,
        typography = when (theme) {
            ResolvedTheme.SUN -> SunTypography
            ResolvedTheme.VIOLET -> VioletTypography
            else -> Typography
        },
        shapes = if (theme.pastel) VioletShapes else Shapes()
    ) {
        CompositionLocalProvider(LocalPastelBackground provides theme.pastel) {
            if (theme.pastel) {
                Box(Modifier.fillMaxSize().pastelBackground(scheme.background)) { content() }
            } else {
                content()
            }
        }
    }
}

/** Top bars: transparent over Violeta's pastel background, the theme's own elsewhere. */
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun topBarColors(): androidx.compose.material3.TopAppBarColors =
    if (LocalPastelBackground.current) {
        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    } else {
        androidx.compose.material3.TopAppBarDefaults.topAppBarColors()
    }

/** Text fields: white, borderless and rounded in Violeta (like the cards); the theme's own elsewhere. */
@androidx.compose.runtime.Composable
fun appFieldColors(): androidx.compose.material3.TextFieldColors =
    if (LocalPastelBackground.current) {
        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary
        )
    } else {
        androidx.compose.material3.OutlinedTextFieldDefaults.colors()
    }

@androidx.compose.runtime.Composable
fun appFieldShape(): androidx.compose.ui.graphics.Shape =
    if (LocalPastelBackground.current) RoundedCornerShape(16.dp) else androidx.compose.material3.OutlinedTextFieldDefaults.shape
