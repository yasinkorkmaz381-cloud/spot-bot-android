package com.spotbot.core.analysis

import com.spotbot.core.model.Candle
import kotlinx.serialization.Serializable

enum class Regime { TREND_UP, TREND_DOWN, RANGE, UNCLEAR }

/** Göstergelerin belirli bir kapanmış mumdaki değerleri (panelde ve kayıtlarda gösterilir). */
@Serializable
data class IndicatorSnapshot(
    val close: Double,
    val emaFast: Double,
    val emaMid: Double,
    val emaSlow: Double,
    val emaTrend: Double,
    val rsi: Double,
    val macd: Double,
    val macdSignal: Double,
    val macdHistogram: Double,
    val atr: Double,
    val adx: Double,
    val plusDi: Double,
    val minusDi: Double,
    val superTrendUp: Boolean,
    val superTrendLine: Double,
    val vwap: Double,
    val volume: Double,
    val volumeAverage: Double,
)

/** Bir zaman diliminin [endIndex] numaralı kapanmış mumu itibarıyla analizi. Gelecek veriye bakmaz. */
class TimeframeAnalysis(val series: Series, val endIndex: Int) {
    private val config = series.config
    val timeframe get() = series.timeframe
    val isValid: Boolean get() = endIndex >= 0 && endIndex < series.size
    val candle: Candle get() = series.candles[endIndex]
    private val from: Int get() = maxOf(0, endIndex - config.structureLookback)

    val indicators: IndicatorSnapshot by lazy { snapshotAt(endIndex) }
    val previous: IndicatorSnapshot? by lazy { if (endIndex > 0) snapshotAt(endIndex - 1) else null }

    fun snapshotAt(i: Int): IndicatorSnapshot = with(series) {
        IndicatorSnapshot(
            close = close[i], emaFast = emaFast[i], emaMid = emaMid[i], emaSlow = emaSlow[i], emaTrend = emaTrend[i],
            rsi = rsi[i], macd = macd.line[i], macdSignal = macd.signal[i], macdHistogram = macd.histogram[i],
            atr = atr[i], adx = adx.adx[i], plusDi = adx.plusDi[i], minusDi = adx.minusDi[i],
            superTrendUp = superTrend.direction[i] == 1, superTrendLine = superTrend.line[i],
            vwap = vwap[i], volume = volume[i], volumeAverage = volumeAverage[i],
        )
    }

    /** [endIndex] itibarıyla onaylanmış ve geriye bakış penceresindeki swingler. */
    val swings: List<SwingPoint> by lazy {
        series.confirmedSwings(from, endIndex)
    }

    val structure: StructureState by lazy { Structure.structure(swings, series.close, endIndex) }

    val levels: List<Level> by lazy {
        Structure.levels(swings, indicators.atr * config.levelToleranceAtr, indicators.close)
    }

    val supports: List<Level> get() = levels.filter { it.kind == LevelKind.SUPPORT }.sortedByDescending { it.price }
    val resistances: List<Level> get() = levels.filter { it.kind == LevelKind.RESISTANCE }.sortedBy { it.price }

    val fairValueGaps: List<FairValueGap> by lazy {
        Structure.fairValueGaps(series.high, series.low, from, endIndex)
    }

    val orderBlocks: List<OrderBlock> by lazy {
        Structure.bullishOrderBlocks(swings, series.open, series.high, series.low, series.close, from, endIndex)
    }

    val liquidityPools: List<LiquidityPool> by lazy {
        Structure.liquidityPools(swings, indicators.atr * config.equalLevelToleranceAtr, series.high, series.low, endIndex)
    }

    /** Piyasa rejimi: EMA dizilimi, SuperTrend, ADX ve piyasa yapısının birlikte değerlendirilmesi. */
    val regime: Regime by lazy {
        val s = indicators
        if (s.adx.isNaN() || s.emaSlow.isNaN()) return@lazy Regime.UNCLEAR
        val bullAligned = s.close > s.emaSlow && s.emaMid > s.emaSlow && s.superTrendUp
        val bearAligned = s.close < s.emaSlow && s.emaMid < s.emaSlow && !s.superTrendUp
        when {
            bullAligned && s.adx >= config.trendAdxMin && structure.trend != Trend.DOWN -> Regime.TREND_UP
            bearAligned && s.adx >= config.trendAdxMin && structure.trend != Trend.UP -> Regime.TREND_DOWN
            s.adx < config.rangeAdxMax -> Regime.RANGE
            else -> Regime.UNCLEAR
        }
    }

    fun averageVolume(lastBars: Int): Double {
        val start = maxOf(0, endIndex - lastBars + 1)
        return (start..endIndex).sumOf { series.volume[it] } / (endIndex - start + 1)
    }
}

/** Bir parite için çoklu zaman dilimi görünümü (4H ana trend, 1H plan, 15M tetik, 5M yardımcı). */
class MultiTimeframe(
    val symbol: String,
    val h4: TimeframeAnalysis,
    val h1: TimeframeAnalysis,
    val m15: TimeframeAnalysis,
    val m5: TimeframeAnalysis? = null,
) {
    val isValid: Boolean get() = h4.isValid && h1.isValid && m15.isValid &&
        h4.endIndex >= MIN_BARS && h1.endIndex >= MIN_BARS && m15.endIndex >= MIN_BARS

    companion object {
        /** Göstergelerin (EMA 55, MACD, ADX) güvenilir olması için gereken asgari mum sayısı. */
        const val MIN_BARS = 60
    }
}
