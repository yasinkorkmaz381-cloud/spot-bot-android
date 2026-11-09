package com.spotbot.core.engine

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.CostModel
import com.spotbot.core.support.Fixtures
import com.spotbot.core.support.TestData
import com.spotbot.core.trading.DecisionOutcome
import com.spotbot.core.trading.ExitReason
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.TradeRationale
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
import java.util.concurrent.atomic.AtomicInteger

/** Canlı motorun uçtan uca davranışı: sahte MEXC API (gerçek BTC mumları) ve erişilemeyen WebSocket ile. */
class TradingEngineTest {

    private val candles = Fixtures.btc1h
    private val now = candles.last().closeTime + 60_000

    private class FakeApi(private val candles: List<Candle>, private val now: Long, private val failFirst: Int = 0) : MarketDataApi {
        val calls = AtomicInteger()
        private val symbols = listOf("BTCUSDT", "ETHUSDT")
        override suspend fun serverTime(): Long {
            if (calls.incrementAndGet() <= failFirst) throw java.io.IOException("ağ yok")
            return now
        }
        override suspend fun exchangeInfo(): List<SymbolInfo> = symbols.map { TestData.symbolInfo(it, it.removeSuffix("USDT")) }
        override suspend fun klines(symbol: String, timeframe: Timeframe, limit: Int, startTime: Long?, endTime: Long?) = candles.takeLast(limit)
        override suspend fun tickers24h() = symbols.map { Ticker24h(it, candles.last().close, 1.0, 0.0, 0.0, 0.0, 50_000_000.0, candles.last().close, candles.last().close) }
        override suspend fun bookTickers() = symbols.map { bookTicker(it) }
        override suspend fun bookTicker(symbol: String) = candles.last().close.let { BookTicker(symbol, it, 1.0, it * 1.0001, 1.0, now) }
        override suspend fun orderBook(symbol: String, limit: Int) =
            OrderBook(symbol, listOf(PriceLevel(candles.last().close * 0.999, 100.0)), listOf(PriceLevel(candles.last().close * 1.001, 100.0)))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun engine(api: MarketDataApi, store: StateStore = InMemoryStateStore()) = TradingEngine(
        api = api,
        webSocket = MexcWebSocketClient(scope, url = "ws://127.0.0.1:9/ws"),
        scope = scope,
        store = store,
        config = EngineConfig(scanner = com.spotbot.core.scanner.ScannerConfig(watchlist = listOf("BTC", "ETH"), extraCoins = 0)),
        timeSource = TimeSource { now },
    )

