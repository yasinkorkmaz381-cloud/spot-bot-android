package com.spotbot.core.kr

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.random.Random

/** Bir işlem kümesinin özeti (docs/KR-ONKAYIT.md §6). */
@Serializable
data class KrMetrics(
    val trades: Int,
    val netPnl: Double,
    val netPercent: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val meanR: Double,
    val winRate: Double,
    val maxDrawdownPercent: Double,
    val totalCosts: Double,
    val grossWins: Double,
    /** Maliyet / kazanan işlemlerin brüt kârı. */
    val costToGrossProfit: Double,
    val avgHoldMinutes: Double,
    val medianHoldMinutes: Double,
    val days: Double,
    val activeDays: Int,
    val tradesPerDay: Double,
    val tradesPerWeek: Double,
    val tradesPerActiveDay: Double,
    val ambiguous: Int,
)

object KrReport {
    fun metrics(trades: List<KrTrade>, startEquity: Double, curve: List<Pair<Long, Double>>, start: Long, end: Long): KrMetrics {
        val wins = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val losses = -trades.filter { it.netPnl < 0 }.sumOf { it.netPnl }
        val net = trades.sumOf { it.netPnl }
        val holds = trades.map { it.holdMinutes.toDouble() }.sorted()
        val days = (end - start) / KrConfig.DAY.toDouble()
        val active = trades.map { dayKey(it.entryTime) }.toSet().size
        var peak = startEquity
        var dd = 0.0
        for ((_, e) in curve) { peak = max(peak, e); dd = max(dd, (peak - e) / peak) }
        val costs = trades.sumOf { it.costs }
        val grossWins = trades.filter { it.grossPnl > 0 }.sumOf { it.grossPnl }
        return KrMetrics(
            trades = trades.size, netPnl = net, netPercent = net / startEquity * 100,
            profitFactor = if (losses > 0) wins / losses else if (wins > 0) Double.POSITIVE_INFINITY else Double.NaN,
            expectancy = if (trades.isEmpty()) Double.NaN else net / trades.size,
            meanR = if (trades.isEmpty()) Double.NaN else trades.sumOf { it.rMultiple } / trades.size,
            winRate = if (trades.isEmpty()) Double.NaN else trades.count { it.netPnl > 0 } * 100.0 / trades.size,
            maxDrawdownPercent = dd * 100, totalCosts = costs, grossWins = grossWins,
            costToGrossProfit = if (grossWins > 0) costs / grossWins else Double.NaN,
            avgHoldMinutes = holds.average().takeUnless { it.isNaN() } ?: 0.0,
            medianHoldMinutes = if (holds.isEmpty()) 0.0 else holds[holds.size / 2],
            days = days, activeDays = active,
            tradesPerDay = trades.size / days, tradesPerWeek = trades.size / days * 7,
            tradesPerActiveDay = if (active > 0) trades.size.toDouble() / active else 0.0,
            ambiguous = trades.count { it.ambiguous },
        )
    }

    /** İşlem başı net R ortalamasının bootstrap güven aralığı (yüzdelik). */
    fun bootstrapMeanR(trades: List<KrTrade>, samples: Int = 10_000, seed: Int = 42, level: Double = 0.90): Pair<Double, Double> {
        if (trades.size < 2) return Double.NaN to Double.NaN
        val r = trades.map { it.rMultiple }.toDoubleArray()
        val rnd = Random(seed)
        val means = DoubleArray(samples) {
            var s = 0.0
            repeat(r.size) { s += r[rnd.nextInt(r.size)] }
            s / r.size
        }.sorted()
        val a = (1 - level) / 2
        return means[(a * samples).toInt()] to means[((1 - a) * samples).toInt() - 1]
    }

    enum class Evidence(val title: String) { PROVEN("Kanıt var"), INSUFFICIENT("Yetersiz kanıt"), FAILED("Başarısız") }

    data class Verdict(val evidence: Evidence, val checks: List<Pair<String, Boolean>>)

    /**
     * Ön kayıtlı sınıf. [normal] ve [doubled]: doğrulama + son değerlendirme birlikte; [validation] ve [final] ayrı ayrı
     * (normal maliyet). Eşikler sonuçlardan önce yazıldı ve değiştirilmez.
     */
    fun classify(normal: List<KrTrade>, normalDd: Double, doubled: List<KrTrade>, validation: List<KrTrade>, final: List<KrTrade>): Verdict {
        val n = normal.size
        val net = normal.sumOf { it.netPnl }
        val wins = normal.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val losses = -normal.filter { it.netPnl < 0 }.sumOf { it.netPnl }
        val pf = if (losses > 0) wins / losses else Double.POSITIVE_INFINITY
        val best = normal.groupBy { it.symbol }.maxByOrNull { (_, t) -> t.sumOf { it.netPnl } }?.key
        val withoutBest = normal.filter { it.symbol != best }.sumOf { it.netPnl }
        val ci = bootstrapMeanR(normal)
        val checks = listOf(
            "En az 100 işlem ($n)" to (n >= 100),
            "Net beklenti > 0" to (n > 0 && net > 0),
            "PF ≥ 1,2 (${"%.2f".format(pf)})" to (pf >= 1.2),
            "En büyük düşüş ≤ %6 (%${"%.2f".format(normalDd)})" to (normalDd <= 6.0),
            "×2 maliyette net > 0 (${"%.2f".format(doubled.sumOf { it.netPnl })})" to (doubled.sumOf { it.netPnl } > 0),
            "Ortalama R için %90 GA alt sınırı > 0 (${"%.3f".format(ci.first)})" to (ci.first > 0),
            "En iyi coin ($best) çıkarılınca net > 0 (${"%.2f".format(withoutBest)})" to (withoutBest > 0),
            "Doğrulama dönemi net > 0 (${"%.2f".format(validation.sumOf { it.netPnl })})" to (validation.sumOf { it.netPnl } > 0),
            "Son değerlendirme net > 0 (${"%.2f".format(final.sumOf { it.netPnl })})" to (final.sumOf { it.netPnl } > 0),
        )
        val evidence = when {
            n == 0 || net <= 0 -> Evidence.FAILED
            checks.all { it.second } -> Evidence.PROVEN
            else -> Evidence.INSUFFICIENT
        }
        return Verdict(evidence, checks)
    }
}
