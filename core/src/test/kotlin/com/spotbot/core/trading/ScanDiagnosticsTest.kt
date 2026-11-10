package com.spotbot.core.trading

import com.spotbot.core.backtest.BacktestConfig
import com.spotbot.core.backtest.BacktestEngine
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.scanner.ScannerConfig
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.StrategyParams
import com.spotbot.core.support.Fixtures
import com.spotbot.core.support.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanDiagnosticsTest {
    private val stamp = DataStamp(1, 1, 1, 1)
    private fun rule(id: String, passed: Boolean) = RuleCheck(id, "kural $id", true, passed, "ayrıntı")
    private fun setup(vararg rules: RuleCheck) = listOf(StrategyCheck(StrategyId.TREND_PULLBACK, true, true, emptyList(), 100.0, 98.0, 105.0, 1.2, 50.0, false, rules.toList()))
    private fun log(time: Long, allows: Boolean, vararg coins: CoinDecision) = ScanLog(time, time, StrategyProfile.ADAPTIVE, allows, emptyList(), false, coins.toList())

    private val coins = arrayOf(
        CoinDecision("AUSDT", CoinOutcome.ELIMINATED, "", listOf("Spread yüksek (%0.300)", "24s hacim düşük (300,000 USDT)"), 0, stamp),
        CoinDecision("BUSDT", CoinOutcome.ELIMINATED, "", listOf("Spread yüksek (%0.250)"), 0, stamp),
        CoinDecision("CUSDT", CoinOutcome.NOT_SELECTED, "", emptyList(), 0, stamp),
        CoinDecision("DUSDT", CoinOutcome.NO_SETUP, "", emptyList(), 3, stamp),
        CoinDecision("EUSDT", CoinOutcome.REJECTED, "", emptyList(), 4, stamp, setup(rule("Z03", false), rule("Z07", true))),
    )

    @Test
    fun `funnel counts come from the decision log and add up`() {
        val d = ScanDiagnostics.of(log(10, true, *coins))
        assertEquals(5, d.scanned)
        assertEquals(2, d.eliminated)
        assertEquals(1, d.notSelected)
        assertEquals(1, d.noSetup)
        assertEquals(1, d.rejected)
        assertEquals(0, d.eligible)
        assertEquals(d.scanned, d.eliminated + d.flagged + d.notSelected + d.noSetup + d.rejected + d.staleData + d.eligible)
        assertEquals("Spread yüksek" to 2, d.topReasons.first())
        assertTrue(d.shortReason!!.contains("Z03"))
    }

    @Test
    fun `short reason follows the real blocker in priority order`() {
        assertTrue(ScanDiagnostics.of(log(10, false, *coins)).shortReason!!.contains("BTC"))
        assertTrue(ScanDiagnostics.of(log(10, true, coins[0], coins[2], coins[3])).shortReason!!.startsWith("15M giriş teyidi bekleniyor"))
        assertTrue(ScanDiagnostics.of(log(10, true, coins[0], coins[2])).shortReason!!.startsWith("Uygun aday yok"))
        assertTrue(ScanDiagnostics.of(log(10, true, CoinDecision("XUSDT", CoinOutcome.STALE_DATA, "", emptyList(), 1, stamp))).shortReason!!.contains("güncellenemedi"))
        assertNull(ScanDiagnostics.of(log(10, true, CoinDecision("XUSDT", CoinOutcome.OPENED, "", emptyList(), 5, stamp, setup(rule("Z03", true))))).shortReason)
    }

    @Test
    fun `last signal reports why it did not become a trade`() {
        val logs = listOf(log(10, true, *coins), log(20, true, coins[2]))
        val s = ScanDiagnostics.lastSignal(logs)!!
        assertEquals("EUSDT", s.symbol)
        assertFalse(s.opened)
        assertTrue(s.blockedBy.single().startsWith("Z03"))
        assertNull(ScanDiagnostics.lastSignal(listOf(log(20, true, coins[2]))))
    }

    // ---- Backtest tarafındaki yeni seçenekler ----
    private fun dataset(symbol: String) = HistoricalDataset(symbol, Fixtures.period(symbol, "m15"), Fixtures.period(symbol, "h1"), Fixtures.period(symbol, "h4"))
    private val data = mapOf("NEARUSDT" to dataset("NEARUSDT"), "BTCUSDT" to dataset("BTCUSDT"))
    private val info = mapOf("NEARUSDT" to TestData.symbolInfo("NEARUSDT", "NEAR"), "BTCUSDT" to TestData.symbolInfo("BTCUSDT", "BTC"))
    private val base = BacktestConfig(listOf("NEARUSDT", "BTCUSDT"), 1_789_689_600_000L, data.getValue("NEARUSDT").m15.last().closeTime, scanner = ScannerConfig(minQuoteVolume24h = 100_000.0))

    @Test
    fun `per strategy totals always add up to the overall result, including strategy C`() {
        val r = BacktestEngine(base.copy(enabledStrategies = StrategyId.entries.toSet())).run(data, info)
        assertTrue(r.trades > 0)
        assertEquals(r.trades, r.perStrategy.sumOf { it.stats.trades })
        assertEquals(r.finalEquity - r.initialCapital, r.perStrategy.sumOf { it.stats.netPnl }, 1e-6)
    }

    @Test
    fun `measured spreads apply the live spread filter and higher costs in the backtest`() {
        val plain = BacktestEngine(base).run(data, info)
        // %0,30 spread (> %0,20 sınırı): coin canlıdaki gibi taramada elenir, işlem açılmaz.
        val wide = BacktestEngine(base.copy(halfSpreadBySymbol = mapOf("NEARUSDT" to 0.0015, "BTCUSDT" to 0.0015))).run(data, info)
        assertTrue(plain.trades > 0)
        assertEquals(0, wide.trades)
        assertTrue(wide.measuredSpread)
        assertFalse(plain.measuredSpread)
        // Sınırın altında ama sabit değerden geniş spread: işlem başına maliyet artar.
        val costly = BacktestEngine(base.copy(halfSpreadBySymbol = mapOf("NEARUSDT" to 0.0009, "BTCUSDT" to 0.0009))).run(data, info)
        if (costly.trades > 0) assertTrue(costly.spreadAndSlippageCost / costly.trades > plain.spreadAndSlippageCost / plain.trades)
    }

    @Test
    fun `entry filters are off by default and act as extra mandatory gates when enabled`() {
        val plain = BacktestEngine(base).run(data, info)
        val same = BacktestEngine(base.copy(strategyParams = StrategyParams(entryFilters = emptyMap()))).run(data, info)
        assertEquals(plain.closedTrades.map { it.netPnl }, same.closedTrades.map { it.netPnl })
        val filtered = BacktestEngine(base.copy(strategyParams = StrategyParams(entryFilters = mapOf(StrategyId.TREND_PULLBACK to setOf("F3"), StrategyId.BREAKOUT_RETEST to setOf("F3"))))).run(data, info)
        assertTrue(filtered.blockedByRule.keys.any { it.startsWith("F3") })
        // Filtreden geçen her işlem koşulu sağlar (15M hacim ≥ 1,5× ortalama).
        assertTrue(filtered.entryFeatures.all { it.volumeRatio15m >= 1.5 })
    }
}
