package com.spotbot.android.ui.strategy

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotbot.android.R
import com.spotbot.android.data.bot.BotRepository
import com.spotbot.android.data.bot.StrategyUpdateResult
import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.domain.model.StrategyError
import com.spotbot.android.domain.model.TradingPair
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

enum class StrategyEvent(@StringRes val messageRes: Int) {
    SAVED(R.string.strategy_saved),
    BOT_RUNNING(R.string.strategy_error_bot_running),
    POSITION_OPEN(R.string.strategy_error_position_open),
}

class StrategyViewModel(
    private val botRepository: BotRepository,
    marketRepository: MarketRepository,
) : ViewModel() {

    var form by mutableStateOf(StrategyFormState.from(botRepository.state.value.config))
        private set

    val pairs: StateFlow<List<TradingPair>> = marketRepository.tickers
        .map { tickers -> tickers.map { it.pair } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), marketRepository.tickers.value.map { it.pair })

    val isBotRunning: StateFlow<Boolean> = botRepository.state
        .map { it.isRunning }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), botRepository.state.value.isRunning)

    private val _events = Channel<StrategyEvent>(Channel.BUFFERED)
    val events: Flow<StrategyEvent> = _events.receiveAsFlow()

    fun onPairSelected(pair: TradingPair) {
        form = form.copy(pair = pair)
    }

    fun onOrderAmountChange(value: String) {
        form = form.copy(orderAmount = value, errors = form.errors - StrategyError.ORDER_AMOUNT_TOO_LOW)
    }

    fun onTakeProfitChange(value: String) {
        form = form.copy(takeProfitPercent = value, errors = form.errors - StrategyError.TAKE_PROFIT_OUT_OF_RANGE)
    }

    fun onStopLossChange(value: String) {
        form = form.copy(stopLossPercent = value, errors = form.errors - StrategyError.STOP_LOSS_OUT_OF_RANGE)
    }

    fun onSave() {
        val config = when (val result = form.validate()) {
            is StrategyFormResult.Invalid -> {
                form = form.copy(errors = result.errors)
                return
            }
            is StrategyFormResult.Valid -> result.config
        }
        val event = when (val update = botRepository.updateStrategy(config)) {
            StrategyUpdateResult.Saved -> {
                form = StrategyFormState.from(config)
                StrategyEvent.SAVED
            }
            StrategyUpdateResult.BotRunning -> StrategyEvent.BOT_RUNNING
            StrategyUpdateResult.PositionOpenOnOtherPair -> StrategyEvent.POSITION_OPEN
            is StrategyUpdateResult.Invalid -> {
                form = form.copy(errors = update.errors.toSet())
                return
            }
        }
        _events.trySend(event)
    }
}
