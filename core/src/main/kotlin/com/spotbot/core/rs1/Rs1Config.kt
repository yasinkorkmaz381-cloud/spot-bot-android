package com.spotbot.core.rs1

import kotlinx.serialization.Serializable

/**
 * Momentum RS-1'in bütün parametreleri: tek yapılandırma kaynağı (docs/RS1-ONKAYIT.md). Backtest, paper trading ve
 * canlı tarama aynı nesneyi kullanır. Değerler ilk sürüm araştırma parametreleridir; verilerle doğrulanmadan
 * değiştirilmez.
 */
@Serializable
data class Rs1Config(
    // Uygunluk
    val emaTrend: Int = 100,
    val emaExit: Int = 50,
    val atrPeriod: Int = 14,
    val shortReturnDays: Int = 7,
    val returnDays: Int = 30,
    val minRelativeStrengthPoints: Double = 3.0,
    val maxSpreadPercent: Double = 0.15,
    /** Orta fiyatın ±[depthBandPercent] aralığında, her iki tarafta ≥ bu kat × coin başına en büyük pozisyon. */
    val depthMultiple: Double = 3.0,
    val depthBandPercent: Double = 1.0,
    val minMedianVolume: Double = 5_000_000.0,
    val volumeDays: Int = 30,
    val maxRangeAtr: Double = 2.5,
    val minHistoryDays: Int = 150,
    val gapCheckDays: Int = 60,
    val candidates: Int = 2,
    // Risk
    val capital: Double = 1_000.0,
    val riskPercent: Double = 0.5,
    val maxPositions: Int = 2,
    val maxPositionPercent: Double = 25.0,
    val maxTotalPercent: Double = 50.0,
    val dailyLossPercent: Double = 1.5,
    val initialStopAtr: Double = 2.5,
    val trailAtr: Double = 3.0,
    val emaExitDays: Int = 2,
    val participation: Double = 0.10,
    val minNotional: Double = 5.0,
    // Zamanlama
    /** Haftalık giriş günü (java.time.DayOfWeek değeri; 1 = pazartesi), 00:00 UTC kapanışında. */
    val entryWeekday: Int = 1,
    /** Paper trading'de canlı haftalık ölçümün yapılabileceği süre (pazartesi 00:00'dan itibaren, saat). */
    val weeklyWindowHours: Int = 4,
    val costs: Rs1Costs = Rs1Costs.MEDIUM,
)

/** Maliyet senaryosu: yön başına ücret, kayma ve yarım spread (oran). Geçmiş spread verisi olmadığı için senaryodur. */
@Serializable
data class Rs1Costs(val name: String, val halfSpread: Double, val fee: Double = 0.0005, val slippage: Double = 0.0005) {
    /** Alışta fiyata eklenen, satışta düşülen oran (yarım spread + kayma). */
    val markup: Double get() = halfSpread + slippage

    companion object {
        val OPTIMISTIC = Rs1Costs("İyimser", 0.00025)
        val MEDIUM = Rs1Costs("Orta", 0.00075)
        val PESSIMISTIC = Rs1Costs("Kötümser", 0.0015)
        val all = listOf(OPTIMISTIC, MEDIUM, PESSIMISTIC)
    }
}

const val DAY_MS = 86_400_000L
const val HOUR_MS = 3_600_000L
const val BTC = "BTCUSDT"

/** [t] (UTC gün başı) haftanın giriş günü mü? 1970-01-01 perşembedir (ISO 4). */
fun isWeekday(t: Long, isoDay: Int): Boolean = ((Math.floorDiv(t, DAY_MS) + 3) % 7 + 1).toInt() == isoDay
