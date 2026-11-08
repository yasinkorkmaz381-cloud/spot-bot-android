package com.spotbot.core.strategy

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.TimeframeAnalysis
import kotlinx.serialization.Serializable

@Serializable
enum class StrategyId(val code: String, val title: String) {
    BREAKOUT_RETEST("A", "Breakout + Retest"),
    TREND_PULLBACK("B", "Trend Pullback"),
    RANGE("C", "Yatay Piyasa"),
}

/** Bir stratejinin bulduğu işlem planı. Fiyatlar referans değerlerdir; gerçek giriş dolum fiyatıyla yapılır. */
@Serializable
data class TradeSetup(
    val symbol: String,
    val strategy: StrategyId,
    /** Tetik mumunun (15M) kapanış zamanı. */
    val signalTime: Long,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    /** 1H mum bu seviyenin altında kapanırsa işlem yapısal olarak geçersiz sayılır. */
    val invalidationPrice: Double,
    /** Birbirinden bağımsız giriş teyitleri (hacim, RSI, MACD, VWAP, EMA). */
    val confirmations: List<String>,
    /** Giriş bölgesindeki yapısal uyumlar (FVG, order block, S/R, EMA bölgesi). */
    val confluences: List<String>,
    val entryReason: String,
    val stopReason: String,
    val targetReason: String,
    val invalidationReason: String,
)

sealed interface StrategyResult {
    data class Found(val setup: TradeSetup) : StrategyResult
    data class NotFound(val reasons: List<String>) : StrategyResult
}

interface Strategy {
    val id: StrategyId
    val suitableRegimes: Set<Regime>
    fun evaluate(mtf: MultiTimeframe): StrategyResult
}

/** Strateji eşikleri. Değerler test başlangıç ayarlarıdır; backtest sonuçlarına göre değiştirilecek. */
@Serializable
data class StrategyParams(
    val minConfirmations: Int = 2,
    val volumeConfirmMultiplier: Double = 1.2,
    val pullbackLookbackBars: Int = 12,
    val minPullbackAtr: Double = 1.0,
    val zoneToleranceAtr: Double = 0.5,
    val stopBufferAtr: Double = 0.25,
    val minTargetDistanceAtr: Double = 0.5,
    val breakoutLookbackBars: Int = 12,
    val breakoutLevelLookbackBars: Int = 80,
    val breakoutBufferAtr: Double = 0.1,
    val breakoutVolumeMultiplier: Double = 1.3,
    val retestToleranceAtr: Double = 0.3,
    val rangeMinWidthAtr: Double = 3.0,
    val rangeMinTouches: Int = 2,
)

/** 15M tetik mumu ve teyit kontrolleri; tüm stratejilerde ortak. */
object Triggers {

    /** Kapanmış son 15M mumu yükseliş tetiği mi: önceki tepenin üzerinde kapanış ya da yutan boğa mumu. */
    fun bullishTrigger(m15: TimeframeAnalysis): String? {
        val i = m15.endIndex
        if (i < 1) return null
        val s = m15.series
        val bullish = s.close[i] > s.open[i]
        if (!bullish) return null
        val engulfing = s.close[i - 1] < s.open[i - 1] && s.close[i] >= s.open[i - 1] && s.open[i] <= s.close[i - 1]
        return when {
            engulfing -> "15M yutan boğa mumu"
            s.close[i] > s.high[i - 1] -> "15M önceki tepenin üzerinde kapanış"
            else -> null
        }
    }

    fun confirmations(m15: TimeframeAnalysis, params: StrategyParams): List<String> {
        val now = m15.indicators
        val prev = m15.previous ?: return emptyList()
        return buildList {
            if (now.volumeAverage > 0 && now.volume >= params.volumeConfirmMultiplier * now.volumeAverage) {
                add("Hacim ortalamanın %.1f katı".format(now.volume / now.volumeAverage))
            }
            if (now.rsi > 50 && now.rsi > prev.rsi) add("RSI 50 üzerinde ve yükseliyor (%.0f)".format(now.rsi))
            if (now.macdHistogram > prev.macdHistogram && now.macd > now.macdSignal) add("MACD sinyal üzerinde, histogram yükseliyor")
            if (now.close > now.vwap) add("Fiyat VWAP üzerinde")
            if (now.emaFast > now.emaMid && now.close > now.emaFast) add("EMA9 > EMA21 ve kapanış EMA9 üzerinde")
        }
    }
}

/** Girişin üzerindeki ilk anlamlı direnci hedef olarak seçer (hedefi R:R tutsun diye uzağa itmez). */
internal fun nearestTargetAbove(
    entry: Double,
    minDistance: Double,
    h1: TimeframeAnalysis,
    h4: TimeframeAnalysis,
    extra: List<Pair<Double, String>> = emptyList(),
): Pair<Double, String>? {
    val candidates = buildList {
        addAll(extra)
        h1.resistances.forEach { add(it.price to "1H direnç (${it.touches} temas)") }
        h1.liquidityPools.filter { it.buySide }.forEach { add(it.price to "1H eşit tepeler (alış likiditesi)") }
        h4.resistances.forEach { add(it.price to "4H direnç (${it.touches} temas)") }
    }
    return candidates.filter { it.first >= entry + minDistance }.minByOrNull { it.first }
}
