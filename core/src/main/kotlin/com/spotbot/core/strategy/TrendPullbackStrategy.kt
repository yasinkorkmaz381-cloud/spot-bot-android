package com.spotbot.core.strategy

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.SwingType
import com.spotbot.core.analysis.Trend
import com.spotbot.core.ta.Indicators

/**
 * Strateji B — Trend Pullback.
 * 4H yükseliş rejimindeki bir coinin 1H'de sağlıklı düzeltme yapıp destek bölgesinde (EMA21-55, S/R, FVG,
 * order block) tutunması ve 15M'de dönüş teyidi vermesi.
 */
class TrendPullbackStrategy(private val params: StrategyParams = StrategyParams()) : Strategy {
    override val id = StrategyId.TREND_PULLBACK
    override val suitableRegimes = setOf(Regime.TREND_UP)

    override fun evaluate(mtf: MultiTimeframe): StrategyResult {
        val reasons = mutableListOf<String>()
        if (mtf.h4.regime != Regime.TREND_UP) return StrategyResult.NotFound(listOf("4H rejimi yükseliş trendi değil (${mtf.h4.regime})"))

        val h1 = mtf.h1
        val s1 = h1.indicators
        val atr = s1.atr
        val series = h1.series
        val end = h1.endIndex
        if (s1.close <= s1.emaSlow) reasons += "1H kapanış EMA55 altında"
        if (h1.structure.trend == Trend.DOWN) reasons += "1H yapı düşüşte (LH/LL)"

        val from = end - params.pullbackLookbackBars + 1
        val highIndex = (from..end).maxByOrNull { series.high[it] }!!
        val swingHigh = series.high[highIndex]
        val pullbackLow = Indicators.lowest(series.low, highIndex, end)
        if (highIndex == end) reasons += "1H henüz düzeltme yapmadı (son mum tepe)"
        if ((swingHigh - pullbackLow) < params.minPullbackAtr * atr) {
            reasons += "Düzeltme derinliği yetersiz (%.1f ATR)".format((swingHigh - pullbackLow) / atr)
        }

        val priorLow = h1.swings.lastOrNull { it.type == SwingType.LOW && it.index < highIndex }
        if (priorLow != null && pullbackLow < priorLow.price) reasons += "Düzeltme önceki yükselen dibi (HL) kırdı"

        val tolerance = params.zoneToleranceAtr * atr
        val confluences = buildList {
            if (pullbackLow <= s1.emaMid + 0.25 * atr && pullbackLow >= s1.emaSlow - tolerance) add("1H EMA21-55 bölgesi")
            h1.supports.firstOrNull { kotlin.math.abs(it.price - pullbackLow) <= tolerance }?.let { add("1H destek (${it.touches} temas)") }
            h1.fairValueGaps.firstOrNull { it.bullish && pullbackLow <= it.top && pullbackLow >= it.bottom - tolerance / 2 }?.let { add("1H FVG") }
            h1.orderBlocks.firstOrNull { pullbackLow <= it.top && pullbackLow >= it.bottom - tolerance / 2 }?.let { add("1H order block") }
        }
        if (confluences.isEmpty()) reasons += "Düzeltme bir destek bölgesine değmedi"

        val trigger = Triggers.bullishTrigger(mtf.m15)
        if (trigger == null) reasons += "15M dönüş tetiği yok"
        val confirmations = Triggers.confirmations(mtf.m15, params)
        if (mtf.m15.candle.low < pullbackLow - 0.1 * atr) reasons += "15M yeni dip yaptı, dönüş teyitsiz"
        if (reasons.isNotEmpty()) return StrategyResult.NotFound(reasons)

        val entry = mtf.m15.candle.close
        val stop = pullbackLow - params.stopBufferAtr * atr
        val target = nearestTargetAbove(
            entry, params.minTargetDistanceAtr * atr, h1, mtf.h4,
            extra = listOf(swingHigh to "Düzeltme öncesi 1H tepe"),
        ) ?: return StrategyResult.NotFound(listOf("Girişin üzerinde yapısal hedef bulunamadı"))

        return StrategyResult.Found(
            TradeSetup(
                symbol = mtf.symbol,
                strategy = id,
                signalTime = mtf.m15.candle.closeTime,
                entryPrice = entry,
                stopPrice = stop,
                targetPrice = target.first,
                invalidationPrice = pullbackLow,
                confirmations = listOf(trigger!!) + confirmations,
                confluences = confluences,
                entryReason = "4H yükseliş trendinde 1H düzeltme ${confluences.joinToString()} bölgesinde tutundu; $trigger.",
                stopReason = "Düzeltme dibinin (%s) %.2f ATR altı.".format(pullbackLow.fmt(), params.stopBufferAtr),
                targetReason = target.second,
                invalidationReason = "1H mum düzeltme dibinin (%s) altında kapanırsa yükselen dip yapısı bozulur.".format(pullbackLow.fmt()),
            ),
        )
    }
}

internal fun Double.fmt(): String = if (this >= 100) "%.2f".format(this) else if (this >= 1) "%.4f".format(this) else "%.6f".format(this)
