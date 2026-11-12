package com.spotbot.core.rs1

import kotlinx.serialization.Serializable
import kotlin.random.Random

enum class EvidenceClass(val title: String) {
    EVIDENCE("Kanıt var"),
    INSUFFICIENT("Yetersiz kanıt"),
    FAILED("Başarısız"),
}

/** Ön kayıtlı kabul ölçütlerinin tek tek sonucu (docs/RS1-V6-ONKAYIT.md). */
@Serializable
data class Evidence(
    val classification: EvidenceClass,
    val trades: Int,
    val expectancy: Double,
    val profitFactor: Double,
    val maxDrawdownPercent: Double,
    val expectancyWithoutBestCoin: Double,
    val netWithoutTop3: Double,
    val doubledCostExpectancy: Double?,
    /** Ortalama R için %90 bootstrap güven aralığı. */
    val ciLow: Double,
    val ciHigh: Double,
    val checks: List<Pair<String, Boolean>>,
)

/**
 * Ön kayıtlı kabul ölçütleri. "Kanıt var" yalnız bütün şartlar sağlanırsa; "Başarısız" en az 30 işlemde beklenen
 * değer ≤ 0 ya da PF < 1,0 ise; diğer her durum "Yetersiz kanıt". Arayüzdeki etiketler yalnız buradan gelir.
 */
object Rs1Evidence {
    const val MIN_TRADES = 30

    fun bootstrapMeanCi(values: List<Double>, iterations: Int = 10_000, seed: Int = 42, level: Double = 0.90): Pair<Double, Double> {
        if (values.size < 2) return Double.NaN to Double.NaN
        val rnd = Random(seed)
        val means = DoubleArray(iterations) {
            var s = 0.0
            repeat(values.size) { s += values[rnd.nextInt(values.size)] }
            s / values.size
        }
        means.sort()
        val a = (1 - level) / 2
        return means[(a * iterations).toInt()] to means[((1 - a) * iterations).toInt().coerceAtMost(iterations - 1)]
    }

    fun classify(r: Rs1Result, doubled: Rs1Result? = null): Evidence {
        val trades = r.trades
        val n = trades.size
        val expectancy = r.expectancy
        val pf = r.profitFactor
        val best = trades.groupBy { it.symbol }.maxByOrNull { e -> e.value.sumOf { it.netPnl } }?.key
        val rest = trades.filter { it.symbol != best }
        val exNoBest = if (rest.isEmpty()) 0.0 else rest.sumOf { it.netPnl } / rest.size
        val top3 = trades.sortedByDescending { it.netPnl }.take(3).sumOf { it.netPnl }
        val netNoTop3 = trades.sumOf { it.netPnl } - top3
        val dExp = doubled?.expectancy
        val (lo, hi) = bootstrapMeanCi(trades.map { it.r })
        val checks = listOf(
            "En az $MIN_TRADES kapanmış işlem ($n)" to (n >= MIN_TRADES),
            "Maliyet sonrası beklenen değer > 0" to (expectancy > 0),
            "Profit factor ≥ 1,20" to (pf >= 1.2),
            "En büyük düşüş ≤ %10" to (r.maxDrawdownPercent <= 10.0),
            "En iyi coin olmadan beklenen değer > 0" to (exNoBest > 0),
            "En iyi 3 işlem olmadan net > 0" to (netNoTop3 > 0),
            "İki kat maliyette beklenen değer > 0" to (dExp != null && dExp > 0),
            "Ortalama R %90 güven aralığı alt sınırı > 0" to (lo > 0),
        )
        val cls = when {
            checks.all { it.second } -> EvidenceClass.EVIDENCE
            n >= MIN_TRADES && (expectancy <= 0 || pf < 1.0) -> EvidenceClass.FAILED
            else -> EvidenceClass.INSUFFICIENT
        }
        return Evidence(cls, n, expectancy, pf, r.maxDrawdownPercent, exNoBest, netNoTop3, dExp, lo, hi, checks)
    }
}
