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
    /** R hesabının dayandığı ilk stop (başa baş taşımasından etkilenmez). */
    val initialStopPrice: Double = stopPrice,
    val initialQuantity: Double = quantity,
    /** Kısmi çıkışlardan gerçekleşmiş net kâr/zarar (ücretler dahil). */
    val realizedPnl: Double = 0.0,
    /** Kısmi çıkışlarda ödenen ücretler (giriş ücretinin ilgili payı dahil). */
    val partialFees: Double = 0.0,
    val partialTaken: Boolean = false,
    val breakEvenSet: Boolean = false,
    /** Çıkış tetiklendi ama likidite yetmediği için kısmen gerçekleşti; kalan miktar ilk fırsatta satılır. */
    val closingReason: ExitReason? = null,
) {
    /** Kalan miktarın maliyeti (giriş ücretinin kalan payı dahil). */
    val cost: Double get() = entryPrice * quantity + entryFee

    /** Stop gerçekleşirse kalan miktarın kaybı (USDT). Başa baş/kısmi kâr sonrası küçülür; en az 0. */
    val openRisk: Double
        get() = if (!breakEvenSet && quantity >= initialQuantity * (1 - 1e-9)) riskAmount
        else maxOf(0.0, quantity * (entryPrice - stopPrice) + entryFee)

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
    BREAK_EVEN("Başa baş stop"),
    TAKE_PROFIT("Kâr hedefi"),
    TIME_EXIT("Süre doldu"),
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
    /** Kalan miktarın sonucu + kısmi çıkışlardan gerçekleşen kâr/zarar. */
    val netPnl: Double get() = proceeds - position.cost + position.realizedPnl
    val rMultiple: Double get() = if (position.riskAmount > 0) netPnl / position.riskAmount else 0.0
    val fees: Double get() = position.entryFee + exitFee + position.partialFees

    /** Tüm satışların (kısmi çıkışlar dahil) ortalama fiyatı: net sonuç + giriş maliyeti + ücretler, ilk miktara bölünür. */
    val averageExitPrice: Double get() =
        if (position.initialQuantity > 0) (netPnl + position.entryPrice * position.initialQuantity + fees) / position.initialQuantity else exitPrice
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
    val pendingOrders: List<PendingOrder> = emptyList(),
    /** Motorun son "canlıyım" zamanı (çevrimdışı süreyi ölçmek için). */
    val lastHeartbeat: Long = 0,
    val downtimes: List<Downtime> = emptyList(),
    /** Kullanıcı botu kapattığında yazılır (çevrimdışı süreyi "beklenmedik" ile ayırmak için). */
    val stoppedByUserAt: Long = 0,
    /** Motorun ilk başlatıldığı zaman (çalışma oranı penceresi için). */
    val firstStartedAt: Long = 0,
) {
    /** Bekleyen limit emirler için ayrılmamış nakit. */
    val availableCash: Double get() = cash - pendingOrders.sumOf { it.reservedQuote }

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
        maker: Boolean = false,
    ): PortfolioState {
        // Limit (maker) emir tam limit fiyatından dolar; piyasa emri spread ve kayma öder.
        val fill = if (maker) askReference else costs.buyPrice(askReference)
        val fee = fill * quantity * (if (maker) costs.makerFee else costs.takerFee)
        if (fill * quantity + fee > state.availableCash + 1e-9) {
            return log(state, Decision(now, setup.symbol, setup.strategy, DecisionOutcome.REJECTED, "${setup.symbol}: bakiye yetersiz, emir reddedildi"))
        }
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

    /**
     * Gerçekleşmiş bir dolumla pozisyon açar. Nakit (bekleyen emirler için ayrılan hariç) yetmezse açmaz.
     * [fromPending] bekleyen emrin dolumudur; o emrin ayırdığı nakit kullanılabilir.
     */
    fun openWithFill(
        state: PortfolioState,
        setup: TradeSetup,
        fill: Fill,
        riskAmount: Double,
        qualityScore: Double,
        rationale: TradeRationale,
        report: RuleReport?,
        now: Long,
        fromPending: PendingOrder? = null,
    ): PortfolioState {
        val spend = fill.averagePrice * fill.quantity + fill.fee
        val usable = state.availableCash + (fromPending?.reservedQuote ?: 0.0)
        if (fill.quantity <= 0 || spend > usable + 1e-9) {
            return log(
                state.copy(pendingOrders = state.pendingOrders - listOfNotNull(fromPending).toSet()),
                Decision(now, setup.symbol, setup.strategy, DecisionOutcome.REJECTED, "${setup.symbol}: bakiye yetersiz, emir reddedildi", listOf("Gereken %.2f USDT, kullanılabilir %.2f USDT".format(spend, usable))),
            )
        }
        val scaledRisk = if (fill.requestedQuantity > 0) riskAmount * fill.quantity / fill.requestedQuantity else riskAmount
        val position = Position(
            id = "P${state.nextPositionId}", symbol = setup.symbol, strategy = setup.strategy, openedAt = now,
            entryPrice = fill.averagePrice, quantity = fill.quantity, entryFee = fill.fee,
            stopPrice = setup.stopPrice, targetPrice = setup.targetPrice, invalidationPrice = setup.invalidationPrice,
            riskAmount = scaledRisk, qualityScore = qualityScore, setup = setup, rationale = rationale, ruleReport = report,
        )
        val details = listOfNotNull(rationale.whyEntry, rationale.stopReason, rationale.riskReward, fill.note.takeIf { it.isNotEmpty() })
        val decision = Decision(
            now, setup.symbol, setup.strategy, DecisionOutcome.OPENED,
            "${setup.symbol} alındı (${setup.strategy.title}) @ ${"%.6g".format(fill.averagePrice)}${if (fill.partial) " — kısmi" else ""}",
            details, qualityScore,
        )
        return log(
            state.copy(
                cash = state.cash - spend,
                positions = state.positions + position,
                nextPositionId = state.nextPositionId + 1,
                pendingOrders = state.pendingOrders - listOfNotNull(fromPending).toSet(),
            ),
            decision,
        )
    }

    /** Gerçekleşmiş bir satış dolumu: tamamıysa kapatır, kısmiyse kalan miktar açık kalır. */
    fun exitWithFill(state: PortfolioState, positionId: String, fill: Fill, reason: ExitReason, note: String, now: Long): PortfolioState {
        val position = state.positions.firstOrNull { it.id == positionId } ?: return state
        if (fill.quantity >= position.quantity * (1 - 1e-9)) {
            val trade = ClosedTrade(position, now, fill.averagePrice, fill.fee, reason, listOf(note, fill.note).filter { it.isNotEmpty() }.joinToString(" · "))
            val decision = Decision(
                now, position.symbol, position.strategy, DecisionOutcome.CLOSED,
                "${position.symbol} kapandı: ${reason.title}, net %+.2f USDT (%+.2fR)".format(trade.netPnl, trade.rMultiple), listOf(trade.note),
            )
            return log(state.copy(cash = state.cash + trade.proceeds, positions = state.positions.filterNot { it.id == positionId }, closedTrades = state.closedTrades + trade), decision)
        }
        val fraction = fill.quantity / position.quantity
        val makerCosts = CostModel(takerFee = 0.0, slippage = 0.0, halfSpread = 0.0, makerFee = 0.0)
        // Ücret dolumda hesaplandığından kısmi kapatma ücretsiz modelle yapılır, ardından ücret düşülür.
        val partial = partialClose(state, positionId, fraction, fill.averagePrice, maker = true, note = "${reason.title} — ${fill.note}", costs = makerCosts, now = now)
        // Likidite yetmediği için kısmi kalan çıkış: kalan miktar ilk fırsatta satılmaya devam eder.
        return partial.copy(
            cash = partial.cash - fill.fee,
            positions = partial.positions.map { p ->
                if (p.id == positionId) {
                    p.copy(
                        realizedPnl = p.realizedPnl - fill.fee,
                        partialFees = p.partialFees + fill.fee,
                        closingReason = reason,
                        partialTaken = position.partialTaken,
                    )
                } else p
            },
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
        maker: Boolean = false,
    ): PortfolioState {
        val position = state.positions.firstOrNull { it.id == positionId } ?: return state
        val fill = if (maker) exitReference else costs.sellPrice(exitReference)
        val fee = fill * position.quantity * (if (maker) costs.makerFee else costs.takerFee)
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

    /** Pozisyonun [fraction] kadarını kapatır; gerçekleşen sonuç pozisyonda biriktirilir. */
    fun partialClose(
        state: PortfolioState,
        positionId: String,
        fraction: Double,
        exitReference: Double,
        maker: Boolean,
        note: String,
        costs: CostModel,
        now: Long,
    ): PortfolioState {
        val p = state.positions.firstOrNull { it.id == positionId } ?: return state
        val qty = p.quantity * fraction.coerceIn(0.0, 1.0)
        if (qty <= 0) return state
        val fill = if (maker) exitReference else costs.sellPrice(exitReference)
        val exitFee = fill * qty * (if (maker) costs.makerFee else costs.takerFee)
        val entryFeeShare = p.entryFee * fraction
        val pnl = fill * qty - exitFee - (p.entryPrice * qty + entryFeeShare)
        val updated = p.copy(
            quantity = p.quantity - qty,
            entryFee = p.entryFee - entryFeeShare,
            realizedPnl = p.realizedPnl + pnl,
            partialFees = p.partialFees + entryFeeShare + exitFee,
            partialTaken = true,
        )
        val decision = Decision(now, p.symbol, p.strategy, DecisionOutcome.INFO, "${p.symbol}: %%%.0f kısmi kâr alındı (%+.2f USDT)".format(fraction * 100, pnl), listOf(note))
        return log(
            state.copy(cash = state.cash + fill * qty - exitFee, positions = state.positions.map { if (it.id == positionId) updated else it }),
            decision,
        )
    }

    fun moveStop(state: PortfolioState, positionId: String, newStop: Double, note: String, now: Long): PortfolioState {
        val p = state.positions.firstOrNull { it.id == positionId } ?: return state
        if (newStop <= p.stopPrice) return state
        val decision = Decision(now, p.symbol, p.strategy, DecisionOutcome.INFO, "${p.symbol}: stop ${"%.6g".format(newStop)} seviyesine taşındı", listOf(note))
        return log(state.copy(positions = state.positions.map { if (it.id == positionId) it.copy(stopPrice = newStop, breakEvenSet = true) else it }), decision)
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
