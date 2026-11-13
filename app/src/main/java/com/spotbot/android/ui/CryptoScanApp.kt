package com.spotbot.android.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.spotbot.android.ui.common.Tag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spotbot.android.ui.detail.ArchiveContent
import com.spotbot.android.ui.history.HistoryContent
import com.spotbot.android.ui.detail.DetailContent
import com.spotbot.android.ui.home.HomeContent
import com.spotbot.android.ui.opportunities.OpportunitiesContent
import com.spotbot.android.ui.settings.SettingsContent
import com.spotbot.android.ui.trades.TradesContent
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object OpportunitiesRoute
@Serializable data object TradesRoute
@Serializable data object DetailRoute
@Serializable data object SettingsRoute
@Serializable data object ArchiveRoute
@Serializable data object HistoryRoute

/** Ana kullanım üç bölümdür; ayrıntılar ve ayarlar üst çubuktan açılır. */
private enum class Tab(val route: Any, val label: String, val icon: ImageVector) {
    HOME(HomeRoute, "Ana ekran", Icons.Filled.Home),
    OPPORTUNITIES(OpportunitiesRoute, "Fırsatlar", Icons.Filled.Search),
    TRADES(TradesRoute, "İşlemler", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CryptoScanApp(vm: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab = Tab.entries.firstOrNull { destination.isOn(it.route) }
    val subTitle = when {
        destination.isOn(DetailRoute) -> "Ayrıntılar"
        destination.isOn(SettingsRoute) -> "Ayarlar"
        destination.isOn(ArchiveRoute) -> "Arşiv (eski stratejiler)"
        destination.isOn(HistoryRoute) -> "Geçmiş test"
        else -> null
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keyPresent by vm.keyPresent.collectAsStateWithLifecycle()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    // Saat değerleri (… sn önce) için ekran açıkken her 5 sn yeniden çiz.
    var tick by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); tick++ } }
    val keyStatus by vm.keyStatus.collectAsStateWithLifecycle()
    val keyUnreadable by vm.keyUnreadable.collectAsStateWithLifecycle()
    var pendingExport by remember { mutableStateOf<List<com.spotbot.core.kr.KrTrade>>(emptyList()) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri, pendingExport)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(subTitle ?: if (tab == Tab.HOME || tab == null) "CryptoScan Pro" else tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (subTitle != null) IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                },
                actions = {
                    // İşlem modu her ekranda görünür: uygulama yalnız sanal işlem yapar.
                    Tag("PAPER", MaterialTheme.colorScheme.tertiary, Modifier.padding(end = 4.dp))
                    if (subTitle == null) {
                        IconButton(onClick = { navController.navigate(DetailRoute) }) { Icon(Icons.Filled.Info, contentDescription = "Ayrıntılar") }
                        IconButton(onClick = { navController.navigate(SettingsRoute) }) { Icon(Icons.Filled.Settings, contentDescription = "Ayarlar") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (subTitle == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = destination.isOn(t.route),
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
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = HomeRoute, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable<HomeRoute> {
                HomeContent(
                    state, settings.botMode, keyPresent, vm.now() + 0 * tick, onMode = vm::setMode, onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenHistory = { navController.navigate(HistoryRoute) }, live = live,
                )
            }
            composable<OpportunitiesRoute> { OpportunitiesContent(state, vm.now()) }
            composable<TradesRoute> { TradesContent(state, onOpenArchive = { vm.loadArchive(); navController.navigate(ArchiveRoute) }) }
            composable<DetailRoute> { DetailContent(state, vm.now()) }
            composable<SettingsRoute> {
                SettingsContent(
                    keyPresent, state.events, keyStatus = keyStatus, keyUnreadable = keyUnreadable, onRefreshList = { vm.refreshList() },
                    onClearCache = { vm.clearHistoryCache() }, onSaveKey = { vm.saveKey(it) }, onClearKey = { vm.clearKey() },
                    onAddEvent = { t, title -> vm.addEvent(t, title) }, onRemoveEvent = { vm.removeEvent(it) }, onReset = { vm.reset() },
                    onOpenArchive = { vm.loadArchive(); navController.navigate(ArchiveRoute) },
                )
            }
            composable<ArchiveRoute> { ArchiveContent(archive) }
            composable<HistoryRoute> {
                HistoryContent(
                    history, keyPresent, vm.now(), onStart = vm::startHistory, onCancel = vm::cancelHistory, onReset = vm::resetHistory,
                    onExport = { trades -> pendingExport = trades; exporter.launch("gecmis-test-islemler.csv") },
                )
            }
        }
    }
}

private fun NavDestination?.isOn(route: Any): Boolean = this?.hierarchy?.any { it.hasRoute(route::class) } == true
