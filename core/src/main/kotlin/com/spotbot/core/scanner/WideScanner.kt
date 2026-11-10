package com.spotbot.core.scanner

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Timeframe
import com.spotbot.core.research.DAY_MS
import com.spotbot.core.ta.Indicators
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Geniş evren taramasında bir paritenin elenme nedeni (sırayla uygulanır; ilk tutan neden kaydedilir). */
enum class WideReject(val title: String) {
    PRODUCT("Desteklenmeyen ürün (stablecoin, tokenleştirilmiş hisse, emtia, kaldıraçlı)"),
    NOT_TRADING("Borsada işlem kapalı ya da tek yönlü"),
    RISK_FLAG("MEXC risk etiketi (ST)"),
    LOW_VOLUME_24H("24 saatlik hacim < 250 bin USDT"),
    WIDE_SPREAD("Spread > %1 ya da fiyat yok"),
    DATA_ERROR("Mum verisi hatalı / eksik"),
    SHORT_HISTORY("210 günden kısa geçmiş (yeni listeleme)"),
    LOW_MEDIAN_VOLUME("30 günlük medyan hacim < 1M USDT"),
    TREND("Trend koşulu yok (kapanış ≤ EMA50 ya da EMA50 düşüyor)"),
    WEAK_RS("BTC'ye göre zayıf (30 günlük göreceli güç ≤ 0)"),
}

