package com.spotbot.android.domain.trading

import com.spotbot.android.domain.model.BotState
import com.spotbot.android.domain.model.OrderSide
import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.Trade
import com.spotbot.android.domain.model.TradeReason
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Stratejinin kararlarını simülasyon hesabına uygular. Saf fonksiyondur: aynı girdi her zaman
 * aynı yeni durumu üretir; bu sayede gerçek borsa entegrasyonundan bağımsız test edilebilir.
 */
object PaperTrader {

    /** Binance spot standart işlem ücreti (%0,1). */
    val FEE_RATE: BigDecimal = BigDecimal("0.001")
    const val MAX_TRADE_HISTORY = 100
    private const val SCALE = 8

    fun onPrice(state: BotState, price: BigDecimal, now: Instant): BotState {
        if (!state.isRunning || price.signum() <= 0) return state
        val signal = TakeProfitStopLossStrategy.evaluate(
            config = state.config,
            position = state.account.position,
            price = price,
            availableQuote = state.account.quoteBalance,
        )
        return when (signal) {
            Signal.Hold -> state
            Signal.Buy -> buy(state, price, now)
            is Signal.Sell -> sell(state, price, now, signal.reason)
        }
    }

    private fun buy(state: BotState, price: BigDecimal, now: Instant): BotState {
        val pair = state.config.pair
        val cost = state.config.orderAmount
        val fee = cost.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP)
        val quantity = (cost - fee).divide(price, SCALE, RoundingMode.DOWN)
        if (quantity.signum() <= 0) return state

        val position = Position(
            pair = pair,
            quantity = quantity,
            entryPrice = cost.divide(quantity, SCALE, RoundingMode.HALF_UP),
            cost = cost,
        )
        val trade = Trade(
            id = nextTradeId(state),
            pair = pair,
            side = OrderSide.BUY,
            reason = TradeReason.ENTRY,
            price = price,
            quantity = quantity,
            quoteAmount = cost,
            fee = fee,
            realizedPnl = null,
            timestamp = now,
        )
        return state.copy(
            account = state.account.copy(
                quoteBalance = state.account.quoteBalance - cost,
                position = position,
            ),
            trades = (listOf(trade) + state.trades).take(MAX_TRADE_HISTORY),
        )
    }

    private fun sell(state: BotState, price: BigDecimal, now: Instant, reason: TradeReason): BotState {
        val position = state.account.position ?: return state
        val gross = position.quantity.multiply(price).setScale(SCALE, RoundingMode.HALF_UP)
        val fee = gross.multiply(FEE_RATE).setScale(SCALE, RoundingMode.HALF_UP)
        val proceeds = gross - fee

        val trade = Trade(
            id = nextTradeId(state),
            pair = position.pair,
            side = OrderSide.SELL,
            reason = reason,
            price = price,
            quantity = position.quantity,
            quoteAmount = proceeds,
            fee = fee,
            realizedPnl = proceeds - position.cost,
            timestamp = now,
        )
        return state.copy(
            account = state.account.copy(
                quoteBalance = state.account.quoteBalance + proceeds,
                position = null,
            ),
            trades = (listOf(trade) + state.trades).take(MAX_TRADE_HISTORY),
        )
    }

    private fun nextTradeId(state: BotState): Long = (state.trades.firstOrNull()?.id ?: 0L) + 1
}
