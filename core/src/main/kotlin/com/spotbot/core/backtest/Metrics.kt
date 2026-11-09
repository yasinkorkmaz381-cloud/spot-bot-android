package com.spotbot.core.backtest

import com.spotbot.core.analysis.MarketRegime
import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.RegimeClassifier
import com.spotbot.core.analysis.SwingType
import com.spotbot.core.analysis.Trend
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.trading.ClosedTrade
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.max

/** İşlem listesinden hesaplanan performans ölçüleri (kazanç/kayıp net kâr/zarara göre, ücretler dahil). */
@Serializable
data class PerformanceStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: Double,
    /** Kazançlı işlemlerin net kârları toplamı (ücretler düşülmüş). */
    val grossProfit: Double,
    /** Zararlı işlemlerin net zararları toplamı (pozitif sayı, ücretler dahil). */
    val grossLoss: Double,
    val netPnl: Double,
    val netReturnPercent: Double,
    val profitFactor: Double,
    val averageWin: Double,
    val averageLoss: Double,
    /** İşlem başına beklenen değer (USDT). */
    val expectancy: Double,
    val averageR: Double,
    /** Gerçekleşen işlem sırasına göre en büyük düşüş (%); tüm portföy için piyasa değerli düşüş raporda ayrıca var. */
    val maxDrawdownPercent: Double,
    val fees: Double,
) {
    companion object {
        fun of(trades: List<ClosedTrade>, capital: Double): PerformanceStats {
            val wins = trades.filter { it.netPnl > 0 }
            val losses = trades.filter { it.netPnl <= 0 }
            val grossProfit = wins.sumOf { it.netPnl }
            val grossLoss = -losses.sumOf { it.netPnl }
            var equity = capital
            var peak = capital
            var maxDd = 0.0
            trades.sortedBy { it.closedAt }.forEach {
                equity += it.netPnl
                peak = max(peak, equity)
                maxDd = max(maxDd, (peak - equity) / peak * 100)
            }
            val net = grossProfit - grossLoss
            return PerformanceStats(
                trades = trades.size,
                wins = wins.size,
                losses = losses.size,
                winRatePercent = if (trades.isEmpty()) 0.0 else wins.size * 100.0 / trades.size,
                grossProfit = grossProfit,
                grossLoss = grossLoss,
                netPnl = net,
                netReturnPercent = net / capital * 100,
                profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0,
                averageWin = if (wins.isEmpty()) 0.0 else grossProfit / wins.size,
                averageLoss = if (losses.isEmpty()) 0.0 else -grossLoss / losses.size,
                expectancy = if (trades.isEmpty()) 0.0 else net / trades.size,
                averageR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.rMultiple } / trades.size,
                maxDrawdownPercent = maxDd,
                fees = trades.sumOf { it.fees },
            )
        }
    }
}

/** Bir dönemin (gün/ay) özsermaye değişimi. Dönem sonu özsermayesi açık pozisyonların piyasa değerini içerir. */
@Serializable
data class PeriodReturn(
    val label: String,
    val startEquity: Double,
    val endEquity: Double,
    val changePercent: Double,
    /** Bu dönemde kapanan işlem sayısı ve net sonucu. */
    val tradesClosed: Int,
    val realizedPnl: Double,
)

object PeriodReturns {
    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate()

    /** [points]: (gün sonu zamanı, özsermaye). Zaman damgası bir sonraki günün 00:00'ıdır; gün = t − 1 ms. */
    fun of(points: List<Pair<Long, Double>>, initial: Double, trades: List<ClosedTrade>, monthly: Boolean): List<PeriodReturn> {
        fun key(t: Long) = day(t - 1).let { if (monthly) "%04d-%02d".format(it.year, it.monthValue) else it.toString() }
        val tradesByKey = trades.groupBy { key(it.closedAt) }
        val out = ArrayList<PeriodReturn>()
        var startEquity = initial
        points.sortedBy { it.first }.groupBy { key(it.first) }.forEach { (label, group) ->
            val end = group.last().second
            val closed = tradesByKey[label].orEmpty()
            out += PeriodReturn(label, startEquity, end, (end / startEquity - 1) * 100, closed.size, closed.sumOf { it.netPnl })
            startEquity = end
        }
        return out
    }
}

