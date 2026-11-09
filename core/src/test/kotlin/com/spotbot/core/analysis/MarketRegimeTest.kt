package com.spotbot.core.analysis

import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.strategy.StrategyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class MarketRegimeTest {

    private val h4 = Timeframe.H4.millis

    private fun series(closes: List<Double>, range: (Int) -> Double = { 0.004 }): Series {
        val candles = closes.mapIndexed { i, c ->
            val open = if (i == 0) c else closes[i - 1]
            val r = c * range(i)
            Candle(i * h4, open, maxOf(open, c) + r, minOf(open, c) - r, c, 100.0, (i + 1) * h4, 100.0 * c)
        }
        return Series("BTCUSDT", Timeframe.H4, candles)
    }

    private fun classify(s: Series) = RegimeClassifier.classify(s.analyze())

    @Test
    fun `steady rise is a strong uptrend and steady fall a strong downtrend`() {
        assertEquals(MarketRegime.STRONG_UP, classify(series(List(300) { 100.0 * Math.pow(1.004, it.toDouble()) })))
        assertEquals(MarketRegime.STRONG_DOWN, classify(series(List(300) { 100.0 * Math.pow(0.996, it.toDouble()) })))
    }

    @Test
    fun `sideways oscillation is range`() {
        assertEquals(MarketRegime.RANGE, classify(series(List(300) { 100.0 + 2 * sin(it / 3.0) })))
    }

    @Test
    fun `a shock candle far beyond normal range is extreme volatility`() {
        val closes = List(300) { 100.0 * Math.pow(1.004, it.toDouble()) }
        val shocked = series(closes) { if (it == 299) 0.08 else 0.004 }
        assertEquals(MarketRegime.EXTREME_VOLATILITY, classify(shocked))
    }

    @Test
    fun `classification uses only bars up to the analysed index`() {
        val closes = List(300) { 100.0 * Math.pow(1.004, it.toDouble()) } + List(100) { 100.0 * Math.pow(1.004, 299.0) * Math.pow(0.99, it.toDouble()) }
        val s = series(closes)
        val truncated = series(closes.take(300))
        assertEquals(RegimeClassifier.classify(truncated.analyze(299)), RegimeClassifier.classify(s.analyze(299)))
    }

    @Test
    fun `too little history gives no regime`() {
        assertNull(classify(series(List(10) { 100.0 })))
    }

    @Test
    fun `policies allow trades as documented`() {
        assertTrue(RegimePolicy.OFF.allows(MarketRegime.STRONG_DOWN, StrategyId.BREAKOUT_RETEST))
        assertFalse(RegimePolicy.PROTECTIVE.allows(MarketRegime.EXTREME_VOLATILITY, StrategyId.TREND_PULLBACK))
        assertTrue(RegimePolicy.PROTECTIVE.allows(MarketRegime.RANGE, StrategyId.TREND_PULLBACK))
        assertTrue(RegimePolicy.FULL.allows(MarketRegime.STRONG_UP, StrategyId.BREAKOUT_RETEST))
        assertFalse(RegimePolicy.FULL.allows(MarketRegime.WEAK_UP, StrategyId.BREAKOUT_RETEST))
        assertTrue(RegimePolicy.FULL.allows(MarketRegime.WEAK_UP, StrategyId.TREND_PULLBACK))
        assertFalse(RegimePolicy.FULL.allows(null, StrategyId.TREND_PULLBACK))
    }
}
