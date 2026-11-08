package com.spotbot.android.di

import com.spotbot.android.data.bot.BotRepository
import com.spotbot.android.data.bot.PaperTradingBotRepository
import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.data.market.SimulatedMarketRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Basit, elle kurulan bağımlılık kabı. Proje büyüdüğünde Hilt/Koin'e geçilebilir. */
class AppContainer {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val marketRepository: MarketRepository = SimulatedMarketRepository(applicationScope)

    val botRepository: BotRepository = PaperTradingBotRepository(marketRepository, applicationScope)
}
