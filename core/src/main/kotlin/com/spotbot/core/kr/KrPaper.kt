package com.spotbot.core.kr

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.kr.KrConfig.Companion.H1
import com.spotbot.core.kr.KrConfig.Companion.H4
import com.spotbot.core.kr.KrConfig.Companion.M15
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

/** Botun çalışma biçimi. [OFF] durumunda servis hiç çalışmaz: açık pozisyonlar izlenmez (arayüz bunu uyarır). */
@Serializable
enum class BotMode(val title: String, val detail: String) {
    FULL("Çalışıyor", "Tarama, yeni girişler ve açık pozisyon koruması açık"),
    PROTECT_ONLY("Yeni girişler durdu", "Tarama ve yeni giriş yok; açık pozisyonların stop/hedef takibi sürüyor"),
    OFF("Tamamen kapalı", "Hiçbir şey izlenmiyor; açık pozisyonların stopu denetlenmiyor"),
}

@Serializable
data class Interval(val from: Long, val to: Long)

@Serializable
data class LogEntry(val time: Long, val text: String)

/** Kullanıcının girdiği makro olay (ör. FOMC). Kaynağı kullanıcıdır; uygulama bunu doğrulanmış takvim gibi göstermez. */
@Serializable
data class EventWindow(val time: Long, val title: String)

@Serializable
data class Opportunity(
    val symbol: String,
    val name: String,
    val rank: Int,
    val stage: SetupStage,
    val reason: String,
    val price: Double = Double.NaN,
    val level: Double = Double.NaN,
)

@Serializable
data class ScanSummary(
    /** Değerlendirilen son 15M kapanışı. */
    val barClose: Long,
    val finishedAt: Long,
    val top: Int,
    val onMexc: Int,
    val included: Int,
    /** Verisi alınıp değerlendirilen coin sayısı. */
    val scanned: Int,
    val items: List<Opportunity>,
    val funnel: Map<String, Int> = emptyMap(),
)

@Serializable
data class KrState(
    val version: Int = 1,
    val createdAt: Long,
    val book: KrBook,
    val processedSetups: List<String> = emptyList(),
    val untracked: List<Interval> = emptyList(),
    /** Son başarılı pozisyon/fiyat denetimi (MEXC fiyat verisi). */
    val lastMonitorAt: Long = 0,
    val lastPriceDataAt: Long = 0,
    val lastScanBar: Long = 0,
    val universe: Universe? = null,
    val universeError: String? = null,
    val universeAttemptAt: Long = 0,
    val terms: Map<String, SymbolTerms> = emptyMap(),
    val riskFlagged: Set<String> = emptySet(),
    val termsAt: Long = 0,
    val scan: ScanSummary? = null,
    val log: List<LogEntry> = emptyList(),
    /** Toplam huni sayaçları (paper başlangıcından beri). */
    val funnel: Map<String, Int> = emptyMap(),
    val events: List<EventWindow> = emptyList(),
    val eventsUpdatedAt: Long = 0,
    val lastError: String? = null,
    /** Açık pozisyonların son alış fiyatı (piyasa değeri için). */
    val marks: Map<String, Double> = emptyMap(),
) {
    val equity: Double get() = book.equity(marks)
    /** Bugünkü (TSİ) net K/Z: gerçekleşmiş + gerçekleşmemiş. */
    fun todayPnl(now: Long): Double = if (book.day == dayKey(now)) equity - book.dayStartEquity else book.trades.filter { dayKey(it.exitTime) == dayKey(now) }.sumOf { it.netPnl }
}

interface KrStore {
    fun load(): KrState?
    fun save(state: KrState)
}

class MemoryKrStore(var state: KrState? = null) : KrStore {
    override fun load() = state
    override fun save(state: KrState) { this.state = state }
}

/**
 * Paper trading. Kararları backtestle aynı sınıflar verir: [SetupTracker] (kurulum), [KrFilters] (piyasa süzgeci),
 * [KrPricing] (hedef, net R:R), [KrSizing] (miktar), [KrExits] (çıkış). Farklar yalnız gerçekleşmededir:
 * - Dolum: retest kapanışından birkaç saniye sonra emir defterinden alış VWAP'ı (backtestte sonraki açılış).
 * - Sert stop: her döngüde 1 dakikalık mumlar ve anlık alış fiyatıyla (backtestte 15M mumun düşüğü).
 * - Bot çalışmadığı aralıklar kaydedilir; o aralıktaki çıkışlar "geçmişten hesaplandı" diye işaretlenir.
 * Gerçek emir gönderen hiçbir kod yoktur.
 */
