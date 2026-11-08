package com.spotbot.core.analysis

import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.ta.Indicators

/**
 * Bir paritenin tek zaman dilimindeki kapanmış mumları ve önceden hesaplanmış göstergeleri.
 * Göstergeler nedensel olduğundan aynı seri hem canlıda (son indeks) hem backtestte (geçmiş indeks) kullanılır.
 */
class Series(val symbol: String, val timeframe: Timeframe, val candles: List<Candle>, val config: AnalysisConfig = AnalysisConfig()) {
    val size: Int get() = candles.size
    val open = DoubleArray(size) { candles[it].open }
    val high = DoubleArray(size) { candles[it].high }
    val low = DoubleArray(size) { candles[it].low }
    val close = DoubleArray(size) { candles[it].close }
    val volume = DoubleArray(size) { candles[it].volume }

    val emaFast by lazy { Indicators.ema(close, config.emaFast) }
    val emaMid by lazy { Indicators.ema(close, config.emaMid) }
    val emaSlow by lazy { Indicators.ema(close, config.emaSlow) }
    val emaTrend by lazy { Indicators.ema(close, config.emaTrend) }
    val rsi by lazy { Indicators.rsi(close, config.rsiPeriod) }
    val macd by lazy { Indicators.macd(close, config.macdFast, config.macdSlow, config.macdSignal) }
    val atr by lazy { Indicators.atr(high, low, close, config.atrPeriod) }
    val adx by lazy { Indicators.adx(high, low, close, config.adxPeriod) }
    val superTrend by lazy { Indicators.superTrend(high, low, close, config.superTrendPeriod, config.superTrendMultiplier) }
    val vwap by lazy { Indicators.dailyVwap(candles) }
    val volumeAverage by lazy { Indicators.sma(volume, config.volumeAveragePeriod) }

    /** Tüm seri için bir kez hesaplanan pivotlar; her birinin onaylandığı indeks bilinir. */
    val swings: List<SwingPoint> by lazy { Structure.findSwings(high, low, config.swingStrength(timeframe)) }

    /** [closeTime] anında kapanmış son mumun indeksi (yoksa -1). */
    fun indexAtOrBefore(closeTime: Long): Int {
        var lo = 0
        var hi = size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (candles[mid].closeTime <= closeTime) { result = mid; lo = mid + 1 } else hi = mid - 1
        }
        return result
    }

    fun analyze(endIndex: Int = size - 1): TimeframeAnalysis = TimeframeAnalysis(this, endIndex)
}
