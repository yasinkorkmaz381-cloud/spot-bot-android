package com.spotbot.core.strategy

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.ta.Indicators

/**
 * Strateji C — Yatay Piyasa (destek dönüşü). Bant kırılımı Strateji A ile yakalanır.
 * Tasarım notu gereği ayrı backtest yapılmadan etkinleştirilmez; varsayılan olarak kapalıdır.
 */
class RangeStrategy(private val params: StrategyParams = StrategyParams()) : Strategy {
    override val id = StrategyId.RANGE
    override val suitableRegimes = setOf(Regime.RANGE)

    override fun evaluate(mtf: MultiTimeframe): StrategyResult {
        val h1 = mtf.h1
        val atr = h1.indicators.atr
        val reasons = mutableListOf<String>()
        if (mtf.h4.regime == Regime.TREND_DOWN) reasons += "4H düşüş trendinde"
        if (h1.indicators.adx >= 20) reasons += "1H ADX yatay piyasa için yüksek (%.0f)".format(h1.indicators.adx)

        val support = h1.supports.firstOrNull { it.touches >= params.rangeMinTouches }
        val resistance = h1.resistances.firstOrNull { it.touches >= params.rangeMinTouches }
        if (support == null || resistance == null) return StrategyResult.NotFound(reasons + "En az ${params.rangeMinTouches} temaslı destek/direnç bandı yok")
        if (resistance.price - support.price < params.rangeMinWidthAtr * atr) reasons += "Bant çok dar"

        val m15 = mtf.m15
        val recentLow = Indicators.lowest(m15.series.low, m15.endIndex - 3, m15.endIndex)
        val tolerance = params.zoneToleranceAtr * atr
        if (recentLow > support.price + tolerance) reasons += "Fiyat desteğe yaklaşmadı"
        if (m15.candle.close <= support.price) reasons += "15M kapanış desteğin altında"
        val trigger = Triggers.bullishTrigger(m15)
        if (trigger == null) reasons += "15M dönüş tetiği yok"
        if (reasons.isNotEmpty()) return StrategyResult.NotFound(reasons)

        return StrategyResult.Found(
            TradeSetup(
                symbol = mtf.symbol,
                strategy = id,
                signalTime = m15.candle.closeTime,
                entryPrice = m15.candle.close,
                stopPrice = minOf(recentLow, support.price) - 2 * params.stopBufferAtr * atr,
                targetPrice = resistance.price - 0.2 * atr,
                invalidationPrice = support.price - tolerance,
                confirmations = listOf(trigger!!) + Triggers.confirmations(m15, params),
                confluences = listOf("1H bant desteği (${support.touches} temas)"),
                entryReason = "Yatay bantta ${support.price.fmt()} desteğinden dönüş; $trigger.",
                stopReason = "Bant desteğinin ve son dibin altında.",
                targetReason = "Bant direncinin (${resistance.price.fmt()}) hemen altı.",
                invalidationReason = "1H mum bant desteğinin altında kapanırsa bant bozulur.",
            ),
        )
    }
}
