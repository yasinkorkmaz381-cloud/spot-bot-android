package com.spotbot.android.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.spotbot.android.R
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object MarketsRoute

@Serializable
data object StrategyRoute

@Serializable
data object SettingsRoute

/** Alt gezinme çubuğundaki ana ekranlar. */
enum class TopLevelDestination(
    val route: Any,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    DASHBOARD(DashboardRoute, R.string.nav_dashboard, Icons.Filled.Home),
    MARKETS(MarketsRoute, R.string.nav_markets, Icons.AutoMirrored.Filled.List),
    STRATEGY(StrategyRoute, R.string.nav_strategy, Icons.Filled.Build),
    SETTINGS(SettingsRoute, R.string.nav_settings, Icons.Filled.Settings),
}
