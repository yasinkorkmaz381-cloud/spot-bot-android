package com.spotbot.android.data

import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.domain.model.MarketTicker
import com.spotbot.android.domain.model.TradingPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.math.BigDecimal

class FakeMarketRepository(initial: List<MarketTicker>) : MarketRepository {
    private val _tickers = MutableStateFlow(initial)
    override val tickers: StateFlow<List<MarketTicker>> = _tickers.asStateFlow()

    fun setPrice(pair: TradingPair, price: String) {
        _tickers.update { list ->
            list.map { if (it.pair == pair) it.copy(lastPrice = BigDecimal(price)) else it }
        }
    }
}
