package com.spotbot.android.domain.model

import java.math.BigDecimal
import java.time.Instant

enum class OrderSide { BUY, SELL }

enum class TradeReason { ENTRY, TAKE_PROFIT, STOP_LOSS }

/** Gerçekleşmiş bir alım veya satım. */
data class Trade(
    val id: Long,
    val pair: TradingPair,
    val side: OrderSide,
    val reason: TradeReason,
    val price: BigDecimal,
    val quantity: BigDecimal,
    /** Alımda harcanan, satımda ücret düşüldükten sonra elde edilen kote tutarı. */
    val quoteAmount: BigDecimal,
    val fee: BigDecimal,
    /** Yalnızca satışlarda: pozisyonun ücretler dahil net kâr/zararı. */
    val realizedPnl: BigDecimal?,
    val timestamp: Instant,
)
