package com.spotbot.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotbot.android.ui.backtest.BacktestReportContent
import com.spotbot.android.ui.detail.CoinDetailContent
import com.spotbot.android.ui.detail.CoinDetailViewModel
import com.spotbot.android.ui.detail.DetailState
import com.spotbot.android.ui.scan.DecisionLogContent
import com.spotbot.android.ui.trades.TradeDetailContent
import com.spotbot.android.ui.trades.TradesTab
import com.spotbot.android.ui.panel.PanelActions
import com.spotbot.android.ui.panel.PanelContent
import com.spotbot.android.ui.panel.PanelUiState
import com.spotbot.android.ui.panel.PositionRow
import com.spotbot.android.ui.scan.ScanContent
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.trades.TradesContent
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Series
import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.engine.EngineHealth
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.exchange.FeedHealth
import com.spotbot.core.exchange.mexc.ConnectionStatus
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.risk.PositionSizer
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.risk.netRiskReward
import com.spotbot.core.rules.RuleContext
import com.spotbot.core.rules.RuleEngine
import com.spotbot.core.scanner.CoinMarketData
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.Scanner
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.trading.Decision
import com.spotbot.core.trading.DecisionOutcome
import com.spotbot.core.trading.Performance
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.TradeRationale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.GZIPInputStream

