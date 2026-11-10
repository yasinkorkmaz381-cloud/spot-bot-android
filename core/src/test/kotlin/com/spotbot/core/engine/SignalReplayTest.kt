package com.spotbot.core.engine

import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.exchange.ReplayMarketDataApi
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.model.Timeframe
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.support.Fixtures
import com.spotbot.core.support.TestData
import com.spotbot.core.trading.CoinOutcome
import com.spotbot.core.trading.ScanDiagnostics
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Backtest ile kâğıt işlem tutarlılığı: backtest'in işlem açtığı gerçek bir geçmiş sinyal, canlı karar motoruna
 * (tarama → strateji → kurallar → boyutlandırma → sanal hesap) beslenir. Yalnız simülasyon; gerçek emir yoktur.
 */
class SignalReplayTest {
    private fun dataset(symbol: String) =
        HistoricalDataset(symbol, Fixtures.period(symbol, "m15"), Fixtures.period(symbol, "h1"), Fixtures.period(symbol, "h4"))

    private val data = mapOf("NEARUSDT" to dataset("NEARUSDT"), "BTCUSDT" to dataset("BTCUSDT"))
    private val info = mapOf("NEARUSDT" to TestData.symbolInfo("NEARUSDT", "NEAR"), "BTCUSDT" to TestData.symbolInfo("BTCUSDT", "BTC"))
    private val scanner = ScannerConfig(watchlist = listOf("NEAR"), extraCoins = 0, minQuoteVolume24h = 100_000.0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a signal the backtest traded also opens a paper trade in the live engine`() = runBlocking {
        val backtest = BacktestEngine(BacktestConfig(listOf("NEARUSDT"), 1_789_689_600_000L, data.getValue("NEARUSDT").m15.last().closeTime, scanner = scanner)).run(data, info)
        // Isınma için yeterli 4H mumu olsun diye dönemin sonlarındaki bir işlem seçilir.
        val reference = backtest.closedTrades.last { it.position.setup.signalTime > 1_789_689_600_000L + 8L * 24 * 3_600_000 }
        val signal = reference.position.setup.signalTime

        val clock = AtomicLong(signal - Timeframe.M15.millis + 5_000)
        val engine = TradingEngine(
            api = ReplayMarketDataApi(data, info, clock::get),
            webSocket = MexcWebSocketClient(scope, url = "ws://127.0.0.1:9/ws"),
            scope = scope,
            store = InMemoryStateStore(),
            config = EngineConfig(scanner = scanner),
            timeSource = TimeSource { clock.get() },
        )
        engine.start()
        // 1) Başlangıç taraması yalnız gözlemdir.
        withTimeout(30_000) { engine.scanLogs.first { it.isNotEmpty() } }
        assertTrue(engine.portfolio.value.positions.isEmpty())
        // 2) Saat sinyal mumunun kapanışına ilerletilir: motor aynı sinyali canlı modda değerlendirir.
        //    Önce fiyat akışı güncellenir (canlıda sürekli akar), sonra tarama eşiği (kapanış + 3 sn) geçilir.
        clock.set(signal + 1_000)
        withTimeout(30_000) { engine.prices.first { p -> (p["NEARUSDT"]?.time ?: 0) >= signal } }
        clock.set(signal + 5_000)
        val logs = withTimeout(30_000) { engine.scanLogs.first { l -> l.any { it.boundary == signal } } }
        val decision = logs.last { it.boundary == signal }.coins.single { it.symbol == "NEARUSDT" }
        assertEquals("Karar: ${decision.summary} ${decision.reasons} ${decision.strategies.flatMap { s -> s.rules.filter { !it.passed && it.mandatory }.map { it.id + " " + it.detail } }}", CoinOutcome.OPENED, decision.outcome)

        val position = engine.portfolio.value.positions.single()
        val expected = reference.position
        assertEquals(expected.symbol, position.symbol)
        assertEquals(expected.strategy, position.strategy)
        // Aynı strateji ve risk motoru: stop, hedef ve geçersizlik seviyeleri backtest ile birebir aynı.
        assertEquals(expected.initialStopPrice, position.initialStopPrice, 1e-9)
        assertEquals(expected.targetPrice, position.targetPrice, 1e-9)
        assertEquals(expected.invalidationPrice, position.invalidationPrice, 1e-9)
        // Giriş: backtest sonraki mumun açılışından, canlı o anki fiyattan alır; fark kayma sınırı (%0,3) içinde kalır.
        assertTrue(kotlin.math.abs(position.entryPrice / expected.entryPrice - 1) < 0.003)
        // Miktar: aynı risk (%0,5) ile hesaplanır; anlık spread farkı nedeniyle birkaç yüzde oynayabilir.
        assertEquals(expected.initialQuantity, position.initialQuantity, expected.initialQuantity * 0.05)

        val diagnostics = ScanDiagnostics.of(logs.last { it.boundary == signal })
        assertEquals(1, diagnostics.opened)
        assertEquals(null, diagnostics.shortReason)
        assertTrue(ScanDiagnostics.lastSignal(logs)!!.opened)
        engine.stop()
    }
}
