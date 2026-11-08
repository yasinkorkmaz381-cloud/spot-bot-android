package com.spotbot.core.trading

import com.spotbot.core.risk.CostModel
import com.spotbot.core.rules.RuleReport
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.TradeSetup
import kotlinx.serialization.Serializable

/** Her işlemde botun cevaplaması gereken altı soru (tasarım notu, bölüm 6). */
@Serializable
data class TradeRationale(
    val whyCoin: String,
    val whyEntry: String,
    val trendCheck: String,
    val stopReason: String,
    val riskReward: String,
    val invalidation: String,
)

@Serializable
data class Position(
    val id: String,
    val symbol: String,
    val strategy: StrategyId,
    val openedAt: Long,
    /** Gerçekleşen (spread ve kayma dahil) alış fiyatı. */
    val entryPrice: Double,
    val quantity: Double,
    val entryFee: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val invalidationPrice: Double,
    /** Stop gerçekleşirse beklenen kayıp (USDT, ücretler dahil). */
    val riskAmount: Double,
    val qualityScore: Double,
    val setup: TradeSetup,
    val rationale: TradeRationale,
    val ruleReport: RuleReport? = null,
    val lastPrice: Double = entryPrice,
) {
    val cost: Double get() = entryPrice * quantity + entryFee

    /** Şu an satılırsa net kâr/zarar (satış ücreti ve kayma dahil). */
    fun unrealizedPnl(bid: Double, costs: CostModel): Double {
        val out = costs.sellPrice(bid) * quantity
        return out - out * costs.takerFee - cost
    }

    fun marketValue(bid: Double, costs: CostModel): Double {
        val out = costs.sellPrice(bid) * quantity
        return out - out * costs.takerFee
    }
}

@Serializable
enum class ExitReason(val title: String) {
    STOP_LOSS("Stop-loss"),
    TAKE_PROFIT("Kâr hedefi"),
    INVALIDATION("Yapısal bozulma"),
    MANUAL("Manuel kapatma"),
}

@Serializable
data class ClosedTrade(
    val position: Position,
    val closedAt: Long,
    val exitPrice: Double,
    val exitFee: Double,
    val reason: ExitReason,
    val note: String,
) {
    val proceeds: Double get() = exitPrice * position.quantity - exitFee
    val netPnl: Double get() = proceeds - position.cost
    val rMultiple: Double get() = if (position.riskAmount > 0) netPnl / position.riskAmount else 0.0
    val fees: Double get() = position.entryFee + exitFee
}

@Serializable
enum class DecisionOutcome { OPENED, REJECTED, NO_SETUP, CLOSED, INFO }

/** İşlem günlüğü kaydı: açılan, reddedilen ve kapatılan her kararın gerekçesi. */
@Serializable
data class Decision(
    val time: Long,
    val symbol: String?,
    val strategy: StrategyId?,
    val outcome: DecisionOutcome,
    val summary: String,
    val details: List<String> = emptyList(),
    val qualityScore: Double? = null,
)

@Serializable
data class EquityPoint(val time: Long, val equity: Double)

@Serializable
data class PortfolioState(
    val initialCapital: Double,
    val cash: Double,
    val positions: List<Position> = emptyList(),
    val closedTrades: List<ClosedTrade> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val dayStart: Long = 0,
    val dayStartEquity: Double = initialCapital,
    val equityHistory: List<EquityPoint> = emptyList(),
    val nextPositionId: Long = 1,
) {
    fun equity(bids: Map<String, Double>, costs: (String) -> CostModel): Double =
        cash + positions.sumOf { p -> p.marketValue(bids[p.symbol] ?: p.lastPrice, costs(p.symbol)) }

    companion object {
        fun create(capital: Double) = PortfolioState(initialCapital = capital, cash = capital)
    }
}

/** Sanal hesap işlemleri. Saf fonksiyonlardır; canlı motor ve backtest aynı kodu kullanır. */
object PaperAccount {
    const val MAX_DECISIONS = 300
    const val MAX_EQUITY_POINTS = 24 * 120
    private const val DAY = 24 * 60 * 60_000L