/**
 * Ekranları gerçek MEXC verisiyle (BTC/NEAR) Robolectric üzerinde çizer. Çökme olmadan çizildiklerini doğrular;
 * `-Pscreenshots` ile çalıştırılırsa görüntüleri app/build/outputs/roborazzi altına kaydeder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1500dp-xhdpi")
open class ScreenshotTest {

    /** Dosya adı öneki; küçük ekran sınıfı aynı ekranları "k-" önekiyle kaydeder. */
    protected open val prefix: String = ""

    @get:Rule
    val compose = createComposeRule()

    private fun candles(path: String): List<Candle> {
        val stream = javaClass.getResourceAsStream(path)!!
        val input = if (path.endsWith(".gz")) GZIPInputStream(stream) else stream
        return input.bufferedReader().readLines().drop(1).map { l ->
            val p = l.split(',')
            Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toLong(), p[7].toDouble())
        }
    }

    private val btc = Series("BTCUSDT", Timeframe.H1, candles("/fixtures/BTCUSDT_1H.csv"))
    private val mtf = MultiTimeframe("BTCUSDT", btc.analyze(), btc.analyze(), btc.analyze())
    private val costs = CostModel()

    private fun info(symbol: String) = SymbolInfo(symbol, symbol.removeSuffix("USDT"), "USDT", 2, 6, 0.0001, 1.0, 0.0, 0.0005, true, true, listOf("LIMIT", "MARKET"), false, 1)

    private val backtestData by lazy {
        listOf("NEARUSDT", "BTCUSDT").associateWith { s ->
            HistoricalDataset(s, candles("/fixtures/${s}_m15.csv.gz"), candles("/fixtures/${s}_h1.csv.gz"), candles("/fixtures/${s}_h4.csv.gz"))
        }
    }

    private val backtestConfig by lazy {
        BacktestConfig(listOf("NEARUSDT"), 1_789_689_600_000L, backtestData.getValue("NEARUSDT").m15.last().closeTime, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0), shadowAnalysis = true)
    }

    private val backtest by lazy { BacktestEngine(backtestConfig).run(backtestData, backtestData.keys.associateWith { info(it) }) }

    private val portfolio: PortfolioState by lazy {
        val last = btc.candles.last()
        val setup = TradeSetup(
            "BTCUSDT", StrategyId.TREND_PULLBACK, last.closeTime, last.close, last.close * 0.985, last.close * 1.035, last.close * 0.99,
            listOf("15M yutan boğa mumu", "RSI 50 üzerinde ve yükseliyor (56)", "Fiyat VWAP üzerinde"), listOf("1H EMA21-55 bölgesi", "1H FVG"),
            "4H yükseliş trendinde 1H düzeltme EMA21-55 bölgesinde tutundu.", "Düzeltme dibinin 0,25 ATR altı.", "Düzeltme öncesi 1H tepe", "1H kapanış düzeltme dibinin altında.",
        )
        val rr = netRiskReward(setup.entryPrice, setup.stopPrice, setup.targetPrice, costs)
        val sizing = PositionSizer.size(1_000.0, 1_000.0, setup.entryPrice, setup.stopPrice, setup.targetPrice, info("BTCUSDT"), costs, RiskConfig())
        val report = RuleEngine().evaluate(
            RuleContext(setup, mtf, mtf, null, Scanner.marketState(mtf, emptyList(), ScannerConfig()), RiskConfig(), rr, sizing, true, setOf(Regime.TREND_UP), last.closeTime, emptySet(), 0, 0.0, 0.5),
        )
        val rationale = TradeRationale(
            "Tarama ön puanı 6; 4H rejimi TREND_UP; BTC'ye göre 7 günlük göreceli güç +3,2%",
            "B – Trend Pullback: 4H yükseliş trendinde 1H düzeltme EMA21-55 bölgesinde tutundu; 15M yutan boğa mumu.",
            "4H: TREND_UP, ADX 27,4, SuperTrend yukarı; 1H yapı UP (HH/HL)",
            "Stop ${"%.2f".format(setup.stopPrice)}: düzeltme dibinin 0,25 ATR altı.",
            "Net R:R %.2f (komisyon, spread ve kayma dahil); risk 5,00 USDT (%%0,50)".format(rr.ratio),
            setup.invalidationReason,
        )
        var state = PortfolioState.create(1_000.0).copy(closedTrades = backtest.closedTrades)
        state = state.copy(cash = 1_000.0 + backtest.closedTrades.sumOf { it.netPnl })
        state = PaperAccount.open(state, setup, 0.004, last.close, costs, 5.0, report.qualityScore, rationale, report, last.closeTime)
        listOf(
            Decision(last.closeTime - 900_000, "ETHUSDT", StrategyId.BREAKOUT_RETEST, DecisionOutcome.REJECTED, "ETHUSDT: Breakout + Retest kurulumu reddedildi", listOf("Z03 Net risk/getiri en az asgari oran: Net R:R 1.21 (asgari 1.5)"), 41.0),
            Decision(last.closeTime - 600_000, "SOLUSDT", null, DecisionOutcome.NO_SETUP, "SOLUSDT: uygun kurulum yok", listOf("B: Düzeltme bir destek bölgesine değmedi")),
        ).forEach { state = PaperAccount.log(state, it) }
        state
    }

    private fun capture(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            SpotBotTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix$name.png")
    }

    private fun panelState(): PanelUiState {
        val position = portfolio.positions.single()
        val bid = position.entryPrice * 1.006
        val pnl = position.unrealizedPnl(bid, costs)
        return PanelUiState(
            health = EngineHealth(
                status = EngineStatus.RUNNING,
                feed = FeedHealth(ConnectionStatus.CONNECTED, false, btc.candles.last().closeTime, btc.candles.last().closeTime, 0.004, reconnects = 2),
                lastScanAt = btc.candles.last().closeTime, universe = List(20) { "X" },
                runLocation = "Bu telefon (Android ön plan servisi)",
                uptime24hPercent = 97.4,
                lastDowntime = com.spotbot.core.trading.Downtime(btc.candles.last().closeTime - 3 * 3_600_000, btc.candles.last().closeTime - 3 * 3_600_000 + 37 * 60_000, "Bot beklenmedik şekilde durmuş (telefon, Android ya da uygulama kapanmış olabilir)"),
            ),
            equity = portfolio.cash + position.marketValue(bid, costs),
            cash = portfolio.cash,
            initialCapital = 1_000.0,
            unrealized = pnl,
            performance = Performance.summarize(portfolio, btc.candles.last().closeTime + 30L * 24 * 3600_000),
            positions = listOf(PositionRow(position, bid, pnl, pnl / position.riskAmount)),
            decisions = portfolio.decisions.reversed(),
            enabledStrategies = setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK),
            equityHistory = backtest.equityCurve.map { it.second },
            risk = com.spotbot.core.trading.RiskStatus.of(
                portfolio.copy(dayStartEquity = 1_010.0), portfolio.cash + position.marketValue(bid, costs), RiskConfig(),
                com.spotbot.core.trading.StrategyProfile.ADAPTIVE, false, true, listOf("ETH ~ SOL (0.82)"),
            ),
            dayStartEquity = 1_004.2,
            recentTrades = portfolio.closedTrades.takeLast(3).reversed(),
        )
    }

    private val actions = PanelActions({}, {}, {}, {}, {})

    @Test
    fun panel() = capture("1-panel") { PanelContent(panelState(), actions) }

    /** Düşüş trendindeki BTC (yapay seri): piyasa filtresi alımı engeller. */
    private val fallingMarket by lazy {
        val end = 2_000L * Timeframe.H4.millis
        fun series(tf: Timeframe) = Series("BTCUSDT", tf, List(300) { i ->
            val c = 80_000.0 * Math.pow(0.996, i.toDouble())
            val o = if (i == 0) c else 80_000.0 * Math.pow(0.996, i - 1.0)
            val openTime = end - (300 - i) * tf.millis
            Candle(openTime, o, maxOf(o, c) * 1.002, minOf(o, c) * 0.998, c, 1.0, openTime + tf.millis, c)
        })
        val falling = MultiTimeframe("BTCUSDT", series(Timeframe.H4).analyze(), series(Timeframe.H1).analyze(), series(Timeframe.M15).analyze())
        Scanner.marketState(falling, emptyList(), ScannerConfig(), boundary = end)
    }

    @Test
    fun panelMarketBlocked() {
        assertTrue(!fallingMarket.allowsLongs)
        val base = panelState()
        val risk = com.spotbot.core.trading.RiskStatus.of(
            portfolio, base.equity, RiskConfig(), com.spotbot.core.trading.StrategyProfile.ADAPTIVE, false, false, emptyList(), fallingMarket,
        )
        capture("1b-panel-piyasa-filtresi") { PanelContent(base.copy(risk = risk), actions) }
    }

    @Test
    fun marketFilterDetails() = capture("2b-piyasa-filtresi") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            com.spotbot.android.ui.common.SectionCard("Piyasa filtresi (BTC)") { com.spotbot.android.ui.scan.MarketFilterDetails(fallingMarket) }
        }
    }

    @Test
    fun panelLight() = capture("1-panel-acik-tema", dark = false) { PanelContent(panelState(), actions) }

    private val scanReport by lazy {
        val symbols = listOf("ETHUSDT" to 6e7, "SOLUSDT" to 1.8e7, "NEARUSDT" to 3e5, "ZECUSDT" to 1.3e6, "INJUSDT" to 1.9e6, "AVAXUSDT" to 2.4e5)
        val coins = symbols.mapIndexed { i, (s, volume) ->
            val price = btc.close.last()
            val spread = if (volume < 1e6) 0.003 else 0.0001
            CoinMarketData(
                info(s), Ticker24h(s, price, if (s == "INJUSDT") 31.0 else -1.2 + i, 0.0, 0.0, 0.0, volume, price, price),
                BookTicker(s, price * (1 - spread / 2), 1.0, price * (1 + spread / 2), 1.0, 0),
                OrderBook(s, listOf(PriceLevel(price * 0.999, 100.0)), listOf(PriceLevel(price * 1.001, 100.0))),
                MultiTimeframe(s, btc.analyze(), btc.analyze(), btc.analyze()),
            )
        }
        Scanner.scan(coins, mtf, ScannerConfig(), btc.candles.last().closeTime)
    }

    @Test
    fun scan() {
        assertTrue(scanReport.coins.any { it.status == CoinStatus.ELIMINATED })
        capture("2-tarama") { ScanContent(scanReport, scanLog.coins.associateBy { it.symbol }, {}) }
    }

    @Test
    fun scanDiagnostics() {
        val view = com.spotbot.android.ui.scan.DiagnosticsView(
            EngineHealth(status = EngineStatus.RUNNING, lastScanAt = scanLog.time, lastCandleSyncAt = scanLog.time, feed = FeedHealth(ConnectionStatus.CONNECTED, false, scanLog.time, reconnects = 1)),
            com.spotbot.core.trading.ScanDiagnostics.of(scanLog.copy(marketAllowsLongs = false), fallingMarket),
            com.spotbot.core.trading.ScanDiagnostics.lastSignal(listOf(scanLog)), fallingMarket,
        )
        capture("2c-teshis") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { com.spotbot.android.ui.scan.DiagnosticsCard(view, initiallyExpanded = true) }
        }
    }

    @Test
    fun backtestWithStrategyC() {
        val r = BacktestEngine(backtestConfig.copy(symbols = listOf("NEARUSDT", "BTCUSDT"), enabledStrategies = StrategyId.entries.toSet(), shadowAnalysis = false))
            .run(backtestData, backtestData.keys.associateWith { info(it) })
        assertTrue(r.perStrategy.sumOf { it.stats.trades } == r.trades)
        capture("6c-backtest-uc-strateji") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.spotbot.android.ui.backtest.StrategyCompareCard(r)
            }
        }
    }

    private fun detailState() = DetailState(
        symbol = "BTCUSDT",
        analysis = btc.analyze(),
        mtf = mtf,
        plans = CoinDetailViewModel.plans(mtf, com.spotbot.core.strategy.StrategyParams(), costs),
        scan = scanReport.coins.first().copy(symbol = "BTCUSDT"),
        decision = scanLog.coins.first().copy(symbol = "BTCUSDT"),
        decisionTime = scanLog.boundary,
        position = portfolio.positions.single(),
        loading = false,
    )

    @Test
    fun detail() = capture("3-coin-detay") { CoinDetailContent(detailState(), {}) }

    private fun bid() = portfolio.positions.single().entryPrice * 1.006

    @Test
    fun trades() = capture("4-islemler") { TradesContent(portfolio, mapOf("BTCUSDT" to bid()), {}) }

    @Test
    fun closedTrades() = capture("5-kapanmis-islemler") { TradesContent(portfolio, emptyMap(), {}, initialTab = TradesTab.HISTORY) }

    /** Gölge test sekmesi: S1 ileri test kartı (temsili sayılar; yalnız düzen kontrolü). */
    @Test
    fun shadowTab() {
        val start = 1_793_000_000_000L
        val result = com.spotbot.core.research.V3Result(
            "S1 Göreceli güç (BTC'ye göre)", "L=30 gün", "Orta", start, start + 6 * 86_400_000L, 1, 0, 0.0, 0.0, -0.4, 0.0, -0.21, 0.6,
            0.3, 0.5, 40.0, -1.8, emptyList(),
            listOf(com.spotbot.core.research.V3Trade("SOLUSDT", start + 86_400_000L, start + 4 * 86_400_000L, 180.0, 176.4, 1.0, -2.1, -0.4, 0.18, 0.3, "İz süren stop", "yatay")),
            listOf(com.spotbot.core.research.V3OpenPosition("ZECUSDT", start + 5 * 86_400_000L, 410.0, 0.5, 380.0, 415.0, 2.0)),
        )
        val state = com.spotbot.core.research.ForwardState(result.strategy, result.option, start, 1_000.0, start + 6 * 86_400_000L, start + 6 * 86_400_000L, result, 28, 17)
        capture("5b-golge-test") { TradesContent(portfolio, emptyMap(), {}, initialTab = TradesTab.SHADOW, forward = state) }
    }

    private val scanLog: com.spotbot.core.trading.ScanLog by lazy {
        val t = btc.candles.last().closeTime
        val stamp = com.spotbot.core.trading.DataStamp(t, t, t - 3_600_000, t + 2_000)
        val decision = com.spotbot.core.trading.DecisionPipeline().evaluate(
            com.spotbot.core.trading.Candidate(mtf, null, info("BTCUSDT"), costs),
            com.spotbot.core.trading.DecisionContext(t, 1_000.0, 1_000.0, emptyList(), 0.0, Scanner.marketState(mtf, emptyList(), ScannerConfig()), mtf, emptyMap()),
        )
        val checks = com.spotbot.core.trading.DecisionLogs.strategyChecks(decision.evaluations)
        com.spotbot.core.trading.ScanLog(
            t + 3_000, t, com.spotbot.core.trading.StrategyProfile.ADAPTIVE, true, listOf("BTC 4H rejimi: RANGE"), false,
            listOf(
                com.spotbot.core.trading.CoinDecision("ETHUSDT", com.spotbot.core.trading.CoinOutcome.REJECTED, "Breakout + Retest kurulumu zorunlu kurallarca reddedildi", listOf("Z03 Net risk/getiri en az asgari oran: Net R:R 1.21 (asgari 1.5)"), 4, stamp, checks),
                com.spotbot.core.trading.CoinDecision("SOLUSDT", com.spotbot.core.trading.CoinOutcome.NO_SETUP, "Uygun kurulum bulunamadı", listOf("B: Düzeltme bir destek bölgesine değmedi"), 3, stamp),
                com.spotbot.core.trading.CoinDecision("XRPUSDT", com.spotbot.core.trading.CoinOutcome.NOT_SELECTED, "Ön puan 0: en iyi 4 aday arasında değil (ya da puan ≤ 0)", emptyList(), 0, stamp),
                com.spotbot.core.trading.CoinDecision("NEARUSDT", com.spotbot.core.trading.CoinOutcome.ELIMINATED, "Tarama filtresinde elendi", listOf("24s hacim düşük (300,000 USDT)", "Spread yüksek (%0.300)"), 0, stamp),
                com.spotbot.core.trading.CoinDecision("INJUSDT", com.spotbot.core.trading.CoinOutcome.STALE_DATA, "Veri güncel değil", listOf("Z17: Canlı fiyat 95 sn eski"), 2, stamp.copy(priceTime = t - 95_000)),
                com.spotbot.core.trading.CoinDecision("ZECUSDT", com.spotbot.core.trading.CoinOutcome.OPENED, "İşlem açıldı @ 410.2", emptyList(), 5, stamp),
            ),
        )
    }

    @Test
    fun decisionLog() = capture("7-karar-gunlugu") { DecisionLogContent(listOf(scanLog), expanded = true) }

    @Test
    fun tradeChart() {
        val position = portfolio.positions.single()
        capture("8-islem-grafigi") { TradeDetailContent(position, null, bid(), btc.candles.takeLast(60), {}, {}) }
    }

    @Test
    fun settings() {
        val dir = java.nio.file.Files.createTempDirectory("ayarlar").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val engine = com.spotbot.core.engine.TradingEngine(
            com.spotbot.core.exchange.mexc.MexcRestClient(), com.spotbot.core.exchange.mexc.MexcWebSocketClient(scope), scope,
            com.spotbot.android.data.FileStateStore(dir),
        )
        val viewModel = com.spotbot.android.ui.settings.SettingsViewModel(engine, com.spotbot.android.data.SettingsRepository(dir))
        capture("12-ayarlar") { com.spotbot.android.ui.settings.SettingsScreen(viewModel = viewModel) }
    }

    @Test
    fun profileComparison() {
        val data = listOf("NEARUSDT", "BTCUSDT").associateWith { s ->
            HistoricalDataset(s, candles("/fixtures/${s}_m15.csv.gz"), candles("/fixtures/${s}_h1.csv.gz"), candles("/fixtures/${s}_h4.csv.gz"))
        }
        val config = BacktestConfig(listOf("NEARUSDT"), 1_789_689_600_000L, data.getValue("NEARUSDT").m15.last().closeTime, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0))
        val results = com.spotbot.core.backtest.StrategyComparison.run(config, data, data.keys.associateWith { info(it) })
        assertTrue(results.size == 3)
        capture("9-profil-karsilastirma") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.spotbot.android.ui.backtest.ComparisonContent(results, 21, listOf("NEARUSDT"))
            }
        }
    }

    @Test
    fun backtestReport() {
        assertTrue(backtest.trades > 0)
        capture("6-backtest") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BacktestReportContent(backtest)
            }
        }
    }

    @Test
    fun backtestStrategiesAndHistory() {
        capture("6b-backtest-a-b-ve-gecmis") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.spotbot.android.ui.backtest.StrategyCompareCard(backtest)
                com.spotbot.android.ui.backtest.TradeHistoryCard(backtest) {}
            }
        }
    }

    @Test
    fun backtestTradeDetail() {
        val trade = backtest.closedTrades.first()
        val chart = com.spotbot.android.ui.backtest.TradeCharts.build(trade, backtestData)
        assertTrue(chart != null && chart.candles.isNotEmpty())
        // Grafik giriş anındaki seviyeleri göstermeli; giriş sonrası bölge gölgeli (gelecek veri gerekçe değildir).
        assertTrue(chart!!.candles.first().openTime < trade.position.openedAt)
        val features = backtest.entryFeatures.first { it.symbol == trade.position.symbol && it.signalTime == trade.position.setup.signalTime }
        capture("10-islem-detayi") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.spotbot.android.ui.backtest.TradeDetailContent(trade, features, backtest.excursions[trade.position.id], chart)
            }
        }
    }

    @Test
    fun backtestIsolated() {
        val results = com.spotbot.core.backtest.StrategyIsolation.run(backtestConfig.copy(shadowAnalysis = false), backtestData, backtestData.keys.associateWith { info(it) })
        assertTrue(results.size == 3)
        capture("11-a-b-ayri") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.spotbot.android.ui.backtest.IsolatedContent(results, 21, listOf("NEARUSDT"))
            }
        }
    }
}

/** Aynı ekranlar küçük telefon boyutunda (320 × 640 dp): taşma ve yatay kaydırma olmamalı. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-xhdpi")
class SmallScreenTest : ScreenshotTest() {
    override val prefix = "k-"
}
