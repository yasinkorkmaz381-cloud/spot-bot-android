package com.spotbot.core.strategy

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.SwingType
import com.spotbot.core.ta.Indicators

/**
 * Strateji A — Breakout + Retest.
 * 1H'de bir swing direncinin hacimli ve teyitli kapanışla kırılması, ardından fiyatın kırılan seviyeye geri
 * dönüp (retest) tutunması ve 15M'de yükseliş tetiği.
 */
class BreakoutRetestStrategy(private val params: StrategyParams = StrategyParams()) : Strategy {
    override val id = StrategyId.BREAKOUT_RETEST
    override val suitableRegimes = setOf(Regime.TREND_UP, Regime.RANGE)

    override fun evaluate(mtf: MultiTimeframe): StrategyResult {
        if (mtf.h4.regime !in suitableRegimes) {
            return StrategyResult.NotFound(listOf("4H rejimi kırılım için uygun değil (${mtf.h4.regime})"))
        }
        val h1 = mtf.h1
        val s = h1.series
        val end = h1.endIndex
        val atr = h1.indicators.atr

        data class Breakout(val level: Double, val levelIndex: Int, val breakIndex: Int, val base: Double)

        val breakouts = h1.swings
            .filter { it.type == SwingType.HIGH && it.index >= end - params.breakoutLevelLookbackBars }
            .mapNotNull { swing ->
                val b = (swing.confirmedAt + 1..end).firstOrNull { s.close[it] > swing.price } ?: return@mapNotNull null
                if (b < end - params.breakoutLookbackBars) return@mapNotNull null
                val strongClose = s.close[b] >= swing.price + params.breakoutBufferAtr * s.atr[b]
                val volumeOk = s.volume[b] >= params.breakoutVolumeMultiplier * s.volumeAverage[b]
                if (!strongClose || !volumeOk) return@mapNotNull null
                Breakout(swing.price, swing.index, b, Indicators.lowest(s.low, swing.index, b))
            }
        if (breakouts.isEmpty()) return StrategyResult.NotFound(listOf("Son ${params.breakoutLookbackBars} 1H mumda hacimli teyitli kırılım yok"))

        val reasons = mutableListOf<String>()
        val breakout = breakouts.maxBy { it.level }
        val level = breakout.level
        val tolerance = params.retestToleranceAtr * atr
        if (breakout.breakIndex == end) reasons += "Kırılım mumu yeni kapandı, retest bekleniyor"
        val afterBreak = (breakout.breakIndex + 1..end)
        val retestLow = if (afterBreak.isEmpty()) Double.NaN else Indicators.lowest(s.low, breakout.breakIndex + 1, end)
        val retested = !retestLow.isNaN() && retestLow <= level + tolerance
        if (!retested) reasons += "Kırılan seviyeye geri dönüş (retest) olmadı"
        if (afterBreak.any { s.close[it] < level - tolerance }) reasons += "Kırılım başarısız: 1H seviyenin altında kapandı"

        val trigger = Triggers.bullishTrigger(mtf.m15)
        if (trigger == null) reasons += "15M yükseliş tetiği yok"
        if (mtf.m15.candle.close <= level) reasons += "15M kapanış kırılan seviyenin altında"
        if (reasons.isNotEmpty()) return StrategyResult.NotFound(reasons)

        val entry = mtf.m15.candle.close
        val stop = minOf(retestLow, level - tolerance) - params.stopBufferAtr * atr
        val measuredMove = level + (level - breakout.base)
        val target = nearestTargetAbove(entry, params.minTargetDistanceAtr * atr, h1, mtf.h4)
            ?: (measuredMove to "Ölçülü hareket (kırılan aralığın yüksekliği kadar)").takeIf { it.first > entry + params.minTargetDistanceAtr * atr }
            ?: return StrategyResult.NotFound(listOf("Girişin üzerinde hedef bulunamadı"))

        val confluences = buildList {
            add("Kırılan 1H direnç desteğe döndü")
            h1.fairValueGaps.firstOrNull { it.bullish && it.bottom <= level + tolerance && it.top >= level - tolerance }?.let { add("1H FVG") }
            h1.orderBlocks.firstOrNull { it.bottom <= level + tolerance && it.top >= level - tolerance }?.let { add("1H order block") }
            if (kotlin.math.abs(h1.indicators.emaMid - level) <= tolerance) add("1H EMA21")
        }
        return StrategyResult.Found(
            TradeSetup(
                symbol = mtf.symbol,
                strategy = id,
                signalTime = mtf.m15.candle.closeTime,
                entryPrice = entry,
                stopPrice = stop,
                targetPrice = target.first,
                invalidationPrice = level - tolerance,
                confirmations = listOf(trigger!!) + Triggers.confirmations(mtf.m15, params),
                confluences = confluences,
                entryReason = "1H direnç ${level.fmt()} hacimli kapanışla kırıldı, retest edildi; $trigger.",
                stopReason = "Retest dibi ile seviyenin altındaki tolerans bölgesinin %.2f ATR altı.".format(params.stopBufferAtr),
                targetReason = target.second,
                invalidationReason = "1H mum kırılan seviyenin (${level.fmt()}) altında kalıcı kapanırsa kırılım başarısız sayılır.",
            ),
        )
    }
}
