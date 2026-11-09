package com.spotbot.core.exchange

import com.spotbot.core.exchange.mexc.ConnectionStatus
import com.spotbot.core.exchange.mexc.MexcChannels
import com.spotbot.core.exchange.mexc.MexcWebSocketClient
import com.spotbot.core.exchange.mexc.StreamEvent
import com.spotbot.core.model.BookTicker
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class FeedHealth(
    val webSocket: ConnectionStatus = ConnectionStatus.STOPPED,
    val restFallbackActive: Boolean = false,
    val lastUpdateAt: Long? = null,
    val lastVerificationAt: Long? = null,
    /** Son doğrulamada WebSocket ile REST arasındaki en büyük fiyat sapması (%). */
    val lastMaxDeviationPercent: Double? = null,
    val lastError: String? = null,
    /** Başlangıçtan bu yana WebSocket yeniden bağlanma sayısı. */
    val reconnects: Int = 0,
)

/**
 * Canlı en iyi alış/satış fiyatları. Öncelik WebSocket'tedir; WebSocket kopuksa ya da bir paritenin
 * fiyatı [staleAfterMillis] süresince güncellenmezse REST ile doldurulur. Ayrıca belirli aralıklarla
 * WebSocket fiyatları REST ile karşılaştırılarak doğrulanır.
 */
class LivePriceFeed(
    private val api: MarketDataApi,
    private val webSocket: MexcWebSocketClient,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource = TimeSource.System,
    private val staleAfterMillis: Long = 15_000,
    private val restPollMillis: Long = 5_000,
    private val verifyEveryMillis: Long = 60_000,
) {
    private val _prices = MutableStateFlow<Map<String, BookTicker>>(emptyMap())
    val prices: StateFlow<Map<String, BookTicker>> = _prices.asStateFlow()

    private val _health = MutableStateFlow(FeedHealth())
    val health: StateFlow<FeedHealth> = _health.asStateFlow()

    private val watched = MutableStateFlow<Set<String>>(emptySet())
    private val wsUpdatedAt = HashMap<String, Long>()
    private val jobs = mutableListOf<Job>()

    fun watch(symbols: Set<String>) {
        watched.value = symbols
        webSocket.setSubscriptions(symbols.map(MexcChannels::bookTicker).toSet())
    }

    fun start() {
        if (jobs.isNotEmpty()) return
        webSocket.start()
        jobs += scope.launch {
            webSocket.events.collect { event ->
                if (event is StreamEvent.BookTickerUpdate) {
                    val ticker = event.ticker
                    synchronized(wsUpdatedAt) { wsUpdatedAt[ticker.symbol] = timeSource.now() }
                    _prices.update { it + (ticker.symbol to ticker) }
                    _health.update { it.copy(lastUpdateAt = timeSource.now()) }
                }
            }
        }
        jobs += scope.launch {
            webSocket.state.collect { s -> _health.update { it.copy(webSocket = s.status, reconnects = s.reconnects) } }
        }
        jobs += scope.launch { restLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        webSocket.stop()
    }

    private suspend fun restLoop() {
        var lastVerification = 0L
        while (scope.isActive) {
            val now = timeSource.now()
            val symbols = watched.value
            val stale = symbols.filter { s ->
                val at = synchronized(wsUpdatedAt) { wsUpdatedAt[s] }
                at == null || now - at > staleAfterMillis
            }
            val verify = now - lastVerification >= verifyEveryMillis
            if (symbols.isNotEmpty() && (stale.isNotEmpty() || verify)) {
                runCatching { api.bookTickers().filter { it.symbol in symbols }.associateBy { it.symbol } }
                    .onSuccess { rest ->
                        if (stale.isNotEmpty()) {
                            _prices.update { current -> current + stale.mapNotNull { s -> rest[s]?.let { s to it } } }
                        }
                        if (verify) {
                            lastVerification = now
                            val deviation = _prices.value.mapNotNull { (s, live) ->
                                val r = rest[s] ?: return@mapNotNull null
                                if (s in stale || r.mid <= 0) null else abs(live.mid - r.mid) / r.mid * 100
                            }.maxOrNull()
                            _health.update { it.copy(lastVerificationAt = now, lastMaxDeviationPercent = deviation) }
                        }
                        _health.update {
                            it.copy(
                                restFallbackActive = stale.isNotEmpty(),
                                lastUpdateAt = if (stale.isNotEmpty()) now else it.lastUpdateAt,
                                lastError = null,
                            )
                        }
                    }
                    .onFailure { e -> _health.update { it.copy(lastError = e.message) } }
            }
            delay(restPollMillis)
        }
    }
}
