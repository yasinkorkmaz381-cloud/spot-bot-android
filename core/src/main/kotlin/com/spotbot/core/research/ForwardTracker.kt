package com.spotbot.core.research

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.klinesBetween
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** V3 araştırmasındaki parite evreni (docs/STRATEJI-V3-ONKAYIT.md). */
val V3_UNIVERSE = listOf(
    "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT", "AVAXUSDT", "DOTUSDT", "LTCUSDT",
    "BCHUSDT", "TRXUSDT", "BNBUSDT", "ATOMUSDT", "NEARUSDT", "UNIUSDT", "FILUSDT", "ETCUSDT", "XLMUSDT", "AAVEUSDT",
    "ARBUSDT", "OPUSDT", "APTUSDT", "SUIUSDT", "INJUSDT", "ZECUSDT", "ENAUSDT", "HBARUSDT",
)

/** İleri testin kalıcı durumu. [startedAt] sabittir; sonuçlar her güncellemede baştan, aynı kurallarla hesaplanır. */
@Serializable
data class ForwardState(
    val strategy: String,
    val option: String,
    val startedAt: Long,
    val capital: Double,
    val updatedAt: Long = 0,
    /** Hesaba katılan son kapanmış 4H mumunun kapanış zamanı. */
    val dataEnd: Long = 0,
    val result: V3Result? = null,
    val symbolsLoaded: Int = 0,
    val tradableNow: Int = 0,
    /** Son güncelleme başarısızsa yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
)

interface ForwardStore {
    fun load(): ForwardState?
    fun save(state: ForwardState)
}

class InMemoryForwardStore(private var state: ForwardState? = null) : ForwardStore {
    override fun load() = state
    override fun save(state: ForwardState) { this.state = state }
}

/**
 * "Umut verici" çıkan V3 stratejisini (S1, L=30) ayrı bir 1.000 USDT sanal portföyde ileriye doğru izler.
 *
 * - Yalnız takip başladıktan ([ForwardState.startedAt]) sonra kapanan 4H mumlarında işlem yapılır; daha önceki veri
 *   yalnız göstergeler ve işlem yapılabilirlik için kullanılır.
 * - Kurallar, maliyet (orta senaryo) ve dolum modeli araştırma motoruyla ([V3Engine]) birebir aynıdır: sinyal 4H
 *   kapanışında, giriş/çıkış sonraki 4H açılışında. Bu nedenle sonuç bir **ileri test**tir; canlı emir defteri
 *   dolumu değildir.
 * - Gerçek emir göndermez; hiçbir API anahtarı kullanmaz.
 */
class ForwardTracker(
    private val api: MarketDataApi,
    private val store: ForwardStore,
    private val clock: TimeSource = TimeSource.System,
    private val strategy: V3Strategy = RelativeStrength(30),
    private val symbols: List<String> = V3_UNIVERSE,
    private val costs: CostScenario = CostScenario.MEDIUM,
    private val capital: Double = 1_000.0,
    /** Göstergeler ve işlem yapılabilirlik (≥ 210 gün) için takip başlangıcından önce yüklenen geçmiş. */
    private val warmupMs: Long = 320 * DAY_MS,
) {
    private val mutex = Mutex()
    private val cache = HashMap<String, List<Candle>>()
    private val _state = MutableStateFlow(store.load()?.takeIf { it.strategy == strategy.name && it.option == strategy.option })
    val state: StateFlow<ForwardState?> = _state.asStateFlow()

    /** Son kapanmış 4H mumunun kapanış zamanı. */
    private fun lastClosed(now: Long) = now / H4_MS * H4_MS

    /** Yeni bir 4H mumu kapandıysa günceller; değilse mevcut durumu döndürür. */
    suspend fun refreshIfDue(): ForwardState = mutex.withLock {
        val now = clock.now()
        val current = _state.value
        if (current != null && current.lastError == null && current.dataEnd >= lastClosed(now)) current else refreshLocked(now)
    }

    suspend fun refresh(): ForwardState = mutex.withLock { refreshLocked(clock.now()) }

    /** İleri testi bugünden yeniden başlatır (eski sonuç silinir). */
    suspend fun restart(): ForwardState = mutex.withLock {
        _state.value = null
        refreshLocked(clock.now())
    }

    private suspend fun refreshLocked(now: Long): ForwardState {
        val base = _state.value ?: ForwardState(strategy.name, strategy.option, startedAt = lastClosed(now), capital = capital)
        val end = lastClosed(now)
        val next = try {
            val loaded = HashMap<String, List<Candle>>()
            val failures = ArrayList<String>()
            for (symbol in symbols) {
                try {
                    loaded[symbol] = load(symbol, base.startedAt - warmupMs, end)
                } catch (e: Exception) {
                    failures += symbol
                }
            }
            if ("BTCUSDT" !in loaded || loaded.getValue("BTCUSDT").isEmpty()) error("BTCUSDT verisi alınamadı")
            val universe = Universe.of(loaded.filterValues { it.isNotEmpty() }.map { H4Dataset(it.key, it.value) })
            val result = if (end > base.startedAt) {
                V3Engine(universe, V3Config(base.startedAt, end, costs, capital)).run(strategy, closeAtEnd = false)
            } else null
            base.copy(
                updatedAt = now,
                dataEnd = end,
                result = result,
                symbolsLoaded = universe.symbols.size,
                tradableNow = universe.symbols.values.count { it.tradable(end, 1_000_000.0) },
                lastError = if (failures.isEmpty()) null else "Veri alınamayan pariteler: ${failures.joinToString()}",
            )
        } catch (e: Exception) {
            base.copy(updatedAt = now, lastError = "Güncelleme başarısız: ${e.message ?: e::class.simpleName}")
        }
        _state.value = next
        store.save(next)
        return next
    }

    /** Önbellekten devam ederek yalnız eksik (kapanmış) 4H mumlarını indirir. */
    private suspend fun load(symbol: String, from: Long, end: Long): List<Candle> {
        val have = cache[symbol].orEmpty().filter { it.openTime >= from }
        val start = if (have.isEmpty()) from else have.last().openTime + H4_MS
        val fresh = if (start < end) api.klinesBetween(symbol, Timeframe.H4, start, end) else emptyList()
        val merged = (have + fresh).filter { it.openTime + H4_MS <= end }.distinctBy { it.openTime }.sortedBy { it.openTime }
        cache[symbol] = merged
        return merged
    }
}
