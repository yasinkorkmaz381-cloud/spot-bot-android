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
import com.spotbot.android.ui.detail.DetailContent
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
class ScreenshotTest {

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

    private val backtest by lazy {
        val data = listOf("NEARUSDT", "BTCUSDT").associateWith { s ->
            HistoricalDataset(s, candles("/fixtures/${s}_m15.csv.gz"), candles("/fixtures/${s}_h1.csv.gz"), candles("/fixtures/${s}_h4.csv.gz"))
        }
        val config = BacktestConfig(listOf("NEARUSDT"), 1_789_689_600_000L, data.getValue("NEARUSDT").m15.last().closeTime, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0), shadowAnalysis = true)
        BacktestEngine(config).run(data, data.keys.associateWith { info(it) })
    }

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

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            SpotBotTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
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
        )
    }

    private val actions = PanelActions({}, {}, {}, {}, {})

    @Test
    fun panel() = capture("1-panel") { PanelContent(panelState(), actions) }

    @Test
    fun panelDark() = capture("1-panel-dark", dark = true) { PanelContent(panelState(), actions) }

    @Test
    fun scan() {
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
        val report = Scanner.scan(coins, mtf, ScannerConfig(), btc.candles.last().closeTime)
        assertTrue(report.coins.any { it.status == CoinStatus.ELIMINATED })
        capture("2-tarama") { ScanContent(report, {}) }
    }

    @Test
    fun detail() = capture("3-coin-detay") {
        // Uygulamadaki gibi: içerik kaydırılabilir bir sütunun içinde çizilir.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailContent(btc.analyze(), portfolio.positions.single())
        }
    }

    @Test
    fun trades() = capture("4-islemler") { TradesContent(portfolio, initialTab = 0, expanded = true) }

    @Test
    fun closedTrades() = capture("5-kapanmis-islemler") { TradesContent(portfolio, initialTab = 1) }

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
            ),
        )
    }

    @Test
    fun decisionLog() = capture("7-karar-gunlugu") { TradesContent(portfolio, initialTab = 2, expanded = true, scanLogs = listOf(scanLog)) }

    @Test
    fun tradeChart() {
        val position = portfolio.positions.single()
        capture("8-islem-grafigi") {
            TradesContent(portfolio, initialTab = 0, expanded = true, charts = mapOf(position.id to btc.candles.takeLast(60)))
        }
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
}