/** Aynı 15M mumda hem stop hem hedef görülen durumların nasıl çözüldüğü. */
@Serializable
data class IntrabarStats(
    val ambiguousBars: Int = 0,
    /** 5M veriyle hedefin önce geldiği doğrulandı. */
    val resolvedTargetFirst: Int = 0,
    /** 5M veriyle stopun önce geldiği doğrulandı. */
    val resolvedStopFirst: Int = 0,
    /** 5M veri yok: ihtiyatlı kural (stop önce) uygulandı ve işlem "belirsiz" işaretlendi. */
    val unresolvedNoData: Int = 0,
    /** Aynı 5M mumda da ikisi birden görüldü: ihtiyatlı kural (stop önce) uygulandı. */
    val unresolvedSameSubBar: Int = 0,
)

/** 5M verisi gereken belirsiz 15M mum (uygulama eksik veriyi indirip testi yeniden çalıştırır). */
@Serializable
data class SubBarRequest(val symbol: String, val openTime: Long)

/** İşlem süresince en iyi ve en kötü fiyat hareketi (R cinsinden; işlemden sonra hesaplanır, kararda kullanılmaz). */
@Serializable
data class Excursion(val mfeR: Double, val maeR: Double, val barsHeld: Int)

/**
 * Giriş anında (sinyal mumunun kapanışında) bilinen özellikler. Yalnız o ana kadar kapanmış mumlardan hesaplanır;
 * kayıp analizi bu kayıtları sonuçla eşleştirir.
 */
@Serializable
data class TradeFeatures(
    val symbol: String,
    val strategy: StrategyId,
    val signalTime: Long,
    val marketRegime: MarketRegime?,
    val coinRegime: MarketRegime?,
    val btcH4Trend: Regime?,
    val h4Trend: Regime,
    val h1Trend: Regime,
    val adx1h: Double,
    val adx4h: Double,
    val rsi1h: Double,
    val rsi15m: Double,
    val macdHistogram1hRising: Boolean,
    /** 1H'de son iki onaylı tepe: fiyat daha yüksek, RSI daha düşük (negatif uyumsuzluk). */
    val bearishRsiDivergence1h: Boolean,
    val volumeRatio15m: Double,
    val volumeRatio1h: Double,
    val atrPercent1h: Double,
    val stopDistanceAtr1h: Double,
    val stopDistancePercent: Double,
    val plannedRewardRisk: Double,
    val hourUtc: Int,
    val qualityScore: Double,
    val confirmations: Int,
    val confluences: Int,
    val h1StructureUp: Boolean,
)

object FeatureExtractor {
    fun extract(mtf: MultiTimeframe, btc: MultiTimeframe?, setup: TradeSetup, qualityScore: Double): TradeFeatures {
        val h1 = mtf.h1.indicators
        val h4 = mtf.h4.indicators
        val m15 = mtf.m15.indicators
        val highs = mtf.h1.swings.filter { it.type == SwingType.HIGH }.takeLast(2)
        val rsi = mtf.h1.series.rsi
        val divergence = highs.size == 2 && highs[1].price > highs[0].price && rsi[highs[1].index] < rsi[highs[0].index]
        val risk = setup.entryPrice - setup.stopPrice
        return TradeFeatures(
            symbol = setup.symbol,
            strategy = setup.strategy,
            signalTime = setup.signalTime,
            marketRegime = RegimeClassifier.classify(btc?.h4),
            coinRegime = RegimeClassifier.classify(mtf.h4),
            btcH4Trend = btc?.h4?.regime,
            h4Trend = mtf.h4.regime,
            h1Trend = mtf.h1.regime,
            adx1h = h1.adx,
            adx4h = h4.adx,
            rsi1h = h1.rsi,
            rsi15m = m15.rsi,
            macdHistogram1hRising = mtf.h1.previous?.let { h1.macdHistogram > it.macdHistogram } ?: false,
            bearishRsiDivergence1h = divergence,
            volumeRatio15m = if (m15.volumeAverage > 0) m15.volume / m15.volumeAverage else 0.0,
            volumeRatio1h = if (h1.volumeAverage > 0) h1.volume / h1.volumeAverage else 0.0,
            atrPercent1h = h1.atr / h1.close * 100,
            stopDistanceAtr1h = if (h1.atr > 0) risk / h1.atr else 0.0,
            stopDistancePercent = risk / setup.entryPrice * 100,
            plannedRewardRisk = if (risk > 0) (setup.targetPrice - setup.entryPrice) / risk else 0.0,
            hourUtc = Instant.ofEpochMilli(setup.signalTime).atZone(ZoneOffset.UTC).hour,
            qualityScore = qualityScore,
            confirmations = setup.confirmations.size,
            confluences = setup.confluences.size,
            h1StructureUp = mtf.h1.structure.trend == Trend.UP,
        )
    }
}
