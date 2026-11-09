package com.spotbot.core.backtest

import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.support.Fixtures
import com.spotbot.core.support.TestData
import com.spotbot.core.trading.ExitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gerçek MEXC verisiyle (NEAR + BTC, Eylül–Ekim 2026) backtest regresyon testleri. */
class BacktestEngineTest {

    private fun dataset(symbol: String) =
        HistoricalDataset(symbol, Fixtures.period(symbol, "m15"), Fixtures.period(symbol, "h1"), Fixtures.period(symbol, "h4"))

    private val data = mapOf("NEARUSDT" to dataset("NEARUSDT"), "BTCUSDT" to dataset("BTCUSDT"))
    private val info = mapOf("NEARUSDT" to TestData.symbolInfo("NEARUSDT", "NEAR"), "BTCUSDT" to TestData.symbolInfo("BTCUSDT", "BTC"))
    private val start = 1_789_689_600_000L // 2026-09-18 00:00 UTC (10 günlük ısınmadan sonra)
    private val end = data.getValue("NEARUSDT").m15.last().closeTime
    private val config = BacktestConfig(listOf("NEARUSDT"), start, end, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0))
    private val report by lazy { BacktestEngine(config).run(data, info) }

    @Test
    fun `produces trades on real data with consistent accounting`() {
        assertTrue("En az bir işlem bekleniyor (${report.trades})", report.trades > 0)
        assertEquals(report.trades, report.closedTrades.size)
        assertEquals(config.initialCapital + report.closedTrades.sumOf { it.netPnl }, report.finalEquity, 1e-6)
        assertTrue(report.maxDrawdownPercent >= 0)
        assertTrue(report.btcBuyAndHoldPercent != null)
        assertEquals(report.trades, report.exitReasons.values.sum())
    }

    @Test
    fun `is deterministic`() {
        val again = BacktestEngine(config).run(data, info)
        assertEquals(report.finalEquity, again.finalEquity, 0.0)
        assertEquals(report.closedTrades.map { it.position.openedAt }, again.closedTrades.map { it.position.openedAt })
    }

    @Test
    fun `entries fill at the open of the candle after the signal`() {
        report.closedTrades.forEach { t ->
            // Sinyal mumunun kapanış anı = sonraki mumun açılış anı; dolum o mumun açılış fiyatından yapılır.
            assertEquals(t.position.setup.signalTime, t.position.openedAt)
            assertTrue(t.closedAt >= t.position.openedAt)
        }
    }

    @Test
    fun `losing stops cost about one R and never much more without a gap`() {
        report.closedTrades.filter { it.reason == ExitReason.STOP_LOSS }.forEach { t ->
            assertTrue("Stop kaybı ${t.rMultiple}R", t.rMultiple > -1.6 && t.rMultiple < 0)
        }
    }

    @Test
    fun `limit entries fill exactly at the signal price and shadow analysis measures rules`() {
        val limitConfig = config.copy(execution = com.spotbot.core.trading.ExecutionConfig(entryMode = com.spotbot.core.trading.EntryMode.LIMIT), shadowAnalysis = true)
        val limit = BacktestEngine(limitConfig).run(data, info)
        limit.closedTrades.forEach { t ->
            // Limit fiyatı borsanın fiyat adımına aşağı yuvarlanır (alış emri).
            val expected = com.spotbot.core.trading.OrderRules.roundPrice(t.position.setup.entryPrice, com.spotbot.core.trading.OrderSide.BUY, info.getValue("NEARUSDT"))
            assertEquals(expected, t.position.entryPrice, 0.0)
            assertTrue(t.position.entryPrice <= t.position.setup.entryPrice)
            assertEquals(0.0, t.position.entryFee, 0.0)
        }
        val shadow = limit.shadow!!
        assertTrue("Gölge işlem bekleniyor", shadow.trades > 0)
        assertTrue(shadow.rules.none { it.id in com.spotbot.core.backtest.ShadowSimulator.portfolioRules })
        assertEquals(shadow.trades, shadow.scoreBuckets.sumOf { it.count })
    }

    @Test
    fun `risk per trade stays at or below the configured share of equity`() {
        report.closedTrades.forEach { t ->
            assertTrue(t.position.riskAmount <= config.initialCapital * 1.2 * config.risk.riskPerTradePercent / 100)
        }
    }
}