    @Test
    fun `starts, selects the universe and runs an initial scan without trading`() = runBlocking {
        val engine = engine(FakeApi(candles, now))
        engine.start()
        val report = withTimeout(20_000) { engine.lastScan.first { it != null }!! }

        assertEquals(EngineStatus.RUNNING, engine.health.value.status)
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), engine.health.value.universe)
        assertEquals(2, report.coins.size)
        val decisions = withTimeout(5_000) {
            engine.portfolio.first { p -> p.decisions.any { it.summary.startsWith("Başlangıç taraması") } }
        }.decisions
        assertTrue("İlk taramada işlem açılmamalı", decisions.none { it.outcome == DecisionOutcome.OPENED })
        engine.stop()
        assertEquals(EngineStatus.STOPPED, engine.health.value.status)
    }

    @Test
    fun `keeps retrying when the network is unavailable at startup`() = runBlocking {
        val api = FakeApi(candles, now, failFirst = 1)
        val engine = engine(api)
        engine.start()
        withTimeout(5_000) { engine.health.first { it.status == EngineStatus.ERROR } }
        assertTrue(engine.health.value.message!!.startsWith("Bağlantı bekleniyor"))
        withTimeout(20_000) { engine.health.first { it.status == EngineStatus.RUNNING } }
        engine.stop()
    }

    @Test
    fun `manual close realizes the position from the live bid`() = runBlocking {
        val price = candles.last().close
        val opened = PaperAccount.open(
            PortfolioState.create(1_000.0), TestData.setup("BTCUSDT", entry = price, stop = price * 0.98, target = price * 1.05), 0.001, price,
            CostModel(), 1.0, 50.0, TradeRationale("", "", "", "", "", ""), null, now,
        )
        val store = InMemoryStateStore(opened)
        val engine = engine(FakeApi(candles, now), store)
        engine.start()
        engine.closePosition("P1")
        val state = withTimeout(20_000) { engine.portfolio.first { it.closedTrades.isNotEmpty() } }

        assertTrue(state.positions.isEmpty())
        assertEquals(ExitReason.MANUAL, state.closedTrades.single().reason)
        // Durum önce yayınlanır, hemen ardından kaydedilir; kaydın tamamlanmasını bekle.
        withTimeout(5_000) { while (store.load()!!.closedTrades.isEmpty()) kotlinx.coroutines.delay(20) }
        assertEquals(state.closedTrades, store.load()!!.closedTrades)
        engine.stop()
    }

    @Test
    fun `every scanned coin gets a decision record with data timestamps`() = runBlocking {
        val engine = engine(FakeApi(candles, now))
        engine.start()
        val log = withTimeout(20_000) { engine.scanLogs.first { it.isNotEmpty() } }.last()
        val report = engine.lastScan.value!!

        assertEquals(report.coins.map { it.symbol }.toSet(), log.coins.map { it.symbol }.toSet())
        log.coins.forEach { coin ->
            assertTrue("${coin.symbol} veri zamanı eksik", coin.data.m15Close != null && coin.data.h1Close != null && coin.data.h4Close != null)
            assertTrue(coin.summary.isNotBlank())
        }
        // İlk tarama gözlem amaçlıdır; hiçbir coin için işlem açılmaz.
        assertTrue(log.coins.none { it.outcome == com.spotbot.core.trading.CoinOutcome.OPENED })
        engine.stop()
    }

    @Test
    fun `pending limit order survives a restart and fills as maker at the limit`() = runBlocking {
        val price = candles.last().close
        val setup = TestData.setup("BTCUSDT", entry = price * 1.01, stop = price * 0.97, target = price * 1.06)
        val order = com.spotbot.core.trading.PendingOrder(
            setup, limitPrice = price * 1.01, quantity = 0.001, riskAmount = 3.0, qualityScore = 55.0,
            rationale = TradeRationale("", "", "", "", "", ""), report = null, placedAt = now - 60_000, expiresAt = now + 3_600_000,
        )
        val store = InMemoryStateStore(PortfolioState.create(1_000.0).copy(pendingOrders = listOf(order)))
        val engine = engine(FakeApi(candles, now), store)
        engine.start()
        val state = withTimeout(20_000) { engine.portfolio.first { it.positions.isNotEmpty() } }

        assertTrue(state.pendingOrders.isEmpty())
        with(state.positions.single()) {
            assertEquals(price * 1.01, entryPrice, 1e-9)
            assertEquals(0.0, entryFee, 0.0)
            assertEquals(0.001, quantity, 1e-12)
        }
        engine.stop()
    }

    @Test
    fun `downtime since the last heartbeat is recorded on start`() = runBlocking {
        val store = InMemoryStateStore(PortfolioState.create(1_000.0).copy(lastHeartbeat = now - 3_600_000, firstStartedAt = now - 7_200_000))
        val engine = engine(FakeApi(candles, now), store)
        engine.start()
        val state = withTimeout(20_000) { engine.portfolio.first { it.downtimes.isNotEmpty() } }

        val downtime = state.downtimes.single()
        assertEquals(now - 3_600_000, downtime.from)
        assertTrue(downtime.note.contains("beklenmedik"))
        assertTrue(state.decisions.any { it.summary.startsWith("Bot 60 dakika çalışmadı") })
        engine.stop()
        assertTrue(store.load()!!.stoppedByUserAt > 0)
    }
}
