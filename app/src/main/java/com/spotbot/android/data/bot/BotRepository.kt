package com.spotbot.android.data.bot

import com.spotbot.android.domain.model.BotState
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import kotlinx.coroutines.flow.StateFlow

/** Botu yöneten kaynak. Ana iş parçacığından çağrılmak üzere tasarlanmıştır. */
interface BotRepository {
    val state: StateFlow<BotState>

    fun start()

    fun stop()

    fun updateStrategy(config: StrategyConfig): StrategyUpdateResult

    /** Botu durdurur; bakiyeyi ve işlem geçmişini sıfırlar, strateji ayarlarını korur. */
    fun resetSimulation()
}

sealed interface StrategyUpdateResult {
    data object Saved : StrategyUpdateResult
    data object BotRunning : StrategyUpdateResult
    data object PositionOpenOnOtherPair : StrategyUpdateResult
    data class Invalid(val errors: List<StrategyError>) : StrategyUpdateResult
}
