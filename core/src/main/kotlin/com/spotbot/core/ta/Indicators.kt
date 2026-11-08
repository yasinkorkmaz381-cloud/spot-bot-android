package com.spotbot.core.ta

import com.spotbot.core.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Teknik göstergeler. Hepsi nedenseldir (i. değer yalnızca 0..i verisine bakar) ve ısınma süresince NaN döner.
 * Tanımlar yaygın platformlarla (TradingView/TA-Lib) uyumludur: EMA ve Wilder ortalamaları ilk değerini
 * basit ortalamayla başlatır.
 */
object Indicators {

    fun sma(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        var count = 0
        for (i in values.indices) {
            val v = values[i]
            if (v.isNaN()) { sum = 0.0; count = 0; continue }
            sum += v
            count++
            if (count > period) sum -= values[i - period]
            if (count >= period) out[i] = sum / period
        }
        return out
    }

    fun ema(values: DoubleArray, period: Int): DoubleArray = smoothed(values, period, 2.0 / (period + 1))

    /** Wilder (RMA) ortalaması: alpha = 1/period. */
    fun rma(values: DoubleArray, period: Int): DoubleArray = smoothed(values, period, 1.0 / period)

    private fun smoothed(values: DoubleArray, period: Int, alpha: Double): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        val start = values.indexOfFirst { !it.isNaN() }
        if (start < 0 || start + period > values.size) return out
        var prev = (start until start + period).sumOf { values[it] } / period
        out[start + period - 1] = prev
        for (i in start + period until values.size) {
            prev = alpha * values[i] + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    fun rsi(close: DoubleArray, period: Int = 14): DoubleArray {
        val gains = DoubleArray(close.size) { Double.NaN }
        val losses = DoubleArray(close.size) { Double.NaN }
        for (i in 1 until close.size) {
            val change = close[i] - close[i - 1]
            gains[i] = max(change, 0.0)
            losses[i] = max(-change, 0.0)
        }
        val avgGain = rma(gains, period)
        val avgLoss = rma(losses, period)
        return DoubleArray(close.size) { i ->
            val g = avgGain[i]
            val l = avgLoss[i]
            when {
                g.isNaN() || l.isNaN() -> Double.NaN
                l == 0.0 -> if (g == 0.0) 50.0 else 100.0
                else -> 100 - 100 / (1 + g / l)
            }
        }
    }

    class Macd(val line: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray)

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd {
        val fastEma = ema(close, fast)
        val slowEma = ema(close, slow)
        val line = DoubleArray(close.size) { fastEma[it] - slowEma[it] }
        val signal = ema(line, signalPeriod)
        return Macd(line, signal, DoubleArray(close.size) { line[it] - signal[it] })
    }

    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray): DoubleArray =
        DoubleArray(close.size) { i ->
            if (i == 0) high[0] - low[0]
            else max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
        }

    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): DoubleArray =
        rma(trueRange(high, low, close), period)

    class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    fun adx(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 14): Adx {
        val n = close.size
        val plusDm = DoubleArray(n) { Double.NaN }
        val minusDm = DoubleArray(n) { Double.NaN }
        val tr = DoubleArray(n) { Double.NaN }
        val fullTr = trueRange(high, low, close)
        for (i in 1 until n) {
            val up = high[i] - high[i - 1]
            val down = low[i - 1] - low[i]
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
            tr[i] = fullTr[i]
        }
        val smTr = rma(tr, period)
        val smPlus = rma(plusDm, period)
        val smMinus = rma(minusDm, period)
        val plusDi = DoubleArray(n) { if (smTr[it] > 0) 100 * smPlus[it] / smTr[it] else Double.NaN }
        val minusDi = DoubleArray(n) { if (smTr[it] > 0) 100 * smMinus[it] / smTr[it] else Double.NaN }
        val dx = DoubleArray(n) { i ->
            val sum = plusDi[i] + minusDi[i]
            if (sum.isNaN()) Double.NaN else if (sum == 0.0) 0.0 else 100 * abs(plusDi[i] - minusDi[i]) / sum
        }
        return Adx(rma(dx, period), plusDi, minusDi)
    }

    /** SuperTrend: [direction] +1 yükseliş, -1 düşüş. ATR Wilder ortalamasıdır (TradingView `ta.supertrend`). */
    class SuperTrend(val line: DoubleArray, val direction: IntArray)

    fun superTrend(high: DoubleArray, low: DoubleArray, close: DoubleArray, period: Int = 10, multiplier: Double = 3.0): SuperTrend {
        val n = close.size
        val atr = atr(high, low, close, period)
        val line = DoubleArray(n) { Double.NaN }
        val direction = IntArray(n)
        var upper = Double.NaN
        var lower = Double.NaN
        for (i in 0 until n) {
            if (atr[i].isNaN()) continue
            val hl2 = (high[i] + low[i]) / 2
            val basicUpper = hl2 + multiplier * atr[i]
            val basicLower = hl2 - multiplier * atr[i]
            val prevClose = if (i > 0) close[i - 1] else close[i]
            val newLower = if (lower.isNaN() || basicLower > lower || prevClose < lower) basicLower else lower
            val newUpper = if (upper.isNaN() || basicUpper < upper || prevClose > upper) basicUpper else upper
            val prevDirection = if (i > 0) direction[i - 1] else 0
            direction[i] = when {
                prevDirection == 0 -> if (close[i] > newUpper) 1 else -1
                prevDirection == -1 && close[i] > newUpper -> 1
                prevDirection == 1 && close[i] < newLower -> -1
                else -> prevDirection
            }
            line[i] = if (direction[i] == 1) newLower else newUpper
            lower = newLower
            upper = newUpper
        }
        return SuperTrend(line, direction)
    }

    /** Günlük (UTC gün başında sıfırlanan) VWAP; tipik fiyat (H+L+C)/3 ile hesaplanır. */
    fun dailyVwap(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size) { Double.NaN }
        var day = Long.MIN_VALUE
        var pv = 0.0
        var vol = 0.0
        candles.forEachIndexed { i, c ->
            val d = Math.floorDiv(c.openTime, DAY_MILLIS)
            if (d != day) { day = d; pv = 0.0; vol = 0.0 }
            pv += (c.high + c.low + c.close) / 3 * c.volume
            vol += c.volume
            out[i] = if (vol > 0) pv / vol else c.close
        }
        return out
    }

    fun highest(values: DoubleArray, from: Int, toInclusive: Int): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in max(0, from)..min(values.lastIndex, toInclusive)) m = max(m, values[i])
        return m
    }

    fun lowest(values: DoubleArray, from: Int, toInclusive: Int): Double {
        var m = Double.POSITIVE_INFINITY
        for (i in max(0, from)..min(values.lastIndex, toInclusive)) m = min(m, values[i])
        return m
    }

    /** İki serinin log getirilerinin Pearson korelasyonu (son [window] çubuk). */
    fun returnCorrelation(a: DoubleArray, b: DoubleArray, window: Int): Double {
        val n = min(min(a.size, b.size) - 1, window)
        if (n < 10) return Double.NaN
        val ra = DoubleArray(n) { kotlin.math.ln(a[a.size - n + it] / a[a.size - n + it - 1]) }
        val rb = DoubleArray(n) { kotlin.math.ln(b[b.size - n + it] / b[b.size - n + it - 1]) }
        val ma = ra.average()
        val mb = rb.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in 0 until n) {
            cov += (ra[i] - ma) * (rb[i] - mb)
            va += (ra[i] - ma) * (ra[i] - ma)
            vb += (rb[i] - mb) * (rb[i] - mb)
        }
        return if (va == 0.0 || vb == 0.0) 0.0 else cov / kotlin.math.sqrt(va * vb)
    }

    private const val DAY_MILLIS = 24 * 60 * 60_000L
}
