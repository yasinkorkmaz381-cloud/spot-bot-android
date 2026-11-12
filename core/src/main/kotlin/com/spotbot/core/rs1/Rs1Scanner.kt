package com.spotbot.core.rs1

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Timeframe
import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Taramada bir paritenin elenme aşaması (huni). */
enum class ScanStage(val title: String) {
    PRODUCT("Desteklenmeyen ürün (stablecoin, tokenleştirilmiş hisse, emtia, kaldıraçlı)"),
    NOT_TRADING("İşlem durdurulmuş, tek yönlü ya da MEXC risk etiketli"),
    LOW_VOLUME_24H("24 saatlik hacim < 1M USDT (ön eleme)"),
    NO_PRICE("Fiyat / emir defteri yok"),
    DATA("Güvenilir fiyat geçmişi yok (yeni listeleme, eksik gün)"),
    VOLUME("30 günlük medyan hacim < 5M USDT"),
    SPREAD("Spread > %0,15"),
    TREND("Momentum şartı yok (7 günlük getiri ≤ 0; etkinse EMA100 / 30 günlük getiri)"),
    RELATIVE_STRENGTH("BTC'ye göre avantaj < 3 puan"),
    RANGE("Son günün aralığı > 2,5 ATR"),
    DEPTH("Emir defteri derinliği yetersiz"),
}

@Serializable
data class ScanRow(
    val symbol: String,
    val sectors: List<String>,
    val price: Double,
    val spreadPercent: Double,
    val check: CoinCheck,
    val bidDepth: Double? = null,
    val askDepth: Double? = null,
    /** En büyük pozisyon tutarı kadar alışın emir defteri ortalama fiyatı ve orta fiyata göre tahmini kayma (%). */
    val askVwap: Double? = null,
    val mid: Double? = null,
    val estimatedSlippagePercent: Double? = null,
    /** MEXC exchangeInfo: taker ücreti, miktar adımı, asgari emir tutarı. */
    val takerFee: Double = Double.NaN,
    val quantityStep: Double = 0.0,
    val minNotional: Double = 0.0,
    /** Bütün şartlar (veri + canlı) sağlandı mı? */
    val eligible: Boolean = false,
    val candidate: Boolean = false,
    val held: Boolean = false,
    /** Başarısız şartlar (veri + canlı). */
    val failed: List<Rs1Condition> = emptyList(),
)

@Serializable
data class Rs1ScanResult(
    val time: Long,
    /** Hesaba katılan son günlük kapanış (UTC 00:00). */
    val dailyClose: Long,
    val btc: BtcState,
    val totalPairs: Int,
    val analyzed: Int,
    val liquidityPassed: Int,
    val trendPassed: Int,
    val eligible: Int,
    /** Elenme aşamalarına göre sayılar (huni; her parite ilk elendiği aşamada sayılır). */
    val stages: Map<String, Int>,
    /** Likidite ve veri şartlarını geçenler arasında 30 günlük getiriye göre en güçlü 20 (+ açık pozisyonlar). */
    val top: List<ScanRow>,
    val candidates: List<String>,
    /** Yeni işlem açılmama nedeni (kural ya da zamanlama); null ise bir sonraki haftalık değerlendirmede aday var. */
    val noTradeReason: String?,
    val nextWeeklyEvaluation: Long,
    val newListings: List<String> = emptyList(),
    val delisted: List<String> = emptyList(),
    val requiredDepth: Double = 0.0,
    val requests: Int = 0,
    val durationMs: Long = 0,
)

