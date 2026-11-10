package com.spotbot.android

import android.app.Application
import com.spotbot.android.bot.BacktestRunner
import com.spotbot.android.data.FileDecisionLogStore
import com.spotbot.android.data.FileStateStore
import com.spotbot.android.data.SettingsRepository
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SpotBotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Uygulama genelinde tek örnek bağımlılıklar. Motor uygulama ömrü boyunca yaşar; servis onu başlatıp durdurur. */
class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = MexcRestClient()
    val settings = SettingsRepository(app.filesDir)
    val engine = TradingEngine(
        api = api,
        webSocket = MexcWebSocketClient(scope),
        scope = scope,
        store = FileStateStore(app.filesDir),
        config = settings.settings.value.engine,
        decisionLog = FileDecisionLogStore(java.io.File(app.filesDir, "decisions")),
        runLocation = "Bu telefon (Android ön plan servisi)",
        shadowStore = com.spotbot.android.data.FileShadowStore(app.filesDir),
    )
    val backtest = BacktestRunner(api, scope, app.cacheDir)
    /** V4 geniş evren taraması: MEXC'deki bütün USDT pariteleri, S1 kurallarıyla iki aşamalı. */
    val wide = com.spotbot.core.scanner.WideScanner(api, com.spotbot.android.data.FileWideScanStore(app.filesDir))
    /** V3 araştırmasında "umut verici" çıkan S1'in ayrı sanal portföydeki ileri testi (yalnız kâğıt). */
    val forward = com.spotbot.core.research.ForwardTracker(
        api, com.spotbot.android.data.FileForwardStore(app.filesDir),
        symbols = { if (settings.settings.value.forwardWideUniverse) wide.stage2Symbols else com.spotbot.core.research.V3_UNIVERSE },
        universeName = {
            if (settings.settings.value.forwardWideUniverse) com.spotbot.core.research.WIDE_UNIVERSE else com.spotbot.core.research.DEFAULT_UNIVERSE
        },
    )
}
