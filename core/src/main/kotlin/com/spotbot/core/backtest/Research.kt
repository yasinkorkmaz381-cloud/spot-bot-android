package com.spotbot.core.backtest

import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.ClosedTrade
import com.spotbot.core.trading.ExecutionConfig
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.random.Random

/** Bir backtest çalışmasının karşılaştırma için kısa özeti. */
@Serializable
data class RunSummary(
    val trades: Int,
    val netReturnPercent: Double,
    val profitFactor: Double,
    val expectancyR: Double,
    val winRatePercent: Double,
    val maxDrawdownPercent: Double,
    val feesAndFriction: Double,
)

fun BacktestReport.summary() = RunSummary(
    trades, netReturnPercent, profitFactor, expectancyR, winRatePercent, maxDrawdownPercent, totalFees + spreadAndSlippageCost,
)

fun summarize(trades: List<ClosedTrade>, capital: Double): RunSummary {
    val wins = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
    val losses = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
    var equity = capital
    var peak = capital
    var maxDd = 0.0
    trades.sortedBy { it.closedAt }.forEach { equity += it.netPnl; peak = max(peak, equity); maxDd = max(maxDd, (peak - equity) / peak * 100) }
    return RunSummary(
        trades = trades.size,
        netReturnPercent = trades.sumOf { it.netPnl } / capital * 100,
        profitFactor = if (losses > 0) wins / losses else if (wins > 0) Double.POSITIVE_INFINITY else 0.0,
        expectancyR = if (trades.isEmpty()) 0.0 else trades.sumOf { it.rMultiple } / trades.size,
        winRatePercent = if (trades.isEmpty()) 0.0 else trades.count { it.netPnl > 0 } * 100.0 / trades.size,
        maxDrawdownPercent = maxDd,
        feesAndFriction = trades.sumOf { it.fees },
    )
}

/** Önceden tanımlanmış bir deney seçeneği. */
@Serializable
data class Variant(val name: String, val strategies: Set<StrategyId>, val execution: ExecutionConfig)

@Serializable
data class MonteCarloResult(
    val iterations: Int,
    val trades: Int,
    val returnP5: Double,
    val returnP50: Double,
    val returnP95: Double,
    val maxDrawdownP50: Double,
    val maxDrawdownP95: Double,
    val lossProbabilityPercent: Double,
)

/**
 * İşlem sonuçlarını yerine koyarak yeniden örnekler (bootstrap). Aynı strateji başka bir sırayla/şansla
 * işleseydi getiri ve en büyük düşüşün hangi aralıkta olabileceğini gösterir.
 */
object MonteCarlo {
    fun run(pnls: List<Double>, capital: Double, iterations: Int = 5_000, seed: Long = 42): MonteCarloResult? {
        if (pnls.size < 5) return null
        val random = Random(seed)
        val returns = DoubleArray(iterations)
        val drawdowns = DoubleArray(iterations)
        repeat(iterations) { k ->
            var equity = capital
            var peak = capital
            var dd = 0.0
            repeat(pnls.size) {
                equity += pnls[random.nextInt(pnls.size)]
                peak = max(peak, equity)
                dd = max(dd, (peak - equity) / peak * 100)
            }
            returns[k] = (equity / capital - 1) * 100
            drawdowns[k] = dd
        }
        returns.sort()
        drawdowns.sort()
        fun pct(a: DoubleArray, p: Double) = a[((a.size - 1) * p).toInt()]
        return MonteCarloResult(
            iterations, pnls.size, pct(returns, 0.05), pct(returns, 0.5), pct(returns, 0.95),
            pct(drawdowns, 0.5), pct(drawdowns, 0.95), returns.count { it < 0 } * 100.0 / iterations,
        )
    }
}

@Serializable
data class WalkForwardFold(
    val inSampleStart: Long,
    val outOfSampleStart: Long,
    val outOfSampleEnd: Long,
    val chosen: String,
    val inSampleExpectancyR: Double,
    val outOfSample: RunSummary,
)

@Serializable
data class WalkForwardReport(val folds: List<WalkForwardFold>, val combined: RunSummary, val combinedPnls: List<Double>)

