package com.spotbot.core.scanner

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Series
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.risk.RiskConfig
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.RiskStatus
import com.spotbot.core.trading.StrategyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MarketFilterTest {

    private val config = ScannerConfig()
    /** Son kapanış (4H ve 1H sınırına denk gelen bir zaman). */
    private val end = 2_000L * Timeframe.H4.millis

    private fun series(tf: Timeframe, closes: List<Double>): Series {
        val n = closes.size
        val candles = closes.mapIndexed { i, c ->
            val open = if (i == 0) c else closes[i - 1]
            val openTime = end - (n - i) * tf.millis
            Candle(openTime, open, maxOf(open, c) * 1.002, minOf(open, c) * 0.998, c, 100.0, openTime + tf.millis, 100.0 * c)
        }
        return Series("BTCUSDT", tf, candles)
    }

    /** [step]: mum başına çarpan (1.004 yükseliş, 0.996 düşüş). [h1Tail]: 1H'nin son 4 mumuna eklenen hareket. */
    private fun btc(step: Double, h1Tail: Double = 1.0): MultiTimeframe {
        val closes = List(300) { 100.0 * step.pow(it) }
        val h1 = closes.mapIndexed { i, c -> if (i >= closes.size - 4) c * h1Tail.pow(i - (closes.size - 5)) else c }
        return MultiTimeframe("BTCUSDT", series(Timeframe.H4, closes).analyze(), series(Timeframe.H1, h1).analyze(), series(Timeframe.M15, closes).analyze())
    }

    @Test
    fun `falling BTC blocks entries as a market risk with the exact condition`() {
        val m = Scanner.marketState(btc(0.996), emptyList(), config, boundary = end)
        assertFalse(m.allowsLongs)
        assertEquals(MarketBlockKind.MARKET, m.blockKind)
        assertEquals(listOf("B3"), m.failedConditions.map { it.id })
        assertEquals(Regime.TREND_DOWN, m.btcH4!!.regime)
        assertEquals(end, m.btcH4!!.lastClose)
        assertTrue(MarketExplanation.text(m)!!.startsWith("Piyasa riski: BTC 4 saatlik grafikte düşüş trendinde"))
        // Şartın ayrıntısında filtrenin kullandığı değerler var.
        assertTrue(m.failedConditions.single().detail.contains("EMA55"))
    }

    @Test
    fun `rising and fresh BTC allows entries and records the indicator values`() {
        val m = Scanner.marketState(btc(1.004), emptyList(), config, boundary = end)
        assertTrue(m.allowsLongs)
        assertNull(m.blockKind)
        assertEquals(listOf("B1", "B2", "B3", "B4"), m.conditions.map { it.id })
        assertTrue(m.conditions.all { it.passed })
        assertEquals(Regime.TREND_UP, m.btcH4!!.regime)
        assertNull(MarketExplanation.text(m))
    }

    @Test
    fun `missing BTC data is reported as a data problem, not as market risk`() {
        val m = Scanner.marketState(null, emptyList(), config, boundary = end)
        assertFalse(m.allowsLongs)
        assertEquals(MarketBlockKind.DATA, m.blockKind)
        assertEquals(listOf("B1"), m.failedConditions.map { it.id })
        assertTrue(MarketExplanation.text(m)!!.startsWith("Veri sorunu"))
    }

    @Test
    fun `stale BTC candles block entries even when the old data looked bullish`() {
        // Son mum 4 saat önce kapanmış: eski veriyle karar verilmez (önceden bu durumda alım serbest kalıyordu).
        val m = Scanner.marketState(btc(1.004), emptyList(), config, boundary = end + Timeframe.H4.millis)
        assertFalse(m.allowsLongs)
        assertEquals(MarketBlockKind.DATA, m.blockKind)
        assertEquals(listOf("B2"), m.failedConditions.map { it.id })
        assertTrue(MarketExplanation.text(m)!!.contains("güncel değil"))
    }

    @Test
    fun `sharp four hour drop blocks entries as market risk`() {
        val m = Scanner.marketState(btc(1.004, h1Tail = 0.985), emptyList(), config, boundary = end)
        assertFalse(m.allowsLongs)
        assertEquals(MarketBlockKind.MARKET, m.blockKind)
        assertTrue("B4" in m.failedConditions.map { it.id })
        assertTrue(m.btcChange4hPercent!! <= -3.0)
    }

    @Test
    fun `panel risk status shows the plain explanation and its kind`() {
        val market = Scanner.marketState(btc(0.996), emptyList(), config, boundary = end)
        val status = RiskStatus.of(PortfolioState.create(1_000.0), 1_000.0, RiskConfig(), StrategyProfile.ADAPTIVE, false, market.allowsLongs, emptyList(), market)
        assertEquals(MarketExplanation.text(market), status.entriesBlockedReason)
        assertEquals(MarketBlockKind.MARKET, status.marketBlockKind)
        val dataStatus = RiskStatus.of(
            PortfolioState.create(1_000.0), 1_000.0, RiskConfig(), StrategyProfile.ADAPTIVE, false, false, emptyList(),
            Scanner.marketState(null, emptyList(), config),
        )
        assertEquals(MarketBlockKind.DATA, dataStatus.marketBlockKind)
        assertTrue(dataStatus.entriesBlockedReason!!.contains("bir piyasa sinyali değildir"))
    }
}
