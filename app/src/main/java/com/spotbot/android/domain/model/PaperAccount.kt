package com.spotbot.android.domain.model

import java.math.BigDecimal
import java.math.MathContext

/** Açık spot pozisyonu. [entryPrice] alım ücretini de içeren efektif maliyettir. */
data class Position(
    val pair: TradingPair,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    /** Pozisyon için harcanan toplam kote tutarı (ücret dahil). */
    val cost: BigDecimal,
)

/** Simülasyon (sanal işlem) hesabı. Aynı anda en fazla bir pozisyon tutar. */
data class PaperAccount(
    val initialBalance: BigDecimal,
    val quoteBalance: BigDecimal,
    val position: Position?,
    val quoteAsset: String = "USDT",
) {
    /** Hesabı [priceOf] ile verilen güncel fiyatlarla değerler. Fiyatı bilinmeyen pozisyon maliyetinden sayılır. */
    fun valuate(priceOf: (TradingPair) -> BigDecimal?): PortfolioSnapshot {
        val positionValue = position?.let { p -> priceOf(p.pair)?.multiply(p.quantity) ?: p.cost }
        val total = quoteBalance + (positionValue ?: BigDecimal.ZERO)
        val pnl = total - initialBalance
        val pnlPercent = if (initialBalance.signum() == 0) {
            0.0
        } else {
            pnl.divide(initialBalance, MathContext.DECIMAL64).toDouble() * 100
        }
        return PortfolioSnapshot(total, positionValue, pnl, pnlPercent)
    }

    companion object {
        fun create(initialBalance: BigDecimal) = PaperAccount(initialBalance, initialBalance, position = null)
    }
}

data class PortfolioSnapshot(
    val totalValue: BigDecimal,
    val positionValue: BigDecimal?,
    val pnl: BigDecimal,
    val pnlPercent: Double,
)
