package com.spotbot.core.analysis

import com.spotbot.core.model.Timeframe
import kotlinx.serialization.Serializable

/** Gösterge ve yapı analizi parametreleri. Değerler test başlangıç ayarlarıdır; backtest ile optimize edilir. */
@Serializable
data class AnalysisConfig(
    val emaFast: Int = 9,
    val emaMid: Int = 21,
    val emaSlow: Int = 55,
    val emaTrend: Int = 200,
    val rsiPeriod: Int = 14,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val atrPeriod: Int = 14,
    val adxPeriod: Int = 14,
    val superTrendPeriod: Int = 10,
    val superTrendMultiplier: Double = 3.0,
    val volumeAveragePeriod: Int = 20,
    /** Swing (pivot) tespiti için her iki yandaki mum sayısı; 15M'de daha kısa. */
    val swingStrengthHigherTf: Int = 3,
    val swingStrengthLowerTf: Int = 2,
    /** Yapı ve seviye analizinde geriye bakılan mum sayısı. */
    val structureLookback: Int = 150,
    /** S/R kümeleme toleransı (ATR katı). */
    val levelToleranceAtr: Double = 0.5,
    /** Eşit tepe/dip (likidite) toleransı (ATR katı). */
    val equalLevelToleranceAtr: Double = 0.15,
    val trendAdxMin: Double = 20.0,
    val rangeAdxMax: Double = 20.0,
) {
    fun swingStrength(timeframe: Timeframe): Int =
        if (timeframe == Timeframe.M5 || timeframe == Timeframe.M15) swingStrengthLowerTf else swingStrengthHigherTf
}
