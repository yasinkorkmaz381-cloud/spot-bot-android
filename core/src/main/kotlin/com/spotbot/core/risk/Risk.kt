package com.spotbot.core.risk

import com.spotbot.core.model.SymbolInfo
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Risk parametreleri. Tasarım notundaki başlangıç test değerleri; kesinleşmiş performans değerleri değildir.
 * [maxPositionPercentOfEquity], [maxCorrelatedRiskPercent] ve [correlationThreshold] notta sayısal olarak
 * verilmediği için test başlangıç varsayımıdır.
 */
@Serializable
data class RiskConfig(
    val riskPerTradePercent: Double = 0.5,
    val dailyLossLimitPercent: Double = 1.5,
    val maxOpenPositions: Int = 3,
    val minNetRiskReward: Double = 1.5,
    val preferredRiskReward: Double = 2.0,
    val maxPositionPercentOfEquity: Double = 30.0,
    val maxCorrelatedRiskPercent: Double = 1.0,
    val correlationThreshold: Double = 0.7,
    val correlationWindowBars: Int = 100,
)

/** İşlem maliyetleri: komisyon (taker), kayma ve yarım spread; hepsi oran (0.0005 = %0,05). */
@Serializable
data class CostModel(
    val takerFee: Double = 0.0005,
    val slippage: Double = 0.0005,
    val halfSpread: Double = 0.00025,
    /** MEXC spot maker komisyonu (Kasım 2026: %0). */
    val makerFee: Double = 0.0,
) {
    /** Alımda ödenen efektif fiyat (spread + kayma dahil). */
    fun buyPrice(reference: Double) = reference * (1 + slippage + halfSpread)

    /** Satımda alınan efektif fiyat. */
    fun sellPrice(reference: Double) = reference * (1 - slippage - halfSpread)

    fun withSymbolFee(info: SymbolInfo?) = if (info == null) this else copy(takerFee = info.takerFee, makerFee = info.makerFee)
}

@Serializable
data class RiskReward(
    /** Birim başına net risk (stop gerçekleşirse; ücretler dahil). */
    val riskPerUnit: Double,
    /** Birim başına net kazanç (hedef gerçekleşirse; ücretler dahil). */
    val rewardPerUnit: Double,
) {
    val ratio: Double get() = if (riskPerUnit > 0) rewardPerUnit / riskPerUnit else Double.NaN
}

/**
 * Komisyon, spread ve kayma düşüldükten sonraki net risk/getiri. [makerEntry] limit girişte giriş fiyatı
 * kaymasız ve maker ücretlidir; [makerTarget] hedefe limit emirle çıkışta aynısı geçerlidir. Stop her zaman
 * piyasa emridir.
 */
fun netRiskReward(entry: Double, stop: Double, target: Double, costs: CostModel, makerEntry: Boolean = false, makerTarget: Boolean = false): RiskReward {
    val paid = if (makerEntry) entry else costs.buyPrice(entry)
    val entryFee = paid * (if (makerEntry) costs.makerFee else costs.takerFee)
    val stopOut = costs.sellPrice(stop)
    val targetOut = if (makerTarget) target else costs.sellPrice(target)
    val risk = paid - stopOut + entryFee + stopOut * costs.takerFee
    val reward = targetOut - paid - entryFee - targetOut * (if (makerTarget) costs.makerFee else costs.takerFee)
    return RiskReward(risk, reward)
}

sealed interface SizingResult {
    @Serializable
    data class Sized(
        val quantity: Double,
        val notional: Double,
        /** Stop gerçekleşirse ücretler dahil beklenen kayıp (USDT). */
        val riskAmount: Double,
        val riskPercent: Double,
        val cappedBy: String?,
    ) : SizingResult

    data class Rejected(val reason: String) : SizingResult
}

/**
 * Pozisyon büyüklüğü: (özsermaye × risk%) / birim başına net risk. Pozisyon özsermayenin
 * [RiskConfig.maxPositionPercentOfEquity] yüzdesini ve kullanılabilir USDT'yi aşamaz. Miktar borsanın
 * ondalık hassasiyetine aşağı yuvarlanır; asgari miktar ve tutar kontrol edilir.
 */
object PositionSizer {
    fun size(
        equity: Double,
        availableQuote: Double,
        entry: Double,
        stop: Double,
        target: Double,
        info: SymbolInfo?,
        costs: CostModel,
        config: RiskConfig,
        makerEntry: Boolean = false,
        makerTarget: Boolean = false,
    ): SizingResult {
        if (stop >= entry) return SizingResult.Rejected("Stop girişin altında değil")
        val rr = netRiskReward(entry, stop, target, costs, makerEntry, makerTarget)
        if (rr.riskPerUnit <= 0) return SizingResult.Rejected("Birim risk hesaplanamadı")
        val paid = if (makerEntry) entry else costs.buyPrice(entry)
        val riskBudget = equity * config.riskPerTradePercent / 100
        var quantity = riskBudget / rr.riskPerUnit
        var cappedBy: String? = null

        val maxNotional = equity * config.maxPositionPercentOfEquity / 100
        if (quantity * paid > maxNotional) {
            quantity = maxNotional / paid
            cappedBy = "Pozisyon üst sınırı (%%%.0f)".format(config.maxPositionPercentOfEquity)
        }
        val spendable = availableQuote / (1 + (if (makerEntry) costs.makerFee else costs.takerFee))
        if (quantity * paid > spendable) {
            quantity = spendable / paid
            cappedBy = "Kullanılabilir USDT"
        }
        quantity = floorTo(quantity, info?.quantityPrecision ?: 8)
        val notional = quantity * paid
        if (quantity <= 0.0) return SizingResult.Rejected("Hesaplanan miktar sıfır")
        if (info != null && quantity < info.minQuantity) return SizingResult.Rejected("Asgari miktarın (${info.minQuantity}) altında")
        if (info != null && notional < info.minNotional) return SizingResult.Rejected("Asgari emir tutarının (${info.minNotional} USDT) altında")

        val risk = quantity * rr.riskPerUnit
        return SizingResult.Sized(quantity, notional, risk, risk / equity * 100, cappedBy)
    }

    fun floorTo(value: Double, decimals: Int): Double =
        BigDecimal.valueOf(value).setScale(decimals.coerceAtLeast(0), RoundingMode.DOWN).toDouble()
}