    fun open(
        state: PortfolioState,
        setup: TradeSetup,
        quantity: Double,
        askReference: Double,
        costs: CostModel,
        riskAmount: Double,
        qualityScore: Double,
        rationale: TradeRationale,
        report: RuleReport?,
        now: Long,
    ): PortfolioState {
        val fill = costs.buyPrice(askReference)
        val fee = fill * quantity * costs.takerFee
        val position = Position(
            id = "P${state.nextPositionId}",
            symbol = setup.symbol,
            strategy = setup.strategy,
            openedAt = now,
            entryPrice = fill,
            quantity = quantity,
            entryFee = fee,
            stopPrice = setup.stopPrice,
            targetPrice = setup.targetPrice,
            invalidationPrice = setup.invalidationPrice,
            riskAmount = riskAmount,
            qualityScore = qualityScore,
            setup = setup,
            rationale = rationale,
            ruleReport = report,
        )
        val decision = Decision(
            now, setup.symbol, setup.strategy, DecisionOutcome.OPENED,
            "${setup.symbol} alındı (${setup.strategy.title}) @ ${"%.6g".format(fill)}",
            listOf(rationale.whyEntry, rationale.stopReason, rationale.riskReward),
            qualityScore,
        )
        return log(
            state.copy(cash = state.cash - fill * quantity - fee, positions = state.positions + position, nextPositionId = state.nextPositionId + 1),
            decision,
        )
    }

    /** Pozisyonu [exitReference] fiyattan kapatır. [alreadyAdjusted] backtestte boşluk dolumu için kullanılır. */
    fun close(
        state: PortfolioState,
        positionId: String,
        exitReference: Double,
        reason: ExitReason,
        note: String,
        costs: CostModel,
        now: Long,
    ): PortfolioState {
        val position = state.positions.firstOrNull { it.id == positionId } ?: return state
        val fill = costs.sellPrice(exitReference)
        val fee = fill * position.quantity * costs.takerFee
        val trade = ClosedTrade(position, now, fill, fee, reason, note)
        val decision = Decision(
            now, position.symbol, position.strategy, DecisionOutcome.CLOSED,
            "${position.symbol} kapandı: ${reason.title}, net %+.2f USDT (%+.2fR)".format(trade.netPnl, trade.rMultiple),
            listOf(note),
        )
        return log(
            state.copy(
                cash = state.cash + trade.proceeds,
                positions = state.positions.filterNot { it.id == positionId },
                closedTrades = state.closedTrades + trade,
            ),
            decision,
        )
    }

    fun log(state: PortfolioState, decision: Decision): PortfolioState =
        state.copy(decisions = (state.decisions + decision).takeLast(MAX_DECISIONS))

    /** UTC gün değiştiyse gün başı özsermayesini yeniler (günlük kayıp limiti için). */
    fun rollDay(state: PortfolioState, now: Long, equity: Double): PortfolioState {
        val day = Math.floorDiv(now, DAY) * DAY
        return if (day != state.dayStart) state.copy(dayStart = day, dayStartEquity = equity) else state
    }

    fun recordEquity(state: PortfolioState, now: Long, equity: Double, minIntervalMillis: Long = 60 * 60_000L): PortfolioState {
        val last = state.equityHistory.lastOrNull()
        if (last != null && now - last.time < minIntervalMillis) return state
        return state.copy(equityHistory = (state.equityHistory + EquityPoint(now, equity)).takeLast(MAX_EQUITY_POINTS))
    }

    fun withPrices(state: PortfolioState, bids: Map<String, Double>): PortfolioState =
        if (state.positions.none { bids.containsKey(it.symbol) }) state
        else state.copy(positions = state.positions.map { p -> bids[p.symbol]?.let { p.copy(lastPrice = it) } ?: p })
}

/** Bir pozisyonun fiyat güncellemesinde çıkış koşulu. */
object ExitRules {
    fun onPrice(position: Position, bid: Double): ExitReason? = when {
        bid <= position.stopPrice -> ExitReason.STOP_LOSS
        bid >= position.targetPrice -> ExitReason.TAKE_PROFIT
        else -> null
    }

    /** 1H kapanış geçersizlik seviyesinin altındaysa yapısal çıkış. */
    fun onHourlyClose(position: Position, h1Close: Double): Boolean = h1Close < position.invalidationPrice
}
