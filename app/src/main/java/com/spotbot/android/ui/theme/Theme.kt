package com.spotbot.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Koyu tema (varsayılan): koyu lacivert-gri zemin, bir ton açık kartlar, kenarlıksız yüzeyler. Metin kontrastı
 * WCAG AA'yı karşılayacak şekilde seçildi (ana metin ~14:1, ikincil metin ~7:1 kart zemininde).
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DD6C3),
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF123B37),
    onPrimaryContainer = Color(0xFFB9F3EA),
    secondary = Color(0xFFAAB4C3),
    onSecondary = Color(0xFF151A21),
    secondaryContainer = Color(0xFF222A35),
    onSecondaryContainer = Color(0xFFD5DCE6),
    tertiary = Color(0xFFF2B84B),
    onTertiary = Color(0xFF2A1C00),
    tertiaryContainer = Color(0xFF3A2C0E),
    onTertiaryContainer = Color(0xFFFFE2A8),
    error = Color(0xFFF2616D),
    onError = Color(0xFF2D0A0E),
    errorContainer = Color(0xFF3D1519),
    onErrorContainer = Color(0xFFFFD9DC),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1C222B),
    onSurfaceVariant = Color(0xFF9BA5B4),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF12171E),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C222B),
    surfaceContainerHighest = Color(0xFF242B35),
    outline = Color(0xFF3A4350),
    outlineVariant = Color(0xFF262D37),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF475569),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    tertiary = Color(0xFFB45309),
)

/** Yükseliş/düşüş renkleri: yeşil yalnız kâr ve olumlu durum, kırmızı yalnız zarar ve risk uyarısı. */
@Immutable
data class TradingColors(val gain: Color, val loss: Color)

private val LightTradingColors = TradingColors(gain = Color(0xFF15803D), loss = Color(0xFFB91C1C))
private val DarkTradingColors = TradingColors(gain = Color(0xFF2FCB89), loss = Color(0xFFF2616D))

private val LocalTradingColors = staticCompositionLocalOf { DarkTradingColors }

/** Rakamlar alt alta hizalansın diye eşit genişlikli (tabular) rakamlar. */
private fun TextStyle.numeric() = copy(fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold).numeric(),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold).numeric(),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold).numeric(),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold).numeric(),
        bodyLarge = t.bodyLarge.numeric(),
        bodyMedium = t.bodyMedium.numeric(),
        bodySmall = t.bodySmall.numeric(),
        labelLarge = t.labelLarge.numeric(),
        labelMedium = t.labelMedium.numeric(),
        labelSmall = t.labelSmall.numeric(),
    )
}

/** Uygulama koyu temayla açılır (tasarım tercihi); açık tema yalnız testler ve ileride seçenek için tutulur. */
@Composable
fun SpotBotTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTradingColors provides if (darkTheme) DarkTradingColors else LightTradingColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

object SpotBotTheme {
    val tradingColors: TradingColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTradingColors.current
}

/** Pozitif değer için yükseliş, negatif için düşüş, sıfır için nötr renk. */
@Composable
@ReadOnlyComposable
fun gainLossColor(signum: Int): Color = when {
    signum > 0 -> SpotBotTheme.tradingColors.gain
    signum < 0 -> SpotBotTheme.tradingColors.loss
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
