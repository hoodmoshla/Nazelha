package com.snapnet.ui.theme

import android.os.Build
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDirection
import com.google.android.material.color.MaterialColors
import com.snapnet.ui.common.LocalFixedColorRoles
import com.snapnet.ui.common.LocalDynamicColorSwitch
import com.snapnet.ui.common.LocalPaletteStyleIndex
import com.snapnet.ui.common.LocalSeedColor
import com.kyant.monet.LocalTonalPalettes
import com.kyant.monet.dynamicColorScheme

fun Color.applyOpacity(enabled: Boolean): Color {
    return if (enabled) this else this.copy(alpha = 0.62f)
}

@Composable
@ReadOnlyComposable
fun Color.harmonizeWith(other: Color) =
    Color(MaterialColors.harmonize(this.toArgb(), other.toArgb()))

@Composable
@ReadOnlyComposable
fun Color.harmonizeWithPrimary(): Color =
    this.harmonizeWith(other = MaterialTheme.colorScheme.primary)

@Composable
fun SealTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isHighContrastModeEnabled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current

    LaunchedEffect(darkTheme) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (darkTheme) {
                view.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    APPEARANCE_LIGHT_STATUS_BARS,
                )
            } else {
                view.windowInsetsController?.setSystemBarsAppearance(
                    APPEARANCE_LIGHT_STATUS_BARS,
                    APPEARANCE_LIGHT_STATUS_BARS,
                )
            }
        }
    }

    val generatedColorScheme = dynamicColorScheme(!darkTheme)
    val useDefaultBrandPalette =
        !LocalDynamicColorSwitch.current &&
            LocalSeedColor.current == DEFAULT_SEED_COLOR &&
            LocalPaletteStyleIndex.current == 0
    val colorScheme =
        (if (useDefaultBrandPalette) generatedColorScheme.withNazelhaBrand(darkTheme) else generatedColorScheme)
            .run {
            if (isHighContrastModeEnabled && darkTheme)
                copy(
                    surface = Color.Black,
                    background = Color.Black,
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerLow = surfaceContainerLowest,
                    surfaceContainer = surfaceContainerLow,
                    surfaceContainerHigh = surfaceContainerLow,
                    surfaceContainerHighest = surfaceContainer,
                )
            else this
        }

    val textStyle =
        LocalTextStyle.current.copy(
            lineBreak = LineBreak.Paragraph,
            textDirection = TextDirection.Content,
        )

    val tonalPalettes = LocalTonalPalettes.current

    CompositionLocalProvider(
        LocalFixedColorRoles provides FixedColorRoles.fromTonalPalettes(tonalPalettes),
        LocalTextStyle provides textStyle,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content,
        )
    }
}

private fun ColorScheme.withNazelhaBrand(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        copy(
            primary = Color(0xFF8BDED6),
            onPrimary = Color(0xFF003734),
            primaryContainer = Color(0xFF00514E),
            onPrimaryContainer = Color(0xFFB3F4ED),
            secondary = Color(0xFFB4CBD6),
            onSecondary = Color(0xFF1B3540),
            tertiary = Color(0xFF81DCD3),
            onTertiary = Color(0xFF003734),
            tertiaryContainer = Color(0xFF00524E),
            onTertiaryContainer = Color(0xFFB5F4ED),
            background = Color(0xFF0B1826),
            onBackground = Color(0xFFE6EFF5),
            surface = Color(0xFF101F2E),
            onSurface = Color(0xFFE6EFF5),
            surfaceVariant = Color(0xFF263A4A),
            onSurfaceVariant = Color(0xFFB4C4CF),
            surfaceContainerLowest = Color(0xFF08131F),
            surfaceContainerLow = Color(0xFF142434),
            surfaceContainer = Color(0xFF192B3B),
            surfaceContainerHigh = Color(0xFF223648),
            surfaceContainerHighest = Color(0xFF2B4052),
            outline = Color(0xFF8297A6),
            outlineVariant = Color(0xFF405565),
        )
    } else {
        copy(
            primary = Color(0xFF12364F),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD0E8F2),
            onPrimaryContainer = Color(0xFF082437),
            secondary = Color(0xFF3F626C),
            onSecondary = Color(0xFFFFFFFF),
            tertiary = Color(0xFF006E70),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFB8F2EC),
            onTertiaryContainer = Color(0xFF002021),
            background = Color(0xFFF5F8FB),
            onBackground = Color(0xFF172633),
            surface = Color(0xFFFBFCFE),
            onSurface = Color(0xFF172633),
            surfaceVariant = Color(0xFFE1EAF0),
            onSurfaceVariant = Color(0xFF465D6B),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF0F4F7),
            surfaceContainer = Color(0xFFEAF0F5),
            surfaceContainerHigh = Color(0xFFE0E9EF),
            surfaceContainerHighest = Color(0xFFD7E3EB),
            outline = Color(0xFF657C8B),
            outlineVariant = Color(0xFFC2CFD8),
        )
    }

@Composable
@Deprecated("Use SealTheme instead", replaceWith = ReplaceWith("SealTheme(content)"))
fun PreviewThemeLight(content: @Composable () -> Unit) {
    SealTheme(darkTheme = false, content = content)
}
