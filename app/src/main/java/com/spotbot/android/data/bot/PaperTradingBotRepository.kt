package com.spotbot.android.data.bot

import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.domain.model.BotState
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.trading.PaperTrader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Clock

/**
 * Simüle piyasa fiyatlarıyla sanal işlem yapan bot. Durum değişiklikleri [PaperTrader]'a devredilir.
 *
 * Not: Bot uygulama süreci yaşadığı sürece çalışır. Kesintisiz çalışma için ileride
 * bir foreground service veya sunucu tarafı çalıştırma gerekecek.
 */
class PaperTradingBotRepository(
    private val marketRepository: MarketRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val initialBalance: BigDecimal = DEFAULT_INITIAL_BALANCE,
) : BotRepository {

    private val _state = MutableStateFlow(BotState.initial(initialBalance))
    override val state: StateFlow<BotState> = _state.asStateFlow()

    private var tradingJob: Job? = null

    override fun start() {
        if (tradingJob?.isActive == true) return
        _state.update { it.copy(isRunning = true) }
        val pair = _state.value.config.pair
        tradingJob = scope.launch {
            marketRepository.tickers
                .mapNotNull { tickers -> tickers.firstOrNull { it.pair == pair }?.lastPrice }
                .distinctUntilChanged()
                .collect { price ->
                    _state.update { PaperTrader.onPrice(it, price, clock.instant()) }
                }
        }
    }

    override fun stop() {
        tradingJob?.cancel()
        tradingJob = null
        _state.update { it.copy(isRunning = false) }
    }

    override fun updateStrategy(config: StrategyConfig): StrategyUpdateResult {
        val errors = config.validate()
        if (errors.isNotEmpty()) return StrategyUpdateResult.Invalid(errors)

        val current = _state.value
        if (current.isRunning) return StrategyUpdateResult.BotRunning
        val openPair = current.account.position?.pair
        if (openPair != null && openPair != config.pair) return StrategyUpdateResult.PositionOpenOnOtherPair

        _state.update { it.copy(config = config) }
        return StrategyUpdateResult.Saved
    }

    override fun resetSimulation() {
        stop()
        _state.update { BotState.initial(initialBalance, config = it.config) }
    }

    companion object {
        val DEFAULT_INITIAL_BALANCE: BigDecimal = BigDecimal("1000")
    }
}
