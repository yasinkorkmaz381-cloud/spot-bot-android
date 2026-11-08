package com.spotbot.core.analysis

import com.spotbot.core.model.Timeframe
import com.spotbot.core.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureTest {

    /** Yükselen zikzak: dipler ve tepeler giderek yükselir. */
    private val uptrend = listOf(
        100, 102, 104, 106, 104, 102, 101, 103, 105, 108, 110, 108, 106, 105, 107, 110, 113, 115, 112, 110, 109, 112, 116, 119,
    ).map { it.toDouble() }

    private val downtrend = uptrend.map { 220.0 - it }

    @Test
    fun `swing is only known after the right side bars close`() {
        val series = Series("X", Timeframe.H1, Fixtures.candles(uptrend))
        val peak = series.swings.first { it.type == SwingType.HIGH }

        assertEquals(3, peak.index)
        assertEquals(peak.index + 3, peak.confirmedAt)
        assertTrue(series.analyze(peak.confirmedAt - 1).swings.none { it.index == peak.index })
        assertTrue(series.analyze(peak.confirmedAt).swings.any { it.index == peak.index })
    }

    @Test
    fun `labels higher highs and higher lows as an uptrend`() {
        val structure = Series("X", Timeframe.H1, Fixtures.candles(uptrend)).analyze().structure

        assertEquals(SwingLabel.HH, structure.lastHighLabel)
        assertEquals(SwingLabel.HL, structure.lastLowLabel)
        assertEquals(Trend.UP, structure.trend)
        assertTrue(structure.lastBos!!.bullish)
    }

    @Test
    fun `labels lower highs and lower lows as a downtrend`() {
        val structure = Series("X", Timeframe.H1, Fixtures.candles(downtrend)).analyze().structure

        assertEquals(SwingLabel.LH, structure.lastHighLabel)
        assertEquals(SwingLabel.LL, structure.lastLowLabel)
        assertEquals(Trend.DOWN, structure.trend)
        assertFalse(structure.lastBos!!.bullish)
    }

    @Test
    fun `detects an unfilled bullish fair value gap and drops it once filled`() {
        val closes = listOf(100.0, 100.0, 100.0, 103.0, 106.0, 107.0, 107.5)
        val candles = Fixtures.candles(closes, wick = 0.1)
        val series = Series("X", Timeframe.H1, candles)

        // Güçlü yükselişte art arda birkaç FVG oluşur; ilki 3. ve 5. mumlar arasındadır.
        val gap = Structure.fairValueGaps(series.high, series.low, 0, series.size - 1).first { it.bullish }
        assertEquals(4, gap.index)
        assertEquals(100.1, gap.bottom, 1e-9) // 3. mumun tepesi
        assertEquals(102.9, gap.top, 1e-9) // 5. mumun dibi

        val filled = Series("X", Timeframe.H1, candles + Fixtures.candles(listOf(99.0), start = candles.last().closeTime))
        assertTrue(Structure.fairValueGaps(filled.high, filled.low, 0, filled.size - 1).none { it.bullish && it.index == gap.index })
    }

    @Test
    fun `bullish order block is the last down candle before the breaking move`() {
        val series = Series("X", Timeframe.H1, Fixtures.candles(uptrend))
        val blocks = series.analyze().orderBlocks

        assertTrue(blocks.isNotEmpty())
        blocks.forEach { block ->
            assertTrue(series.close[block.index] < series.open[block.index])
            assertTrue(block.bottom < block.top)
        }
    }

    @Test
    fun `equal highs form a buy side liquidity pool until swept`() {
        val closes = listOf(100, 103, 106, 103, 100, 97, 100, 103, 106, 103, 100, 98, 99).map { it.toDouble() }
        val series = Series("X", Timeframe.M15, Fixtures.candles(closes, Timeframe.M15, wick = 0.0))
        val pools = Structure.liquidityPools(series.swings, tolerance = 0.5, series.high, series.low, series.size - 1)

        val buySide = pools.single { it.buySide }
        assertEquals(106.0, buySide.price, 1e-9)
        assertEquals(2, buySide.count)

        val swept = Series("X", Timeframe.M15, Fixtures.candles(closes + listOf(107.0), Timeframe.M15, wick = 0.0))
        assertNull(Structure.liquidityPools(swept.swings, 0.5, swept.high, swept.low, swept.size - 1).firstOrNull { it.buySide })
    }

    @Test
    fun `levels cluster nearby swings and classify by current price`() {
        val swings = listOf(
            SwingPoint(1, 100.0, SwingType.LOW, 3),
            SwingPoint(5, 100.3, SwingType.LOW, 7),
            SwingPoint(9, 110.0, SwingType.HIGH, 11),
        )
        val levels = Structure.levels(swings, tolerance = 0.5, currentPrice = 105.0)

        val support = levels.single { it.kind == LevelKind.SUPPORT }
        assertEquals(2, support.touches)
        assertEquals(100.15, support.price, 1e-9)
        assertNotNull(levels.single { it.kind == LevelKind.RESISTANCE && it.price == 110.0 })
    }

    @Test
    fun `analysis at a past index equals analysis of the truncated series`() {
        val full = Series("BTCUSDT", Timeframe.H1, Fixtures.btc1h)
        listOf(150, 260, 371, 480).forEach { end ->
            val truncated = Series("BTCUSDT", Timeframe.H1, Fixtures.btc1h.take(end + 1))
            val a = full.analyze(end)
            val b = truncated.analyze(end)
            assertEquals("gösterge @ $end", a.indicators, b.indicators)
            assertEquals("swing @ $end", a.swings, b.swings)
            assertEquals("yapı @ $end", a.structure, b.structure)
            assertEquals("seviye @ $end", a.levels, b.levels)
            assertEquals("FVG @ $end", a.fairValueGaps, b.fairValueGaps)
            assertEquals("OB @ $end", a.orderBlocks, b.orderBlocks)
            assertEquals("likidite @ $end", a.liquidityPools, b.liquidityPools)
            assertEquals("rejim @ $end", a.regime, b.regime)
        }
    }

    @Test
    fun `index lookup only returns candles closed at the given time`() {
        val series = Series("X", Timeframe.H1, Fixtures.candles(uptrend, start = 0))
        assertEquals(-1, series.indexAtOrBefore(Timeframe.H1.millis - 1))
        assertEquals(0, series.indexAtOrBefore(Timeframe.H1.millis))
        assertEquals(1, series.indexAtOrBefore(Timeframe.H1.millis * 2 + 5))
    }
}
