package com.spotbot.android.data.market

import com.spotbot.android.domain.model.MarketTicker
import com.spotbot.android.domain.model.TradingPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Rastgele yürüyüşle fiyat üreten sahte piyasa. Fiyatlar yalnızca [tickers] dinlenirken güncellenir;
 * böylece uygulama arka plandayken ve bot dururken boşuna çalışmaz.
 */
class SimulatedMarketRepository(
    scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val tickIntervalMillis: Long = 2_000L,
    initialTickers: List<MarketTicker> = DEFAULT_TICKERS,
) : MarketRepository {

    private val _tickers = MutableStateFlow(initialTickers)
    override val tickers: StateFlow<List<MarketTicker>> = _tickers.asStateFlow()

    init {
        scope.launch {
            _tickers.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { hasSubscribers ->
                    if (hasSubscribers) {
                        while (true) {
                            delay(tickIntervalMillis)
                            _tickers.update { list -> list.map(::nextTick) }
                        }
                    }
                }
        }
    }

    private fun nextTick(ticker: MarketTicker): MarketTicker {
        val change = random.nextDouble(-MAX_STEP, MAX_STEP)
        val newPrice = ticker.lastPrice
            .multiply(BigDecimal.valueOf(1 + change))
            .setScale(PRICE_SCALE, RoundingMode.HALF_UP)
        return ticker.copy(lastPrice = newPrice)
    }

    companion object {
        /** Her adımda en fazla ±%0,25 fiyat değişimi. */
        private const val MAX_STEP = 0.0025
        private const val PRICE_SCALE = 8

        /** Başlangıç fiyatları temsilidir; gerçek piyasa fiyatlarını yansıtmaz. */
        val DEFAULT_TICKERS: List<MarketTicker> = listOf(
            "BTC" to "95000",
            "ETH" to "3500",
            "BNB" to "650",
            "SOL" to "180",
            "XRP" to "2.4",
            "ADA" to "0.75",
            "DOGE" to "0.2",
            "AVAX" to "35",
        ).map { (base, price) ->
            val seed = BigDecimal(price)
            MarketTicker(TradingPair(base, "USDT"), lastPrice = seed, openPrice = seed)
        }
    }
}