@Serializable
data class ScanState(
    val knownSymbols: List<String> = emptyList(),
    val result: Rs1ScanResult? = null,
    /** Son taramanın yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
    val updatedAt: Long = 0,
)

interface ScanStore {
    fun load(): ScanState?
    fun save(state: ScanState)
}

class InMemoryScanStore(private var state: ScanState? = null) : ScanStore {
    override fun load() = state
    override fun save(state: ScanState) { this.state = state }
}

/**
 * Momentum RS-1 canlı taraması: MEXC'deki bütün USDT paritelerini keşfeder (sabit liste yok).
 *
 * 1. aşama: exchangeInfo + 24s ticker + book ticker (3 toplu istek); ürün, işlem durumu ve 24s hacim ön elemesi;
 *    kalanlar için 1D mumlar günde bir kez (önbellek) ve [Rs1Rules] veri şartları.
 * 2. aşama: en güçlü 20 (+ veri şartlarını geçen hepsi + açık pozisyonlar) için emir defteri derinliği.
 *
 * Kurallar motorla aynı fonksiyonlardan gelir ([Rs1Rules]); tarama yalnız canlı ölçümü (spread, derinlik) ekler.
 */
class Rs1Scanner(
    private val api: MarketDataApi,
    private val store: ScanStore = InMemoryScanStore(),
    private val clock: TimeSource = TimeSource.System,
    private val cfg: Rs1Config = Rs1Config(),
    private val minQuickVolume: Double = 1_000_000.0,
    private val stage2Size: Int = 20,
    private val refreshMs: Long = 30 * 60_000L,
) {
    private val mutex = Mutex()
    private val daily = HashMap<String, Pair<Long, List<Candle>>>()
    private val _state = MutableStateFlow(store.load() ?: ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    suspend fun refreshIfDue(held: Collection<String> = emptyList(), equity: Double = cfg.capital): ScanState = mutex.withLock {
        val s = _state.value
        val now = clock.now()
        val fresh = s.lastError == null && s.result != null && now - s.updatedAt < refreshMs && s.result.dailyClose == now / DAY_MS * DAY_MS
        if (fresh) s else scanLocked(held, equity)
    }

    suspend fun scan(held: Collection<String> = emptyList(), equity: Double = cfg.capital): ScanState = mutex.withLock { scanLocked(held, equity) }

    private suspend fun scanLocked(held: Collection<String>, equity: Double): ScanState {
        val started = clock.now()
        val next = try {
            val (result, symbols) = run(held, equity, started)
            val previous = _state.value.knownSymbols.toSet()
            ScanState(
                symbols.sorted(),
                result.copy(
                    newListings = if (previous.isEmpty()) emptyList() else (symbols - previous).sorted(),
                    delisted = if (previous.isEmpty()) emptyList() else (previous - symbols).sorted(),
                    durationMs = clock.now() - started,
                ),
                null, started,
            )
        } catch (e: Exception) {
            _state.value.copy(lastError = "Tarama başarısız: ${e.message ?: e::class.simpleName}", updatedAt = started)
        }
        _state.value = next
        store.save(next)
        return next
    }

    /** Haftalık değerlendirme için canlı ölçümler (paper trading kaydı). */
    fun snapshot(result: Rs1ScanResult, takenAt: Long): WeeklySnapshot = WeeklySnapshot(
        result.dailyClose, takenAt,
        result.top.associate {
            it.symbol to LiveCheck(it.spreadPercent, it.bidDepth ?: 0.0, it.askDepth ?: 0.0, it.askVwap ?: 0.0, it.mid ?: Double.NaN, it.takerFee, it.quantityStep, it.minNotional)
        },
    )

    private suspend fun run(held: Collection<String>, equity: Double, now: Long): Pair<Rs1ScanResult, Set<String>> {
        var requests = 3
        val info = api.exchangeInfo().filter { it.quoteAsset == "USDT" }
        val tickers = api.tickers24h().associateBy { it.symbol }
        val books = api.bookTickers().associateBy { it.symbol }
        val dailyClose = now / DAY_MS * DAY_MS
        val stages = LinkedHashMap<ScanStage, Int>()
        fun count(s: ScanStage) { stages[s] = (stages[s] ?: 0) + 1 }

        val candidates = ArrayList<SymbolInfo>()
        for (s in info) {
            when {
                isUnsupportedProduct(s) -> count(ScanStage.PRODUCT)
                !s.isOnline || !s.isSpotTradingAllowed || s.tradeSideType != 1 || s.riskFlagged -> count(ScanStage.NOT_TRADING)
                (tickers[s.symbol]?.quoteVolume ?: 0.0) < minQuickVolume && s.symbol != BTC && s.symbol !in held -> count(ScanStage.LOW_VOLUME_24H)
                else -> candidates += s
            }
        }
        val needed = (candidates.map { it.symbol } + BTC + held).distinct()
        for (symbol in needed) {
            val cached = daily[symbol]
            if (cached != null && cached.first >= dailyClose) continue
            val candles = runCatching { api.klines(symbol, Timeframe.D1, limit = 400) }.getOrNull()
            requests++
            if (candles != null) daily[symbol] = dailyClose to candles.filter { it.openTime + DAY_MS <= dailyClose }
            else daily.remove(symbol)
        }
        val series = needed.mapNotNull { sym -> daily[sym]?.second?.let { sym to DailySeries(sym, it, cfg) } }.toMap()
        val btc = Rs1Rules.btc(series[BTC], dailyClose, cfg)
        if (series[BTC] == null) error("BTCUSDT 1D verisi alınamadı")
        val btcR30 = if (btc.dataMissing) Double.NaN else btc.return30Percent / 100

        val rows = ArrayList<ScanRow>()
        for (s in candidates) {
            val book = books[s.symbol]
            val spread = spread(book)
            val d = series[s.symbol]
            if (d == null || !(spread.isFinite())) { count(if (d == null) ScanStage.DATA else ScanStage.NO_PRICE); continue }
            val check = Rs1Rules.coin(d, dailyClose, btcR30, cfg)
            rows += ScanRow(
                s.symbol, s.sectors, tickers[s.symbol]?.lastPrice ?: 0.0, spread, check, held = s.symbol in held,
                takerFee = s.takerFee, quantityStep = Math.pow(10.0, -s.quantityPrecision.toDouble()), minNotional = s.minNotional,
            )
        }
        val requiredDepth = cfg.depthMultiple * cfg.maxPositionPercent / 100 * equity
        // Huni: her parite ilk elendiği aşamada sayılır.
        fun stageOf(r: ScanRow): ScanStage? {
            val f = r.check.failed
            return when {
                Rs1Condition.DATA in f -> ScanStage.DATA
                Rs1Condition.VOLUME in f -> ScanStage.VOLUME
                r.spreadPercent > cfg.maxSpreadPercent -> ScanStage.SPREAD
                f.any { it == Rs1Condition.TREND || it == Rs1Condition.RETURN_7 || it == Rs1Condition.RETURN_30 } -> ScanStage.TREND
                Rs1Condition.RELATIVE_STRENGTH in f -> ScanStage.RELATIVE_STRENGTH
                Rs1Condition.RANGE in f -> ScanStage.RANGE
                else -> null
            }
        }
        val liquid = rows.filter { Rs1Condition.DATA !in it.check.failed && Rs1Condition.VOLUME !in it.check.failed && it.spreadPercent <= cfg.maxSpreadPercent }
        val trend = liquid.filter { r -> r.check.failed.none { it == Rs1Condition.TREND || it == Rs1Condition.RETURN_7 || it == Rs1Condition.RETURN_30 } }
        val dataEligible = liquid.filter { it.check.ok }

        // 2. aşama: en güçlü 20 (30 günlük getiri) + veri şartlarını geçen hepsi + açık pozisyonlar.
        val byMomentum = liquid.sortedWith(compareByDescending<ScanRow> { it.check.return30Percent }.thenBy { it.symbol })
        val stage2 = (byMomentum.take(stage2Size) + dataEligible + rows.filter { it.held }).distinctBy { it.symbol }
        val notional = cfg.maxPositionPercent / 100 * equity
        val detailed = stage2.map { r ->
            val book = runCatching { api.orderBook(r.symbol, 100) }.getOrNull()
            requests++
            val (bid, ask) = book?.depthWithin(cfg.depthBandPercent) ?: (null to null)
            val mid = book?.let { b -> val bb = b.bids.firstOrNull()?.price; val aa = b.asks.firstOrNull()?.price; if (bb != null && aa != null) (bb + aa) / 2 else null }
            val vwap = book?.let { askVwap(it.asks, notional) }
            val failed = buildList {
                addAll(r.check.failed)
                if (r.spreadPercent > cfg.maxSpreadPercent) add(Rs1Condition.SPREAD)
                if (bid == null || ask == null || bid < requiredDepth || ask < requiredDepth) add(Rs1Condition.DEPTH)
            }
            r.copy(
                bidDepth = bid, askDepth = ask, askVwap = vwap, mid = mid,
                estimatedSlippagePercent = if (vwap != null && mid != null) (vwap / mid - 1) * 100 else null,
                failed = failed, eligible = failed.isEmpty(),
            )
        }
        for (r in rows) {
            if (r.symbol in detailed.map { it.symbol }) continue
            stageOf(r)?.let { count(it) }
        }
        for (r in detailed) {
            val st = stageOf(r) ?: if (Rs1Condition.DEPTH in r.failed) ScanStage.DEPTH else null
            st?.let { count(it) }
        }
        val eligible = detailed.filter { it.eligible }.sortedWith(compareByDescending<ScanRow> { it.check.return30Percent }.thenBy { it.symbol })
        val picks = eligible.filter { !it.held }.take(cfg.candidates).map { it.symbol }
        val top = detailed.map { it.copy(candidate = it.symbol in picks) }
            .sortedWith(compareByDescending<ScanRow> { it.check.return30Percent }.thenBy { it.symbol })
        val nextMonday = nextWeekly(now)
        val reason = when {
            btc.dataMissing || !btc.open -> btc.reason
            eligible.isEmpty() -> "Bütün şartları geçen coin yok"
            held.size >= cfg.maxPositions -> "Pozisyon sınırı dolu (${cfg.maxPositions})"
            else -> null
        }
        return Rs1ScanResult(
            time = now, dailyClose = dailyClose, btc = btc, totalPairs = info.size, analyzed = rows.size,
            liquidityPassed = liquid.size, trendPassed = trend.size, eligible = eligible.size,
            stages = stages.entries.associate { it.key.title to it.value }, top = top, candidates = picks,
            noTradeReason = reason, nextWeeklyEvaluation = nextMonday, requiredDepth = requiredDepth, requests = requests,
        ) to info.map { it.symbol }.toSet()
    }

    companion object {
        private val EXCLUDED_PLATES = setOf("Stablecoins", "Tokenized Stocks", "Stock Meme Tokens", "Precious Metals", "Metals", "Pre-IPO")
        private val STABLE = setOf(
            "USDC", "FDUSD", "TUSD", "USDP", "DAI", "USDE", "PYUSD", "EURC", "USD1", "BUSD", "USDD", "FRAX", "RLUSD", "USDS",
            "EURI", "AEUR", "USDQ", "XUSD", "USDG", "USDF", "USDT0", "PAXG", "XAUT",
        )
        private val LEVERAGED = Regex("""\d+[LS]$""")

        fun isUnsupportedProduct(s: SymbolInfo): Boolean =
            s.baseAsset in STABLE || s.sectors.any { it in EXCLUDED_PLATES } || LEVERAGED.containsMatchIn(s.baseAsset)

        /** [notional] USDT'lik alışın satış tarafındaki ortalama fiyatı; defter yetmezse null. */
        fun askVwap(asks: List<com.spotbot.core.model.PriceLevel>, notional: Double): Double? {
            var left = notional
            var qty = 0.0
            for (l in asks) {
                val take = minOf(left, l.price * l.quantity)
                qty += take / l.price
                left -= take
                if (left <= 1e-9) return notional / qty
            }
            return null
        }

        private fun spread(b: BookTicker?): Double = if (b == null || b.bid <= 0 || b.ask <= 0) Double.NaN else b.spreadPercent

        /** Bir sonraki haftalık değerlendirme (pazartesi 00:15 UTC). */
        fun nextWeekly(now: Long, cfg: Rs1Config = Rs1Config()): Long {
            var d = now / DAY_MS * DAY_MS
            while (!(isWeekday(d, cfg.entryWeekday) && d + 15 * 60_000L > now)) d += DAY_MS
            return d + 15 * 60_000L
        }
    }
}