/**
 * İleri yürüyen test: her katmanda seçenekler yalnız önceki dönemde (in-sample) karşılaştırılır, en iyisi
 * görmediği sonraki dönemde (out-of-sample) çalıştırılır. Sonuç yalnız görülmemiş dönemlerin birleşimidir.
 */
object WalkForward {
    fun run(
        base: BacktestConfig,
        variants: List<Variant>,
        data: Map<String, HistoricalDataset>,
        info: Map<String, SymbolInfo>,
        start: Long,
        end: Long,
        inSampleMillis: Long,
        outOfSampleMillis: Long,
        minTrades: Int = 10,
        runner: (List<() -> BacktestReport>) -> List<BacktestReport> = { jobs -> jobs.map { it() } },
    ): WalkForwardReport {
        val folds = ArrayList<WalkForwardFold>()
        val oosTrades = ArrayList<ClosedTrade>()
        var oosStart = start + inSampleMillis
        while (oosStart < end) {
            val isStart = oosStart - inSampleMillis
            val oosEnd = minOf(end, oosStart + outOfSampleMillis)
            val isReports = runner(variants.map { v -> { BacktestEngine(base.with(v, isStart, oosStart)).run(data, info) } })
            val scored = variants.zip(isReports).filter { it.second.trades >= minTrades }
            val (chosen, isReport) = scored.maxByOrNull { it.second.expectancyR } ?: (variants.first() to isReports.first())
            val oos = BacktestEngine(base.with(chosen, oosStart, oosEnd)).run(data, info)
            folds += WalkForwardFold(isStart, oosStart, oosEnd, chosen.name, isReport.expectancyR, oos.summary())
            oosTrades += oos.closedTrades
            oosStart = oosEnd
        }
        return WalkForwardReport(folds, summarize(oosTrades, base.initialCapital), oosTrades.map { it.netPnl })
    }
}

fun BacktestConfig.with(variant: Variant, start: Long, end: Long) =
    copy(startTime = start, endTime = end, enabledStrategies = variant.strategies, execution = variant.execution)

@Serializable
data class ProfileComparison(
    val profile: com.spotbot.core.trading.StrategyProfile,
    val full: RunSummary,
    /** Dönemin ilk yarısı (seçim/ayar için kullanılabilecek kısım). */
    val firstHalf: RunSummary,
    /** Dönemin ikinci yarısı (görülmemiş dönem gibi değerlendirilir). */
    val secondHalf: RunSummary,
    val partialFills: Int,
    val rejectedOrders: Int,
)

/**
 * Strateji profillerini **aynı koşullarda** karşılaştırır: aynı veri, aynı dönem, aynı maliyet, risk ve emir
 * modeli. Yalnız profil değişir. Ayrıca dönem ikiye bölünür; bir profil yalnız iki yarıda da tutarlıysa anlamlıdır.
 */
object StrategyComparison {
    fun run(
        base: BacktestConfig,
        data: Map<String, HistoricalDataset>,
        info: Map<String, SymbolInfo>,
        profiles: List<com.spotbot.core.trading.StrategyProfile> = com.spotbot.core.trading.StrategyProfile.entries,
        runner: (List<() -> BacktestReport>) -> List<BacktestReport> = { jobs -> jobs.map { it() } },
        onProgress: (Double) -> Unit = {},
    ): List<ProfileComparison> {
        val split = base.startTime + (base.endTime - base.startTime) / 2
        val jobs = profiles.flatMap { profile ->
            val config = base.copy(profile = profile)
            listOf(
                { BacktestEngine(config).run(data, info) },
                { BacktestEngine(config.copy(endTime = split)).run(data, info) },
                { BacktestEngine(config.copy(startTime = split)).run(data, info) },
            )
        }
        var done = 0
        val reports = runner(jobs.map { job -> { job().also { synchronized(this) { done++; onProgress(done.toDouble() / jobs.size) } } } })
        return profiles.mapIndexed { i, profile ->
            val (full, first, second) = Triple(reports[3 * i], reports[3 * i + 1], reports[3 * i + 2])
            ProfileComparison(profile, full.summary(), first.summary(), second.summary(), full.partialFills, full.rejectedOrders.values.sum())
        }
    }
}
