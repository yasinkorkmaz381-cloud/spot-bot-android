package com.spotbot.android.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotbot.android.data.bot.BotRepository
import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.domain.model.BotState
import com.spotbot.android.domain.model.MarketTicker
import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.Trade
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal

data class DashboardUiState(
    val isRunning: Boolean,
    val config: StrategyConfig,
    val quoteAsset: String,
    val currentPrice: BigDecimal?,
    val totalValue: BigDecimal,
    val quoteBalance: BigDecimal,
    val position: Position?,
    val pnl: BigDecimal,
    val pnlPercent: Double,
    val recentTrades: List<Trade>,
)

class DashboardViewModel(
    private val botRepository: BotRepository,
    marketRepository: MarketRepository,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> =
        combine(botRepository.state, marketRepository.tickers, ::toUiState)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = toUiState(botRepository.state.value, marketRepository.tickers.value),
            )

    fun onToggleBot() {
        if (botRepository.state.value.isRunning) botRepository.stop() else botRepository.start()
    }

    companion object {
        const val RECENT_TRADE_COUNT = 20
    }
}

private fun toUiState(bot: BotState, tickers: List<MarketTicker>): DashboardUiState {
    val prices = tickers.associate { it.pair to it.lastPrice }
    val portfolio = bot.account.valuate { prices[it] }
    return DashboardUiState(
        isRunning = bot.isRunning,
        config = bot.config,
        quoteAsset = bot.account.quoteAsset,
        currentPrice = prices[bot.config.pair],
        totalValue = portfolio.totalValue,
        quoteBalance = bot.account.quoteBalance,
        position = bot.account.position,
        pnl = portfolio.pnl,
        pnlPercent = portfolio.pnlPercent,
        recentTrades = bot.trades.take(DashboardViewModel.RECENT_TRADE_COUNT),
    )
}
