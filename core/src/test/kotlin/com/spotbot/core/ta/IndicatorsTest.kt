package com.spotbot.core.ta

import com.spotbot.core.support.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Göstergeler gerçek BTCUSDT verisi üzerinde pandas-ta 0.4.71 sonuçlarıyla karşılaştırılır
 * (RSI/ATR/ADX ayrıca `ta` kütüphanesiyle de çapraz doğrulanmıştır). Son 60 değer kontrol edilir.
 */
class IndicatorsTest {

    private val candles = Fixtures.btc1h
    private val close = candles.map { it.close }.toDoubleArray()
    private val high = candles.map { it.high }.toDoubleArray()
    private val low = candles.map { it.low }.toDoubleArray()
    private val expected = Json.parseToJsonElement(Fixtures.text("/fixtures/BTCUSDT_1H_expected.json")) as JsonObject

    private fun assertMatches(name: String, actual: DoubleArray, relTolerance: Double = 1e-9) {
        val reference = (expected[name] as JsonArray).map { it.jsonPrimitive.doubleOrNull }
        val tail = actual.takeLast(reference.size)
        reference.zip(tail).forEachIndexed { i, (exp, act) ->
            exp ?: return@forEachIndexed
            val error = abs(exp - act) / maxOf(abs(exp), 1e-9)
            assertTrue("$name[$i]: beklenen $exp, bulunan $act", error <= relTolerance)
        }
    }

    @Test fun `ema matches reference`() {
        assertMatches("ema21", Indicators.ema(close, 21))
        assertMatches("ema200", Indicators.ema(close, 200))
    }

    @Test fun `rsi matches reference`() = assertMatches("rsi14", Indicators.rsi(close, 14))

    @Test fun `macd matches reference`() {
        val macd = Indicators.macd(close)
        assertMatches("macd", macd.line)
        assertMatches("macdSignal", macd.signal)
        assertMatches("macdHist", macd.histogram, relTolerance = 1e-7)
    }

    @Test fun `atr matches reference`() = assertMatches("atr14", Indicators.atr(high, low, close, 14))

    @Test fun `adx and directional indicators match reference`() {
        val adx = Indicators.adx(high, low, close, 14)
        assertMatches("adx14", adx.adx, relTolerance = 1e-6)
        assertMatches("plusDi", adx.plusDi, relTolerance = 1e-6)
        assertMatches("minusDi", adx.minusDi, relTolerance = 1e-6)
    }

    @Test fun `supertrend matches reference`() {
        val st = Indicators.superTrend(high, low, close, 10, 3.0)
        assertMatches("stLine", st.line, relTolerance = 1e-9)
        assertMatches("stDir", st.direction.map { it.toDouble() }.toDoubleArray())
    }

    @Test fun `sma and warmup produce NaN until enough data`() {
        val sma = Indicators.sma(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 3)
        assertTrue(sma[0].isNaN() && sma[1].isNaN())
        assertArrayEquals(doubleArrayOf(2.0, 3.0), sma.copyOfRange(2, 4), 1e-12)
    }

    @Test fun `daily vwap resets at UTC midnight`() {
        val day = 24 * 60 * 60_000L
        val candles = listOf(
            com.spotbot.core.model.Candle(day - 3_600_000, 10.0, 12.0, 8.0, 10.0, 1.0, day, 10.0),
            com.spotbot.core.model.Candle(day, 20.0, 22.0, 18.0, 20.0, 3.0, day + 3_600_000, 60.0),
        )
        val vwap = Indicators.dailyVwap(candles)
        assertEquals(10.0, vwap[0], 1e-12)
        assertEquals(20.0, vwap[1], 1e-12)
    }

    @Test fun `correlation of identical and inverted series`() {
        val a = DoubleArray(50) { 100.0 + kotlin.math.sin(it / 3.0) * 5 }
        val inverted = DoubleArray(50) { 10_000.0 / a[it] }
        assertEquals(1.0, Indicators.returnCorrelation(a, a, 40), 1e-9)
        assertEquals(-1.0, Indicators.returnCorrelation(a, inverted, 40), 1e-9)
    }
}