/** Tek bir paritenin tarama satırı. 1. aşama alanları her uygun parite için, 2. aşama alanları yalnız ilk 20 için. */
@Serializable
data class WideCoin(
    val symbol: String,
    val sectors: List<String>,
    val price: Double,
    val quoteVolume24h: Double,
    val spreadPercent: Double,
    val change24hPercent: Double,
    val momentum7Percent: Double = Double.NaN,
    val momentum30Percent: Double = Double.NaN,
    /** 30 günlük getiri − BTC'nin 30 günlük getirisi (yüzde puan). S1'in sıralama ölçüsü. */
    val relativeStrength: Double = Double.NaN,
    val medianVolume30: Double = 0.0,
    val historyDays: Int = 0,
    val rejection: WideReject? = null,
    // 2. aşama
    val rank: Int? = null,
    val candidate: Boolean = false,
    val atrPercent: Double? = null,
    /** S1 stopu: 3 × ATR14(1D), fiyata oranla yüzde. */
    val stopDistancePercent: Double? = null,
    /** Günlük getirilerin 30 günlük standart sapması, yüzde. */
    val volatilityPercent: Double? = null,
    /** Kapanışın art arda EMA50 üstünde kaldığı gün sayısı. */
    val daysAboveEma: Int? = null,
    /** Orta fiyatın %1 altına kadar alış tarafı derinliği (USDT). */
    val bidDepth1Percent: Double? = null,
    /** 1.000 USDT sanal portföyde %0,5 risk ve 3 ATR stopla yaklaşık pozisyon tutarı (USDT, %30 tavanlı). */
    val positionSize: Double? = null,
    /** Daha üst sıradaki adaylarla 60 günlük getiri korelasyonunun en büyüğü. */
    val maxCorrelation: Double? = null,
    val correlatedWith: String? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class WideScanResult(
    val time: Long,
    /** Hesaba katılan son günlük kapanış (UTC 00:00). */
    val dailyClose: Long,
    val totalPairs: Int,
    val productExcluded: Int,
    val notTradable: Int,
    /** 1D verisi incelenen parite sayısı (24s hacim ve spread ön elemesinden sonra). */
    val analyzed: Int,
    val liquidityPassed: Int,
    val trendPassed: Int,
    val strengthPassed: Int,
    val top: List<WideCoin>,
    val candidates: List<String>,
    val rejections: Map<String, Int>,
    /** Güçlü momentumuna rağmen elenen pariteler (30 günlük getiriye göre ilk 10) ve nedenleri. */
    val strongestRejected: List<WideCoin>,
    val sectorCounts: Map<String, Int>,
    val newListings: List<String> = emptyList(),
    val delisted: List<String> = emptyList(),
    val btcMomentum30Percent: Double = Double.NaN,
    val requests: Int = 0,
    val durationMs: Long = 0,
)

@Serializable
data class WideScanState(
    val knownSymbols: List<String> = emptyList(),
    val result: WideScanResult? = null,
    /** Son taramanın yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
    val updatedAt: Long = 0,
)

interface WideScanStore {
    fun load(): WideScanState?
    fun save(state: WideScanState)
}

class InMemoryWideScanStore(private var state: WideScanState? = null) : WideScanStore {
    override fun load() = state
    override fun save(state: WideScanState) { this.state = state }
}

/**
 * V4 geniş evren taraması (S1 göreceli güç kurallarıyla). Sabit coin listesi kullanmaz: MEXC'deki bütün USDT
 * paritelerini keşfeder.
 *
 * 1. aşama: exchangeInfo + 24s ticker + book ticker (3 toplu istek) ile ürün, işlem durumu, hacim ve spread süzülür;
 *    kalanlar için 1D mumlar günde bir kez indirilir (önbellek) ve likidite, veri, trend, momentum hesaplanır.
 * 2. aşama: BTC'ye göre en güçlü 20 uygun parite için emir defteri derinliği, ATR, volatilite ve korelasyon.
 *
 * Uygunluk ve sıralama backtest'teki S1 kurallarıyla aynıdır; emir defteri, spread ve korelasyon geçmişte
 * ölçülemediği için yalnız uyarı olarak gösterilir, işlem kuralı değildir.
 */
class WideScanner(
    private val api: MarketDataApi,
    private val store: WideScanStore = InMemoryWideScanStore(),
    private val clock: TimeSource = TimeSource.System,
    private val minQuickVolume: Double = 250_000.0,
    private val minMedianVolume: Double = 1_000_000.0,
    private val maxSpreadPercent: Double = 1.0,
    private val stage2Size: Int = 20,
    private val refreshMs: Long = 30 * 60_000L,
) {
    private val mutex = Mutex()
    private val daily = HashMap<String, Pair<Long, List<Candle>>>()
    private val _state = MutableStateFlow(store.load() ?: WideScanState())
    val state: StateFlow<WideScanState> = _state.asStateFlow()

    /** Son taramanın 2. aşama listesi (ileri test bu paritelerin 4H verisini izler). */
    val stage2Symbols: List<String> get() = _state.value.result?.top?.map { it.symbol }.orEmpty()

    suspend fun refreshIfDue(held: Collection<String> = emptyList()): WideScanState = mutex.withLock {
        val s = _state.value
        val now = clock.now()
        if (s.lastError == null && s.result != null && now - s.updatedAt < refreshMs && s.result.dailyClose == now / DAY_MS * DAY_MS) s
        else scanLocked(held)
    }

    suspend fun scan(held: Collection<String> = emptyList()): WideScanState = mutex.withLock { scanLocked(held) }

    private suspend fun scanLocked(held: Collection<String>): WideScanState {
        val started = clock.now()
        val next = try {
            val result = run(held, started)
            val previous = _state.value.knownSymbols.toSet()
            val current = result.second
            val withChanges = result.first.copy(
                newListings = if (previous.isEmpty()) emptyList() else (current - previous).sorted(),
                delisted = if (previous.isEmpty()) emptyList() else (previous - current).sorted(),
                durationMs = clock.now() - started,
            )
            WideScanState(current.sorted(), withChanges, null, started)
        } catch (e: Exception) {
            _state.value.copy(lastError = "Geniş tarama başarısız: ${e.message ?: e::class.simpleName}", updatedAt = started)
        }
        _state.value = next
        store.save(next)
        return next
    }

    private suspend fun run(held: Collection<String>, now: Long): Pair<WideScanResult, Set<String>> {
        var requests = 3
        val info = api.exchangeInfo().filter { it.quoteAsset == "USDT" }
        val tickers = api.tickers24h().associateBy { it.symbol }
        val books = api.bookTickers().associateBy { it.symbol }
        val dailyClose = now / DAY_MS * DAY_MS
        val rejections = LinkedHashMap<WideReject, Int>()
        fun reject(r: WideReject) { rejections[r] = (rejections[r] ?: 0) + 1 }

        val rows = ArrayList<WideCoin>()
        var product = 0
        var notTradable = 0
        val toAnalyze = ArrayList<Pair<SymbolInfo, WideCoin>>()
        for (s in info) {
            if (isUnsupportedProduct(s)) { product++; reject(WideReject.PRODUCT); continue }
            val tradingIssue = when {
                !s.isOnline || !s.isSpotTradingAllowed || s.tradeSideType != 1 -> WideReject.NOT_TRADING
                s.riskFlagged -> WideReject.RISK_FLAG
                else -> null
            }
            if (tradingIssue != null) { notTradable++; reject(tradingIssue); continue }
            val t = tickers[s.symbol]
            val b = books[s.symbol]
            val base = WideCoin(
                s.symbol, s.sectors, t?.lastPrice ?: 0.0, t?.quoteVolume ?: 0.0, spread(b), t?.changePercent ?: 0.0,
            )
            val quick = when {
                base.quoteVolume24h < minQuickVolume -> WideReject.LOW_VOLUME_24H
                !(base.spreadPercent <= maxSpreadPercent) || base.price <= 0 -> WideReject.WIDE_SPREAD
                else -> null
            }
            if (quick != null && s.symbol != BTC) { reject(quick); continue }
            toAnalyze += s to base
        }

        // 1D mumlar: günde bir kez (önbellek), yalnız ön elemeyi geçenler + BTC + açık pozisyonlar.
        val needed = (toAnalyze.map { it.first.symbol } + BTC + held).distinct()
        for (symbol in needed) {
            val cached = daily[symbol]
            if (cached != null && cached.first >= dailyClose) continue
            val candles = runCatching { api.klines(symbol, Timeframe.D1, limit = 320) }.getOrNull()
            requests++
            if (candles != null) daily[symbol] = dailyClose to candles.filter { it.closeTime <= now }
            else daily.remove(symbol)
        }
        val btc = daily[BTC]?.second?.let { DailyStats.of(it, dailyClose) } ?: error("BTCUSDT 1D verisi alınamadı")
        val btcRoc30 = btc.roc(30)

        val stats = HashMap<String, DailyStats>()
        for ((s, base) in toAnalyze) {
            val d = daily[s.symbol]?.second?.let { DailyStats.of(it, dailyClose) }
            val coin = if (d == null) base.copy(rejection = WideReject.DATA_ERROR) else {
                val rs = if (s.symbol == BTC) 0.0 else (d.roc(30) - btcRoc30) * 100
                val reason = when {
                    !d.valid -> WideReject.DATA_ERROR
                    d.days < 210 -> WideReject.SHORT_HISTORY
                    d.medianVolume30 < minMedianVolume -> WideReject.LOW_MEDIAN_VOLUME
                    !d.trendOk -> WideReject.TREND
                    !(rs > 0) -> WideReject.WEAK_RS
                    else -> null
                }
                stats[s.symbol] = d
                base.copy(
                    momentum7Percent = d.roc(7) * 100, momentum30Percent = d.roc(30) * 100, relativeStrength = rs,
                    medianVolume30 = d.medianVolume30, historyDays = d.days, rejection = reason,
                )
            }
            coin.rejection?.let { reject(it) }
            rows += coin
        }
        val liquid = rows.filter { it.rejection == null || it.rejection.ordinal > WideReject.LOW_MEDIAN_VOLUME.ordinal }
        val trend = liquid.filter { it.rejection == null || it.rejection.ordinal > WideReject.TREND.ordinal }
        val ranked = rows.filter { it.rejection == null }.sortedByDescending { it.relativeStrength }

        // 2. aşama: ilk 20 + açık pozisyonlar.
        val stage2 = (ranked.take(stage2Size) + rows.filter { it.symbol in held && ranked.none { r -> r.symbol == it.symbol } })
        val detailed = ArrayList<WideCoin>()
        for (c in stage2) {
            val d = stats[c.symbol]
            val rank = ranked.indexOfFirst { it.symbol == c.symbol }.takeIf { it >= 0 }?.plus(1)
            val depth = runCatching { api.orderBook(c.symbol, 100).depthWithin(1.0).first }.getOrNull()
            requests++
            val stopPct = d?.let { 3 * it.atr / it.close0 * 100 }
            val size = stopPct?.let { minOf(1_000.0 * 0.005 / (it / 100 + 0.0035), 300.0) }
            val (corr, with) = d?.let { mine ->
                detailed.filter { it.rank != null }.mapNotNull { o -> stats[o.symbol]?.let { o.symbol to correlation(mine.returns60, it.returns60) } }
                    .maxByOrNull { it.second }?.let { it.second to it.first }
            } ?: (null to null)
            val warnings = buildList {
                if (c.spreadPercent > 0.20) add("Spread %%%.2f (canlı filtre %%0,20)".format(c.spreadPercent))
                if (depth != null && size != null && depth < 3 * size) add("%%1 derinlik pozisyonun 3 katından az")
                if (corr != null && corr >= 0.8) add("$with ile korelasyon %.2f".format(corr))
                if (rank == null) add("Açık pozisyon; sıralama dışı")
            }
            detailed += c.copy(
                rank = rank, candidate = rank != null && rank <= 3,
                atrPercent = d?.let { it.atr / it.close0 * 100 }, stopDistancePercent = stopPct, volatilityPercent = d?.volatility30,
                daysAboveEma = d?.daysAboveEma, bidDepth1Percent = depth, positionSize = size,
                maxCorrelation = corr, correlatedWith = with, warnings = warnings,
            )
        }

        val strongestRejected = rows.filter { it.rejection != null && it.momentum30Percent.isFinite() }
            .sortedByDescending { it.momentum30Percent }.take(10)
        val sectors = ranked.flatMap { c -> c.sectors.filter { it in SECTOR_LABELS }.ifEmpty { listOf("Diğer") } }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.toMap()
        val result = WideScanResult(
            time = now, dailyClose = dailyClose, totalPairs = info.size, productExcluded = product, notTradable = notTradable,
            analyzed = rows.size, liquidityPassed = liquid.size, trendPassed = trend.size, strengthPassed = ranked.size,
            top = detailed, candidates = detailed.filter { it.candidate }.map { it.symbol },
            rejections = rejections.entries.associate { it.key.title to it.value }, strongestRejected = strongestRejected,
            sectorCounts = sectors, btcMomentum30Percent = btcRoc30 * 100, requests = requests,
        )
        return result to info.map { it.symbol }.toSet()
    }

    companion object {
        const val BTC = "BTCUSDT"
        private val EXCLUDED_PLATES = setOf("Stablecoins", "Tokenized Stocks", "Stock Meme Tokens", "Precious Metals", "Metals", "Pre-IPO")
        private val STABLE = setOf(
            "USDC", "FDUSD", "TUSD", "USDP", "DAI", "USDE", "PYUSD", "EURC", "USD1", "BUSD", "USDD", "FRAX", "RLUSD", "USDS",
            "EURI", "AEUR", "USDQ", "XUSD", "USDG", "USDF", "USDT0", "PAXG", "XAUT",
        )
        private val LEVERAGED = Regex("""\d+[LS]$""")

        /** Rapor ve ekran için sektör grupları (MEXC etiketleri). */
        val SECTOR_LABELS = listOf("Main", "LAYER 1 / LAYER 2", "DeFi", "AI", "AI Agent", "MEME", "DePIN", "Storage", "ZK", "RWA", "GameFi", "Privacy", "Innovation", "Assessment")

        fun isUnsupportedProduct(s: SymbolInfo): Boolean =
            s.baseAsset in STABLE || s.sectors.any { it in EXCLUDED_PLATES } || LEVERAGED.containsMatchIn(s.baseAsset)

        private fun spread(b: BookTicker?): Double =
            if (b == null || b.bid <= 0 || b.ask <= 0) Double.NaN else b.spreadPercent

        fun correlation(a: DoubleArray, b: DoubleArray): Double {
            val n = minOf(a.size, b.size)
            if (n < 20) return 0.0
            val x = a.copyOfRange(a.size - n, a.size)
            val y = b.copyOfRange(b.size - n, b.size)
            val mx = x.average()
            val my = y.average()
            var sxy = 0.0
            var sxx = 0.0
            var syy = 0.0
            for (i in 0 until n) {
                sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my)
            }
            return if (sxx > 0 && syy > 0) sxy / sqrt(sxx * syy) else 0.0
        }
    }
}

/** 1D mumlardan S1 göstergeleri (son kapanmış güne göre). */
internal class DailyStats private constructor(candles: List<Candle>) {
    private val close = DoubleArray(candles.size) { candles[it].close }
    private val ema50 = Indicators.ema(close, 50)
    val days = candles.size
    val valid: Boolean = candles.isNotEmpty() && candles.all { it.close > 0 && it.high >= it.low && it.close.isFinite() } &&
        candles.takeLast(60).zipWithNext().all { (a, b) -> b.openTime - a.openTime == DAY_MS }
    val close0 = close.lastOrNull() ?: Double.NaN
    val atr: Double = if (days < 15) Double.NaN else
        Indicators.atr(DoubleArray(days) { candles[it].high }, DoubleArray(days) { candles[it].low }, close, 14).last()
    val medianVolume30: Double = if (days < 30) 0.0 else
        candles.takeLast(30).map { it.quoteVolume }.sorted().let { (it[14] + it[15]) / 2 }
    val trendOk: Boolean = days > 60 && close0 > ema50[days - 1] && ema50[days - 1] > ema50[days - 11]
    val returns60: DoubleArray = close.toList().takeLast(61).zipWithNext { a, b -> b / a - 1 }.toDoubleArray()
    val volatility30: Double = returns60.takeLast(30).let { r -> val m = r.average(); sqrt(r.sumOf { (it - m) * (it - m) } / (r.size - 1).coerceAtLeast(1)) * 100 }
    val daysAboveEma: Int = (days - 1 downTo 0).takeWhile { close[it] > ema50[it] }.count()

    fun roc(n: Int): Double = if (days <= n) Double.NaN else close[days - 1] / close[days - 1 - n] - 1

    companion object {
        /** Yalnız [dailyClose] anına kadar kapanmış mumlar; son mum o kapanışa ait değilse veri bayattır. */
        fun of(candles: List<Candle>, dailyClose: Long): DailyStats? {
            val c = candles.filter { it.closeTime <= dailyClose }.sortedBy { it.openTime }
            if (c.isEmpty() || c.last().closeTime < dailyClose) return null
            return DailyStats(c)
        }
    }
}
