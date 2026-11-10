package com.spotbot.android.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.spotbot.android.ui.backtest.BacktestScreen
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.detail.CoinDetailScreen
import com.spotbot.android.ui.panel.PanelScreen
import com.spotbot.android.ui.scan.DecisionLogScreen
import com.spotbot.android.ui.scan.ScanScreen
import com.spotbot.android.ui.settings.SettingsScreen
import com.spotbot.android.ui.trades.EventsScreen
import com.spotbot.android.ui.trades.TradeDetailScreen
import com.spotbot.android.ui.trades.TradesScreen
import kotlinx.serialization.Serializable

@Serializable data object PanelRoute
@Serializable data object ScanRoute
@Serializable data object TradesRoute
@Serializable data object BacktestRoute
@Serializable data object SettingsRoute
@Serializable data class CoinDetailRoute(val symbol: String)
/** Açık pozisyon ya da kapanmış işlem ayrıntısı (pozisyon kimliğiyle). */
@Serializable data class TradeDetailRoute(val id: String)
@Serializable data object DecisionLogRoute
@Serializable data object EventsRoute

private enum class Tab(val route: Any, val label: String, val icon: ImageVector) {
    PANEL(PanelRoute, "Panel", Icons.Filled.Home),
    SCAN(ScanRoute, "Tarama", Icons.Filled.Search),
    TRADES(TradesRoute, "İşlemler", Icons.AutoMirrored.Filled.List),
    BACKTEST(BacktestRoute, "Backtest", Icons.Filled.DateRange),
    SETTINGS(SettingsRoute, "Ayarlar", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScanApp() {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab = Tab.entries.firstOrNull { destination.isOn(it) }
    val subTitle = when {
        destination?.hasRoute(CoinDetailRoute::class) == true -> entry?.toRoute<CoinDetailRoute>()?.symbol?.removeSuffix("USDT")?.let { "$it / USDT" }
        destination?.hasRoute(TradeDetailRoute::class) == true -> "İşlem ayrıntısı"
        destination?.hasRoute(DecisionLogRoute::class) == true -> "Karar günlüğü"
        destination?.hasRoute(EventsRoute::class) == true -> "Olay günlüğü"
        else -> null
    }
    val openSymbol: (String) -> Unit = { navController.navigate(CoinDetailRoute(it)) }
    val openTrade: (String) -> Unit = { navController.navigate(TradeDetailRoute(it)) }
    val openEvents: () -> Unit = { navController.navigate(EventsRoute) }
    val openDecisions: () -> Unit = { navController.navigate(DecisionLogRoute) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        subTitle ?: tab?.let { if (it == Tab.PANEL) "CryptoScan Pro" else it.label } ?: "CryptoScan Pro",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (subTitle != null) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                    }
                },
                actions = {
                    // İşlem modu her ekranda görünür: uygulama yalnız sanal işlem yapar.
                    Tag("PAPER", MaterialTheme.colorScheme.tertiary, Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = destination.isOn(t),
                        onClick = {
                            navController.navigate(t.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = PanelRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable<PanelRoute> { PanelScreen(openSymbol, openTrade, openEvents) }
            composable<ScanRoute> { ScanScreen(openSymbol, openDecisions) }
            composable<TradesRoute> { TradesScreen(openTrade, openEvents) }
            composable<BacktestRoute> { BacktestScreen() }
            composable<SettingsRoute> { SettingsScreen() }
            composable<CoinDetailRoute> { CoinDetailScreen() }
            composable<TradeDetailRoute> { TradeDetailScreen(openSymbol) }
            composable<DecisionLogRoute> { DecisionLogScreen() }
            composable<EventsRoute> { EventsScreen() }
        }
    }
}

private fun NavDestination?.isOn(tab: Tab): Boolean = this?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