class KrPaper(
    private val api: MarketDataApi,
    private val cmc: CmcSource,
    private val store: KrStore,
    private val clock: TimeSource = TimeSource.System,
    val cfg: KrConfig = KrConfig(),
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(store.load() ?: fresh())
    val state: StateFlow<KrState> = _state.asStateFlow()
    private val m15 = HashMap<String, List<Candle>>()
    private val h4 = HashMap<String, Pair<Long, List<Candle>>>()
    private var symbolInfo: List<SymbolInfo> = emptyList()

    private fun fresh() = KrState(createdAt = clock.now(), book = KrBook(cfg.risk.startEquity))

    suspend fun reset() = lock.withLock { save(fresh()) }

    suspend fun setEvents(events: List<EventWindow>) = lock.withLock {
        save(_state.value.copy(events = events.sortedBy { it.time }, eventsUpdatedAt = clock.now()))
    }

    /** Bir döngü: izleme boşluğu kaydı → ücret/evren yenileme → pozisyon koruması → (yeni 15M kapanışı varsa) tarama. */
    suspend fun cycle(mode: BotMode) = lock.withLock {
        val now = clock.now()
        var s = _state.value
        try {
            s = refreshTerms(s, now)
            if (mode == BotMode.FULL) s = refreshUniverse(s, now)
            val tickers = api.bookTickers().associateBy { it.symbol }
            s = protect(s, tickers, now)
            if (mode == BotMode.FULL) {
                val lastClose = Timeframe.M15.lastCloseAtOrBefore(now - SETTLE_MILLIS)
                if (lastClose > s.lastScanBar) s = scan(s, lastClose, tickers, now)
            }
            s = s.copy(lastError = null)
        } catch (e: Exception) {
            s = s.copy(lastError = e.message ?: e.toString()).log(now, "Hata: ${e.message}")
        }
        save(s)
    }

    // ---- Ücretler ve evren ----

    private suspend fun refreshTerms(s: KrState, now: Long): KrState {
        if (now - s.termsAt < TERMS_REFRESH && symbolInfo.isNotEmpty()) return s
        symbolInfo = api.exchangeInfo()
        val terms = symbolInfo.filter { it.quoteAsset == "USDT" }.associate {
            it.symbol to SymbolTerms(takerFee = it.takerFee, quantityStep = it.minQuantity, minNotional = maxOf(it.minNotional, it.minMarketNotional))
        }
        return s.copy(terms = terms, riskFlagged = symbolInfo.filter { it.riskFlagged }.map { it.symbol }.toSet(), termsAt = now)
    }

    private suspend fun refreshUniverse(s: KrState, now: Long): KrState {
        val u = s.universe
        val due = u == null || now - u.list.fetchedAt >= UNIVERSE_REFRESH
        if (!due || now - s.universeAttemptAt < UNIVERSE_RETRY) return s
        return try {
            val list = cmc.latest()
            val prices = api.tickers24h().associate { it.symbol to it.lastPrice }
            val built = UniverseBuilder.build(list, symbolInfo, prices, now)
            s.copy(universe = built, universeError = null, universeAttemptAt = now)
                .log(now, "CMC listesi yenilendi: ilk ${built.top} → MEXC'de ${built.onMexc} → taranan ${built.included.size}")
        } catch (e: Exception) {
            s.copy(universeError = e.message ?: e.toString(), universeAttemptAt = now).log(now, "CMC listesi alınamadı: ${e.message}")
        }
    }

    /** CMC listesi geçerli mi (CMC zaman damgasından en fazla [LIST_VALIDITY]). */
    fun listValid(s: KrState, now: Long): Boolean = s.universe?.let { now - it.list.cmcTimestamp <= LIST_VALIDITY } == true

    // ---- Koruma (her döngü) ----

    private suspend fun protect(s0: KrState, tickers: Map<String, BookTicker>, now: Long): KrState {
        var s = s0
        val gapFrom = s.lastMonitorAt
        if (gapFrom > 0 && now - gapFrom > MONITOR_GAP) {
            s = s.copy(untracked = (s.untracked + Interval(gapFrom, now)).takeLast(500))
                .log(now, "İzleme boşluğu: ${(now - gapFrom) / 60_000} dk (uygulama/servis çalışmadı veya bağlantı yoktu)")
        }
        for (p in s.book.positions) {
            var pos = p
            val pendingBid = tickers[p.symbol]?.bid
            if (p.pendingExit != null && pendingBid != null && pendingBid > 0) {
                val t = KrExits.close(p, now, pendingBid * (1 - p.exitLeg.slippage), p.pendingExit, ExitSource.LIVE)
                s = s.copy(book = s.book.close(t)).log(now, "${t.symbol} kapandı: ${t.reason.title}, net ${"%.2f".format(t.netPnl)} USDT")
                continue
            }
            // 1) 1 dakikalık mumlar: son denetimden bu yana kaçan fitiller.
            var closed: KrTrade? = null
            for (b in minuteBars(p.symbol, pos.checkedUntil, now)) {
                val partial = b.openTime < pos.entryTime
                val hit = KrExits.intrabar(pos, if (partial) pos.entryPrice else b.open, b.high, b.low)
                if (hit != null) {
                    val backfilled = s.untracked.any { b.openTime < it.to && b.closeTime > it.from }
                    closed = KrExits.close(
                        pos, b.closeTime, hit.price, hit.reason, if (backfilled) ExitSource.BACKFILLED else ExitSource.LIVE,
                        ambiguous = hit.ambiguous || partial, windowFrom = maxOf(b.openTime, pos.entryTime), windowTo = b.closeTime,
                    )
                    break
                }
                pos = pos.copy(highest = maxOf(pos.highest, b.high), checkedUntil = b.closeTime)
            }
            // 2) Anlık alış fiyatı.
            val bid = tickers[p.symbol]?.bid
            if (closed == null && bid != null && bid > 0) {
                closed = when {
                    bid <= pos.stop -> KrExits.close(pos, now, bid * (1 - pos.exitLeg.slippage), ExitReason.STOP, ExitSource.LIVE)
                    bid >= pos.target -> KrExits.close(pos, now, pos.target, ExitReason.TARGET, ExitSource.LIVE)
                    else -> null
                }
                if (closed == null) pos = pos.copy(highest = maxOf(pos.highest, bid), checkedUntil = now)
            }
            s = if (closed != null) {
                s.copy(book = s.book.close(closed)).log(now, "${closed.symbol} kapandı: ${closed.reason.title}, net ${"%.2f".format(closed.netPnl)} USDT (${closed.source.title})")
            } else {
                s.copy(book = s.book.update(pos))
            }
        }
        val marks = tickers.mapValues { it.value.bid }.filterValues { it > 0 }
        val held = s.book.positions.map { it.symbol }.toSet()
        return s.copy(book = s.book.rollDay(now, marks), lastMonitorAt = now, lastPriceDataAt = now, marks = marks.filterKeys { it in held })
    }

    // ---- Tarama (her 15M kapanışı) ----

    private suspend fun scan(s0: KrState, lastClose: Long, tickers: Map<String, BookTicker>, now: Long): KrState {
        var s = s0
        val universe = s.universe
        val members = universe?.symbols.orEmpty()
        val watch = (members + KrFilters.BTC + s.book.positions.map { it.symbol }).sorted()
        val trackers = HashMap<String, SetupTracker>()
        val events = HashMap<String, List<TrackerEvent>>()
        val failed = ArrayList<String>()
        for (sym in watch) {
            val bars = runCatching { updateBars(sym, lastClose) }.getOrElse { failed += sym; continue }
            val trend = runCatching { trend(sym, lastClose, now) }.getOrNull()
            val t = SetupTracker(sym, cfg, trend)
            // İlk taramada yalnız son mum; sonra son taramadan bu yana (en fazla 1 gün) — kaçırılan kararlar "geç" sayılır.
            val after = if (s.lastScanBar == 0L) lastClose - M15 else maxOf(s.lastScanBar, lastClose - KrConfig.DAY)
            val since = ArrayList<TrackerEvent>()
            for (b in bars) {
                val e = t.onBar(b)
                if (b.closeTime > after) since += e
            }
            trackers[sym] = t
            events[sym] = since
        }
        // Kapanış kuralları (tez iptali, ilerleme yok, en uzun süre): girişten sonraki her yeni 15M kapanışı.
        for (p in s.book.positions) {
            val bars = m15[p.symbol].orEmpty().filter { it.closeTime > p.entryTime && it.closeTime > s.lastScanBar && it.closeTime <= lastClose }
            var pos = p
            var reason: ExitReason? = null
            var at: Candle? = null
            for (b in bars) {
                pos = pos.copy(barsHeld = pos.barsHeld + 1, highest = maxOf(pos.highest, b.high))
                reason = KrExits.onClose(pos, b.close, cfg)
                if (reason != null) { at = b; break }
            }
            val bid = tickers[p.symbol]?.bid
            s = if (reason != null && at != null) {
                // Kapanış kuralı geç fark edildiyse (bot o an çalışmıyordu) sonraki mumun açılışı kullanılır ve işaretlenir.
                val next = m15[p.symbol].orEmpty().firstOrNull { it.openTime == at.closeTime }
                val t = if (now - at.closeTime > LATE_MILLIS && next != null) {
                    KrExits.close(pos, next.openTime, next.open * (1 - pos.exitLeg.priceImpact), reason, ExitSource.BACKFILLED)
                } else if (bid != null && bid > 0) {
                    KrExits.close(pos, now, bid * (1 - pos.exitLeg.slippage), reason, ExitSource.LIVE)
                } else null
                if (t != null) s.copy(book = s.book.close(t)).log(now, "${t.symbol} kapandı: ${t.reason.title}, net ${"%.2f".format(t.netPnl)} USDT (${t.source.title})")
                else s.copy(book = s.book.update(pos.copy(pendingExit = reason)))
            } else s.copy(book = s.book.update(pos))
        }

        val funnel = LinkedHashMap<String, Int>()
        fun count(k: String) { funnel[k] = (funnel[k] ?: 0) + 1 }
        val btc = trackers[KrFilters.BTC]?.takeIf { it.lastBar?.closeTime == lastClose }
        val btc4 = btc?.returnPercent(cfg.btcShockBars) ?: Double.NaN
        val btc24 = btc?.returnPercent(96) ?: Double.NaN
        val marks = tickers.mapValues { it.value.bid }.filterValues { it > 0 }
        val processed = s.processedSetups.toHashSet()
        val candidates = ArrayList<Triple<SetupPlan, KrPricing.Quote, Pair<Leg, Leg>>>()
        for ((sym, list) in events) {
            if (sym !in members) continue
            for (e in list) {
                when (e) {
                    is TrackerEvent.BreakoutAccepted -> count("Kırılım: kabul")
                    is TrackerEvent.BreakoutRejected -> count("Kırılım: ${e.reason.title}")
                    is TrackerEvent.SetupEnded -> count("Kurulum bitti: ${e.reason.title}")
                    is TrackerEvent.RetestConfirmed -> {
                        val plan = e.plan
                        if (plan.id in processed) continue
                        processed += plan.id
                        count("Retest onaylandı (evrende)")
                        val reason = when {
                            e.time != lastClose || now - lastClose > LATE_MILLIS -> Rejection.LATE
                            !listValid(s, now) -> Rejection.LIST_STALE
                            else -> KrFilters.market(plan, trackers.getValue(sym).barCount, btc4, btc24, cfg)
                                ?: eventBlock(s, sym, now)
                                ?: if (s.book.has(sym)) Rejection.ALREADY_OPEN else null
                                ?: if (s.book.dailyLossHit(marks, cfg.risk)) Rejection.DAILY_LOSS else null
                        }
                        if (reason != null) { count("Süzgeç: ${reason.title}"); s = s.log(now, "$sym retest: ${reason.title}"); continue }
                        val live = runCatching { liveQuote(plan, s, tickers[sym]) }.getOrElse { Result.failure(it) }
                        live.onSuccess { candidates += it }.onFailure { err ->
                            val r = (err as? RejectedException)?.reason
                            count("Giriş: ${r?.title ?: "Emir defteri alınamadı"}")
                            s = s.log(now, "$sym: ${r?.title ?: err.message}")
                        }
                    }
                }
            }
        }
        // Aynı kapanıştaki girişler: net R:R ↓, maliyet ↑, sembol.
        candidates.sortWith(compareByDescending<Triple<SetupPlan, KrPricing.Quote, Pair<Leg, Leg>>> { it.second.netRewardRisk }
            .thenBy { it.third.first.total }.thenBy { it.first.symbol })
        for ((plan, q, legs) in candidates) {
            val reason = when {
                s.book.has(plan.symbol) -> Rejection.ALREADY_OPEN
                q.fill > plan.maxEntry -> Rejection.CHASE
                q.fill <= plan.stop -> Rejection.BAD_STOP
                !q.target.isFinite() || q.target <= q.fill -> Rejection.NO_TARGET
                !(q.netRewardRisk >= cfg.minNetRewardRisk) -> Rejection.RR_LOW
                q.costShare > cfg.maxCostShareOfTarget -> Rejection.COST_HIGH
                else -> null
            }
            if (reason != null) { count("Giriş: ${reason.title}"); s = s.log(now, "${plan.symbol}: ${reason.title}"); continue }
            val terms = s.terms[plan.symbol] ?: SymbolTerms()
            val equity = s.book.equity(marks)
            when (val sized = KrSizing.size(q.fill, plan.stop, legs.first, legs.second, equity, s.book.cash, s.book.openNotional, s.book.openRisk, s.book.positions.size, cfg.risk, terms)) {
                is KrSizing.Result.Rejected -> { count("Giriş: ${sized.reason.title}"); s = s.log(now, "${plan.symbol}: ${sized.reason.title}") }
                is KrSizing.Result.Ok -> {
                    count("Giriş yapıldı")
                    val pos = KrPosition(
                        id = "${plan.symbol}-$now", symbol = plan.symbol, setupId = plan.id, entryTime = now, refPrice = tickers[plan.symbol]?.mid ?: q.fill,
                        entryPrice = q.fill, quantity = sized.quantity, stop = plan.stop, target = q.target, level = plan.level,
                        invalidation = plan.invalidation, entryLeg = legs.first, exitLeg = legs.second,
                        entryFee = q.fill * sized.quantity * legs.first.fee, plannedNetRewardRisk = q.netRewardRisk,
                        riskAmount = sized.riskAmount, highest = q.fill, checkedUntil = now,
                    )
                    s = s.copy(book = s.book.open(pos)).log(now, "${plan.symbol} alındı (paper): giriş ${SetupTracker.fmt(q.fill)}, stop ${SetupTracker.fmt(plan.stop)}, hedef ${SetupTracker.fmt(q.target)}, net R:R ${"%.2f".format(q.netRewardRisk)}")
                }
            }
        }

        val items = watch.filter { it in members }.map { sym ->
            val entry = universe?.bySymbol(sym)
            val t = trackers[sym]
            val (stage, why) = when {
                t == null -> SetupStage.NOT_SUITABLE to "Veri alınamadı"
                t.lastBar?.closeTime != lastClose -> SetupStage.NOT_SUITABLE to "Son 15M mumu eksik"
                s.book.has(sym) -> SetupStage.NOT_SUITABLE to "Açık pozisyon var"
                else -> t.stage()
            }
            Opportunity(sym, entry?.asset?.name ?: sym, entry?.asset?.rank ?: 0, stage, why, t?.lastBar?.close ?: Double.NaN, t?.pendingLevel ?: Double.NaN)
        }.sortedWith(compareBy<Opportunity> { it.stage.ordinal.let { o -> listOf(2, 1, 0, 3)[o] } }.thenBy { it.rank })
        val total = s.funnel.toMutableMap().apply { funnel.forEach { (k, v) -> this[k] = (this[k] ?: 0) + v } }
        return s.copy(
            lastScanBar = lastClose,
            processedSetups = processed.toList().takeLast(5000),
            funnel = total,
            scan = ScanSummary(lastClose, clock.now(), universe?.top ?: 0, universe?.onMexc ?: 0, members.size, trackers.keys.count { it in members }, items, funnel),
        ).let { if (failed.isNotEmpty()) it.log(now, "${failed.size} coinin verisi alınamadı") else it }
    }

    /** [after] anından sonra kapanan 1 dakikalık mumlar (sayfalı; MEXC sayfa başına ≤500). */
    private suspend fun minuteBars(symbol: String, after: Long, now: Long): List<Candle> {
        val out = ArrayList<Candle>()
        var from = after - Math.floorMod(after, MINUTE)
        while (from < now) {
            val page = api.klines(symbol, Timeframe.M1, limit = 500, startTime = from, endTime = minOf(now, from + 500 * MINUTE) - 1)
            out += page.filter { it.closeTime <= now && it.closeTime > after }
            from += 500 * MINUTE
        }
        return out.distinctBy { it.openTime }.sortedBy { it.openTime }
    }

    private class RejectedException(val reason: Rejection) : Exception(reason.title)

    /** Canlı emir defterinden dolum, maliyet bacakları ve fiyatlama (P3 canlı kısmı, G1). */
    private suspend fun liveQuote(plan: SetupPlan, s: KrState, ticker: BookTicker?): Result<Triple<SetupPlan, KrPricing.Quote, Pair<Leg, Leg>>> {
        val t = ticker ?: api.bookTicker(plan.symbol)
        if (!(t.bid > 0 && t.ask > 0)) return Result.failure(RejectedException(Rejection.BOOK))
        if (t.spreadPercent > cfg.maxSpreadPercent) return Result.failure(RejectedException(Rejection.SPREAD))
        val book = api.orderBook(plan.symbol, 100)
        val terms = s.terms[plan.symbol] ?: SymbolTerms()
        val equity = s.book.equity(emptyMap())
        val notional = equity * cfg.risk.maxPositionPercent / 100
        var left = notional
        var qty = 0.0
        for (lvl in book.asks) {
            val take = minOf(left, lvl.price * lvl.quantity)
            qty += take / lvl.price
            left -= take
            if (left <= 1e-9) break
        }
        if (left > 1e-9 || qty <= 0) return Result.failure(RejectedException(Rejection.BOOK))
        val vwap = notional / qty
        val slip = (vwap / t.ask - 1)
        if (slip * 100 > cfg.maxBookSlippagePercent) return Result.failure(RejectedException(Rejection.BOOK))
        val half = (t.ask - t.bid) / 2 / t.mid
        val fee = terms.takerFee ?: cfg.costs.defaultFee
        val entryLeg = Leg(fee = fee, halfSpread = half, slippage = slip)
        val exitLeg = Leg(fee = fee, halfSpread = half, slippage = cfg.costs.slippage)
        val fill = vwap
        val target = KrPricing.target(plan, fill, cfg)
        val q = KrPricing.Quote(
            fill, target, KrPricing.netRewardRisk(fill, target, plan.stop, entryLeg, exitLeg),
            KrPricing.costShare(t.mid, fill, target, entryLeg, exitLeg),
        )
        return Result.success(Triple(plan, q, entryLeg to exitLeg))
    }

    private fun eventBlock(s: KrState, sym: String, now: Long): Rejection? = when {
        sym in s.riskFlagged -> Rejection.EVENT
        s.events.any { now >= it.time - cfg.eventBeforeMinutes * 60_000L && now <= it.time + cfg.eventAfterMinutes * 60_000L } -> Rejection.EVENT
        else -> null
    }

    /** 15M önbelleği: ilk seferde son 500 mum, sonra yalnız yeni mumlar. Yalnız [lastClose]'a kadar kapanmış mumlar. */
    private suspend fun updateBars(sym: String, lastClose: Long): List<Candle> {
        val cached = m15[sym].orEmpty()
        val fresh = if (cached.isEmpty() || cached.last().closeTime < lastClose - 400 * M15) {
            api.klines(sym, Timeframe.M15, limit = CACHE_BARS)
        } else if (cached.last().closeTime < lastClose) {
            api.klines(sym, Timeframe.M15, limit = CACHE_BARS, startTime = cached.last().openTime + M15, endTime = lastClose - 1)
        } else emptyList()
        val merged = (cached + fresh).filter { it.closeTime <= lastClose }.associateBy { it.openTime }.values.sortedBy { it.openTime }.takeLast(CACHE_BARS)
        m15[sym] = merged
        return merged
    }

    private suspend fun trend(sym: String, lastClose: Long, now: Long): Trend4h {
        val boundary = lastClose - Math.floorMod(lastClose, H4)
        val cached = h4[sym]
        val bars = if (cached != null && cached.first == boundary) cached.second else {
            api.klines(sym, Timeframe.H4, limit = 300).filter { it.openTime + H4 <= now }.also { h4[sym] = boundary to it }
        }
        return Trend4h(bars, cfg)
    }

    private fun save(s: KrState) {
        _state.value = s
        store.save(s)
    }

    companion object {
        const val SETTLE_MILLIS = 10_000L
        const val MINUTE = 60_000L
        const val MONITOR_GAP = 3 * 60_000L
        const val LATE_MILLIS = 5 * 60_000L
        const val CACHE_BARS = 500
        const val TERMS_REFRESH = 6 * H1
        const val UNIVERSE_REFRESH = 24 * H1
        const val UNIVERSE_RETRY = 30 * 60_000L
        /** CMC listesi bu süreden eskiyse yeni giriş yok (açık pozisyon takibi sürer). */
        const val LIST_VALIDITY = 48 * H1
    }
}

internal fun KrState.log(time: Long, text: String) = copy(log = (log + LogEntry(time, text)).takeLast(400))
