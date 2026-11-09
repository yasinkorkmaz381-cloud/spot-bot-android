package com.spotbot.android.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spotbot.android.AppContainer
import com.spotbot.android.SpotBotApplication
import com.spotbot.android.bot.BotService
import com.spotbot.android.ui.backtest.BacktestViewModel
import com.spotbot.android.ui.detail.CoinDetailViewModel
import com.spotbot.android.ui.panel.PanelViewModel
import com.spotbot.android.ui.scan.ScanViewModel
import com.spotbot.android.ui.settings.SettingsViewModel
import com.spotbot.android.ui.trades.TradesViewModel

/** Ön plan servisini başlatıp durdurur (ViewModel'lerin Context tutmaması için). */
class BotController(private val app: Application) {
    fun start() = BotService.start(app)
    fun stop() = BotService.stop(app)
}

object AppViewModels {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { PanelViewModel(container().engine, container().settings, BotController(application())) }
        initializer { ScanViewModel(container().engine) }
        initializer { CoinDetailViewModel(container().engine, container().api, createSavedStateHandle()) }
        initializer { TradesViewModel(container().engine, container().api) }
        initializer { BacktestViewModel(container().backtest, container().settings) }
        initializer { SettingsViewModel(container().engine, container().settings) }
    }

    private fun CreationExtras.application(): SpotBotApplication =
        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SpotBotApplication

    private fun CreationExtras.container(): AppContainer = application().container
}
