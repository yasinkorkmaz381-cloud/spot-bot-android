package com.spotbot.android.ui.panel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotbot.android.data.SettingsRepository
import com.spotbot.android.ui.BotController
import com.spotbot.core.engine.EngineHealth
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.risk.CostModel
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.Decision
import com.spotbot.core.trading.Performance
import com.spotbot.core.trading.PerformanceSummary
import com.spotbot.core.trading.Position
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

data class PositionRow(val position: Position, val bid: Double?, val pnl: Double?, val r: Double?)

data class PanelUiState(
    val health: EngineHealth = EngineHealth(),
    val equity: Double = 0.0,
    val cash: Double = 0.0,
    val initialCapital: Double = 0.0,
    val unrealized: Double = 0.0,
    val performance: PerformanceSummary? = null,
    val positions: List<PositionRow> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val enabledStrategies: Set<StrategyId> = emptySet(),
    val equityHistory: List<Double> = emptyList(),
    val risk: com.spotbot.core.trading.RiskStatus = com.spotbot.core.trading.RiskStatus(),
    val pendingOrders: List<com.spotbot.core.trading.PendingOrder> = emptyList(),
) {
    val isActive: Boolean get() = health.status == EngineStatus.RUNNING || health.status == EngineStatus.STARTING
}

@OptIn(FlowPreview::class)
class PanelViewModel(
    private val engine: TradingEngine,
    settings: SettingsRepository,
    private val controller: BotController,
) : ViewModel() {

    val uiState: StateFlow<PanelUiState> = combine(
        engine.portfolio,
        engine.health,
        engine.prices.sample(1_000),
        settings.settings,
        engine.riskStatus,
    ) { portfolio, health, prices, appSettings, risk ->
        val costs = CostModel(slippage = appSettings.engine.slippage)
        val rows = portfolio.positions.map { p ->
            val bid = prices[p.symbol]?.bid
            val pnl = bid?.let { p.unrealizedPnl(it, costs) }
            PositionRow(p, bid, pnl, pnl?.let { if (p.riskAmount > 0) it / p.riskAmount else null })
        }
        val unrealized = rows.sumOf { it.pnl ?: 0.0 }
        val positionValue = portfolio.positions.sumOf { p -> prices[p.symbol]?.bid?.let { p.marketValue(it, costs) } ?: p.cost }
        PanelUiState(
            health = health,
            equity = portfolio.cash + positionValue,
            cash = portfolio.cash,
            initialCapital = portfolio.initialCapital,
            unrealized = unrealized,
            performance = Performance.summarize(portfolio, System.currentTimeMillis()),
            positions = rows,
            decisions = portfolio.decisions.takeLast(25).reversed(),
            enabledStrategies = appSettings.engine.enabledStrategies,
            equityHistory = portfolio.equityHistory.takeLast(24 * 30).map { it.equity },
            risk = risk,
            pendingOrders = portfolio.pendingOrders,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PanelUiState())

    fun startBot() = controller.start()
    fun stopBot() = controller.stop()
    fun setEntriesPaused(paused: Boolean) = engine.setEntriesPaused(paused)
    fun closePosition(id: String) = engine.closePosition(id)
    fun cancelPending(symbol: String) = engine.cancelPendingOrder(symbol)
}
