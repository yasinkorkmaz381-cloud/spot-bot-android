package com.spotbot.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF475569),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFCBD5E1),
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFE2E8F0),
)

/** Yükseliş/düşüş renkleri; her iki temada da okunabilir kontrast için ayrı tanımlıdır. */
@Immutable
data class TradingColors(val gain: Color, val loss: Color)

private val LightTradingColors = TradingColors(gain = Color(0xFF15803D), loss = Color(0xFFB91C1C))
private val DarkTradingColors = TradingColors(gain = Color(0xFF4ADE80), loss = Color(0xFFF87171))

private val LocalTradingColors = staticCompositionLocalOf { LightTradingColors }

@Composable
fun SpotBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTradingColors provides if (darkTheme) DarkTradingColors else LightTradingColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
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
