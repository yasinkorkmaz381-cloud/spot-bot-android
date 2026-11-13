package com.spotbot.core.kr

import kotlinx.serialization.Serializable

/**
 * Kırılım–Retest (KR) stratejisinin tek parametre kaynağı. Değerler docs/KR-ONKAYIT.md'deki ön kayıtla aynıdır ve
 * **araştırma varsayımıdır**; geçmiş veriye göre seçilmemiştir. Kural numaraları (Y1, S1, K1…) o belgeye karşılık gelir.
 */
@Serializable
data class KrConfig(
    /** Y1: 4H EMA periyodu ve eğim için geriye bakılan 4H mum sayısı. */
    val emaTrend4h: Int = 50,
    val emaSlopeBars: Int = 6,
    /** S1: fraktal tepe için sol/sağ mum sayısı. */
    val swingSide: Int = 3,
    /** S2: seviye en fazla bu kadar 1H mum eski olabilir. */
    val levelLookbackHours: Int = 72,
    val atrPeriod: Int = 14,
    /** K2: kırılım hacmi ≥ bu kat × önceki [volumeAverageBars] mumun ortalaması. */
    val breakoutVolumeMultiple: Double = 1.5,
    val volumeAverageBars: Int = 20,
    /** R1: retest için kırılımdan sonraki en fazla 15M mum sayısı. */
    val retestBars: Int = 8,
    /** R2: retest düşüğü ≤ seviye + bu × ATR15. */
    val retestToleranceAtr: Double = 0.25,
    /** G2: dolum > seviye + bu × ATR15 ise kovalamaz. */
    val maxChaseAtr: Double = 0.75,
    /** G3: stop tamponu (ATR15). */
    val stopBufferAtr: Double = 0.25,
    /** G4: volatilite hedef sınırı (ATR1H). */
    val targetAtr1h: Double = 1.5,
    /** G5: net R:R alt sınırı. */
    val minNetRewardRisk: Double = 1.2,
    /** G6: iki yön maliyet / brüt hedef hareketi en fazla. */
    val maxCostShareOfTarget: Double = 0.40,
    /** Tez iptali: kapanış < seviye − bu × ATR15. */
    val invalidationAtr: Double = 0.25,
    /** İlerleme yok: girişten bu kadar 15M mum sonra en yüksek < dolum + [progressFraction] × (hedef − dolum). */
    val progressBars: Int = 8,
    val progressFraction: Double = 0.5,
    /** En uzun elde tutma (15M mum). */
    val maxHoldBars: Int = 24,
    /** P1: BTC son [btcShockBars] adet 15M'de bu yüzdeden fazla düştüyse yeni giriş yok. */
    val btcShockBars: Int = 16,
    val btcShockPercent: Double = 2.0,
    /** P2: 24 saatlik göreceli güç (coin − BTC) > bu yüzde. */
    val minRelativeStrengthPercent: Double = 0.0,
    /** P3: son 24 saatte MEXC işlem hacmi (USDT). */
    val minQuoteVolume24h: Double = 1_000_000.0,
    /** P3 (yalnız canlı): spread ve emir defteri kayması üst sınırı (yüzde). */
    val maxSpreadPercent: Double = 0.20,
    val maxBookSlippagePercent: Double = 0.15,
    /** P5: son bu kadar saatte 15M boşluğu olmamalı. */
    val dataCleanHours: Int = 48,
    /** P6: makro olay penceresi (dakika, olaydan önce / sonra). */
    val eventBeforeMinutes: Int = 30,
    val eventAfterMinutes: Int = 60,
    val risk: KrRisk = KrRisk(),
    val costs: KrCosts = KrCosts.NORMAL,
) {
    /** Bir kurulumu değerlendirebilmek için gereken en az 15M geçmişi. */
    val minBars15: Int get() = maxOf(dataCleanHours * 4, (levelLookbackHours + 2 * swingSide + 2) * 4, 200)

    companion object {
        const val M15 = 15 * 60_000L
        const val H1 = 60 * 60_000L
        const val H4 = 4 * H1
        const val DAY = 24 * H1
    }
}

/** Başlangıç paper trading sınırları (optimum değildir). */
@Serializable
data class KrRisk(
    val startEquity: Double = 1_000.0,
    val riskPercent: Double = 0.25,
    val maxOpenRiskPercent: Double = 1.0,
    val maxPositions: Int = 4,
    val maxPositionPercent: Double = 20.0,
    val maxTotalPercent: Double = 70.0,
    val dailyLossPercent: Double = 2.0,
)

/**
 * Maliyet senaryosu. Komisyon coin başına MEXC `takerCommission` değerinden gelir (yoksa [defaultFee]); yarım spread
 * coin başına anlık görüntüden (yoksa [defaultHalfSpread]); kayma her bacakta [slippage]. Hepsi [multiplier] ile çarpılır.
 */
@Serializable
data class KrCosts(
    val name: String,
    val defaultFee: Double = 0.0005,
    val defaultHalfSpread: Double = 0.0005,
    val minHalfSpread: Double = 0.0001,
    val slippage: Double = 0.0005,
    val multiplier: Double = 1.0,
) {
    fun doubled() = copy(name = "$name ×2", multiplier = multiplier * 2)

    fun legFor(fee: Double?, halfSpread: Double?): Leg =
        Leg(
            fee = (fee ?: defaultFee) * multiplier,
            halfSpread = maxOf(halfSpread ?: defaultHalfSpread, minHalfSpread) * multiplier,
            slippage = slippage * multiplier,
        )

    companion object {
        val NORMAL = KrCosts("Normal")
    }
}

/** Tek bacak maliyeti (oran). [priceImpact] dolum fiyatını kötüleştirir, [fee] tutardan düşülür. */
@Serializable
data class Leg(val fee: Double, val halfSpread: Double, val slippage: Double) {
    val priceImpact: Double get() = halfSpread + slippage
    val total: Double get() = fee + halfSpread + slippage
}
