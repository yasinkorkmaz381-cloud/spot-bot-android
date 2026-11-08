package com.spotbot.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotbot.android.data.bot.BotRepository
import com.spotbot.android.domain.trading.PaperTrader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal

data class SettingsUiState(
    val initialBalance: BigDecimal,
    val quoteAsset: String,
    val feeRate: BigDecimal = PaperTrader.FEE_RATE,
)

class SettingsViewModel(private val botRepository: BotRepository) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = botRepository.state
        .map { SettingsUiState(it.account.initialBalance, it.account.quoteAsset) }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = botRepository.state.value.account.let {
                SettingsUiState(it.initialBalance, it.quoteAsset)
            },
        )

    fun onResetSimulation() {
        botRepository.resetSimulation()
    }
}
