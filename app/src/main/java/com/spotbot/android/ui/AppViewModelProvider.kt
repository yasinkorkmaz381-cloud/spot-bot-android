package com.spotbot.android.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spotbot.android.SpotBotApplication
import com.spotbot.android.ui.dashboard.DashboardViewModel
import com.spotbot.android.ui.markets.MarketsViewModel
import com.spotbot.android.ui.settings.SettingsViewModel
import com.spotbot.android.ui.strategy.StrategyViewModel

/** Tüm ViewModel'leri [com.spotbot.android.di.AppContainer] bağımlılıklarıyla oluşturan fabrika. */
object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val container = spotBotApplication().container
            DashboardViewModel(container.botRepository, container.marketRepository)
        }
        initializer {
            MarketsViewModel(spotBotApplication().container.marketRepository)
        }
        initializer {
            val container = spotBotApplication().container
            StrategyViewModel(container.botRepository, container.marketRepository)
        }
        initializer {
            SettingsViewModel(spotBotApplication().container.botRepository)
        }
    }
}

private fun CreationExtras.spotBotApplication(): SpotBotApplication =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SpotBotApplication
