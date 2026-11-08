package com.spotbot.android.ui.markets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotbot.android.data.market.MarketRepository
import com.spotbot.android.domain.model.MarketTicker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class MarketsViewModel(marketRepository: MarketRepository) : ViewModel() {

    /** Arama metni; TextField ile senkron kalması için Compose state olarak tutulur. */
    var query by mutableStateOf("")
        private set

    val tickers: StateFlow<List<MarketTicker>> =
        combine(marketRepository.tickers, snapshotFlow { query }, ::filterTickers)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = marketRepository.tickers.value,
            )

    fun onQueryChange(value: String) {
        query = value
    }
}

/** "btc", "BTC/USDT" veya "btcusdt" gibi aramaları sembolle eşleştirir. */
internal fun filterTickers(tickers: List<MarketTicker>, query: String): List<MarketTicker> {
    val normalized = query.trim().replace("/", "").uppercase()
    if (normalized.isEmpty()) return tickers
    return tickers.filter { it.pair.symbol.contains(normalized) }
}
