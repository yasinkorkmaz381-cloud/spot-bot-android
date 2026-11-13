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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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

/** 3 dakikalık taramanın zamanlaması (ana ekranda gösterilir). */
@Serializable
data class ScanTiming(
    val intervalMs: Long = KrPaper.SCAN_INTERVAL,
    val lastStartedAt: Long = 0,
    val lastFinishedAt: Long = 0,
    /** Son hatasız biten tarama. */
    val lastSuccessAt: Long = 0,
    val lastDurationMs: Long = 0,
    /** Planlanan başlangıca göre gecikme. */
    val lastDelayMs: Long = 0,
    val nextAt: Long = 0,
    /** Önceki tarama bitmediği için atlanan tarama sayısı. */
    val skippedOverlap: Int = 0,
    val count: Long = 0,
    val lastError: String? = null,
)

/** Açık pozisyon korumasının durumu. */
@Serializable
data class ProtectionStatus(
    /** Son başarılı koruma denetimi (1 dk mumlar + anlık fiyat). */
    val lastCheckAt: Long = 0,
    /** "Fiyat akışı" ya da "REST". */
    val lastPriceSource: String = "",
    val lastError: String? = null,
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
    val scanTiming: ScanTiming = ScanTiming(),
    val protection: ProtectionStatus = ProtectionStatus(),
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
 * [KrPricing] (hedef, net R:R), [KrSizing] (miktar), [KrExits] (çıkış). İki ayrı döngü vardır:
 * - [scan] (3 dakikada bir, üst üste binmez): CMC evreni (günde bir), mum önbelleği (yalnız yeni kapanmış 15M mumlar),
 *   kurulum izleyiciler. **Giriş yalnız yeni kapanmış 15M mumdaki retest onayından** doğar; 3 dakikalık tarama
 *   kapanmamış mumu teyit saymaz. Her kurulumun kimliği (sembol + kırılım mumu + seviye) bir kez işlenir.
 * - [protect] (daha sık; fiyat akışından gelen her kritik fiyatta da): 1 dakikalık mumlar zaman sırasıyla yeniden
 *   oynatılır (sert stop/hedef, 15M kapanış kuralları), ardından anlık alış fiyatı. Sert stop 15M kapanışını beklemez.
 * Bot çalışmadığı aralıklar kaydedilir; o aralıktaki çıkışlar "canlı izlenmedi" diye işaretlenir. Gerçek emir yoktur.
 */
class KrPaper(
    private val api: MarketDataApi,
    private val cmc: CmcSource,
    private val store: KrStore,
    private val clock: TimeSource = TimeSource.System,
    val cfg: KrConfig = KrConfig(),
    /** 15M mum önbelleği (yeniden başlatmada yalnız yeni mumlar indirilir). Yoksa yalnız bellekte tutulur. */
    private val candleStore: CandleFileStore? = null,
) {
    /** Durum değişiklikleri kısa kritik bölgelerde; ağ istekleri bu kilidin dışında. */
    private val lock = Mutex()
    private val scanLock = Mutex()
    private val protectLock = Mutex()
    private val _state = MutableStateFlow(store.load() ?: fresh())
    val state: StateFlow<KrState> = _state.asStateFlow()
    private val m15 = java.util.concurrent.ConcurrentHashMap<String, List<Candle>>()
    private val h4 = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
    private var symbolInfo: List<SymbolInfo> = emptyList()
    private var trackers: Map<String, SetupTracker> = emptyMap()
    private var trackersClose = 0L
    /** Fiyat akışından gelen son alış/satış (sembol → fiyat). */
    private val ticks = java.util.concurrent.ConcurrentHashMap<String, BookTicker>()

    private fun fresh() = KrState(createdAt = clock.now(), book = KrBook(cfg.risk.startEquity))

    suspend fun reset() = lock.withLock { save(fresh()) }

    suspend fun setEvents(events: List<EventWindow>) = lock.withLock {
        save(_state.value.copy(events = events.sortedBy { it.time }, eventsUpdatedAt = clock.now()))
    }

    /** Test ve tek seferlik çalıştırma kolaylığı: önce koruma, sonra (FULL ise) tarama. */
    suspend fun cycle(mode: BotMode) {
        protect()
        if (mode == BotMode.FULL) scan(mode, clock.now())
    }

    /** Açık pozisyonların sembolleri (fiyat akışı abonelikleri için). */
    fun openSymbols(): Set<String> = _state.value.book.positions.map { it.symbol }.toSet()

    /**
     * Fiyat akışından gelen güncelleme. Açık bir pozisyonun stopunu/hedefini geçtiyse true döner: çağıran hemen
     * [protect] çalıştırmalıdır (15M kapanışı beklenmez).
     */
    fun onTick(t: BookTicker): Boolean {
        ticks[t.symbol] = t
        val p = _state.value.book.positions.firstOrNull { it.symbol == t.symbol } ?: return false
        return t.bid > 0 && (t.bid <= p.stop || t.bid >= p.target || p.pendingExit != null)
    }

    // ---- Tarama (3 dakikada bir) ----

    /**
     * Bir tarama. Önceki tarama sürüyorsa başlamaz (sayılır). [scheduledAt]: planlanan başlangıç (gecikme ölçümü).
     * Dönüş: tarama yapıldı mı.
     */
    suspend fun scan(mode: BotMode, scheduledAt: Long): Boolean {
        if (!scanLock.tryLock()) {
            lock.withLock { val s = _state.value; save(s.copy(scanTiming = s.scanTiming.copy(skippedOverlap = s.scanTiming.skippedOverlap + 1))) }
            return false
        }
        try {
            val started = clock.now()
            lock.withLock {
                val s = _state.value
                save(s.copy(scanTiming = s.scanTiming.copy(lastStartedAt = started, lastDelayMs = maxOf(0, started - scheduledAt), nextAt = nextSlot(started))))
            }
            val error = try {
                scanBody(mode, started)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val finished = clock.now()
            lock.withLock {
                val s = _state.value
                val t = s.scanTiming.copy(
                    lastFinishedAt = finished, lastDurationMs = finished - started, count = s.scanTiming.count + 1,
                    lastSuccessAt = if (error == null) finished else s.scanTiming.lastSuccessAt, lastError = error,
                    nextAt = nextSlot(finished),
                )
                save(s.copy(scanTiming = t, lastError = error).let { if (error != null) it.log(finished, "Tarama hatası: $error") else it })
            }
            return true
        } finally {
            scanLock.unlock()
        }
    }

    private suspend fun scanBody(mode: BotMode, now: Long) {
        // A) Ağ: ücretler (6 saatte bir), evren (günde bir), anlık fiyatlar, yeni kapanmış 15M mumlar.
        var s = _state.value
        s = refreshTerms(s, now)
        if (mode == BotMode.FULL) s = refreshUniverse(s, now)
        val termsAndUniverse = s
        val tickers = api.bookTickers().associateBy { it.symbol }
        val lastClose = Timeframe.M15.lastCloseAtOrBefore(now - SETTLE_MILLIS)
        val members = s.universe?.symbols.orEmpty()
        val watch = (members + KrFilters.BTC + s.book.positions.map { it.symbol }).sorted()
        val failed = ArrayList<String>()
        if (lastClose > trackersClose || watch.any { it !in trackers }) {
            val built = HashMap<String, SetupTracker>()
            // Mumlar eşzamanlı indirilir (istemci hız sınırını kendisi uygular); kapanıştan sonraki karar gecikmesi kısalır.
            val fetched = kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(FETCH_PARALLEL)
                watch.map { sym ->
                    async {
                        gate.withPermit {
                            val bars = runCatching { updateBars(sym, lastClose) }.getOrNull()
                            val trend = if (bars == null) null else runCatching { trend(sym, lastClose, now) }.getOrNull()
                            Triple(sym, bars, trend)
                        }
                    }
                }.awaitAll()
            }
            for ((sym, bars, trend) in fetched) {
                if (bars == null) { failed += sym; continue }
                val t = SetupTracker(sym, cfg, trend)
                val since = ArrayList<TrackerEvent>()
                for (b in bars) {
                    val e = t.onBar(b)
                    if (b.closeTime > eventsAfter(s.lastScanBar, lastClose)) since += e
                }
                newEvents[sym] = since
                built[sym] = t
            }
            trackers = built
            trackersClose = lastClose
        }
        // B) Kararlar ve özet (durum kilidiyle; koruma döngüsünün yazdıklarıyla birleşir).
        lock.withLock {
            var st = _state.value.copy(
                universe = termsAndUniverse.universe, universeError = termsAndUniverse.universeError, universeAttemptAt = termsAndUniverse.universeAttemptAt,
                terms = termsAndUniverse.terms, riskFlagged = termsAndUniverse.riskFlagged, termsAt = termsAndUniverse.termsAt,
                log = (_state.value.log + termsAndUniverse.log.filter { it.time >= now }).distinct().takeLast(400),
            )
            st = decide(st, lastClose, tickers, now, mode, members, watch, failed)
            save(st)
        }
    }

    /** İlk taramada yalnız son mum; sonra son taramadan bu yana (en fazla 1 gün). Kaçırılan kararlar "geç" sayılır. */
    private fun eventsAfter(lastScanBar: Long, lastClose: Long) = if (lastScanBar == 0L) lastClose - M15 else maxOf(lastScanBar, lastClose - KrConfig.DAY)

    private val newEvents = HashMap<String, List<TrackerEvent>>()

    private fun nextSlot(t: Long): Long = t - Math.floorMod(t, SCAN_INTERVAL) + SCAN_INTERVAL + SETTLE_MILLIS

    // ---- Ücretler ve evren ----

    private suspend fun refreshTerms(s: KrState, now: Long): KrState {
        if (now - s.termsAt < TERMS_REFRESH && symbolInfo.isNotEmpty()) return s
        symbolInfo = api.exchangeInfo()
        val terms = symbolInfo.filter { it.quoteAsset == "USDT" }.associate {
            it.symbol to SymbolTerms(takerFee = it.takerFee, quantityStep = it.minQuantity, minNotional = maxOf(it.minNotional, it.minMarketNotional))
        }
        return s.copy(terms = terms, riskFlagged = symbolInfo.filter { it.riskFlagged }.map { it.symbol }.toSet(), termsAt = now)
    }

    /**
     * Anahtar girildiğinde/değiştiğinde çağrılır: bekleme süresine ve bot moduna bakmadan listeyi hemen yeniler.
     * Hata varsa açıklamasını döndürür.
     */
    suspend fun refreshUniverseNow(): String? = lock.withLock {
        val now = clock.now()
        var s = _state.value
        try { s = refreshTerms(s, now) } catch (e: Exception) { save(s.copy(universeError = "MEXC'ye ulaşılamadı: ${e.message}")); return@withLock _state.value.universeError }
        s = refreshUniverse(s, now, force = true)
        save(s)
        s.universeError
    }

    private suspend fun refreshUniverse(s: KrState, now: Long, force: Boolean = false): KrState {
        val u = s.universe
        val due = u == null || now - u.list.fetchedAt >= UNIVERSE_REFRESH
        if (!force && (!due || now - s.universeAttemptAt < UNIVERSE_RETRY)) return s
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

    // ---- Koruma (taramadan ayrı, daha sık) ----

    /**
     * Açık pozisyonlar için: 1 dakikalık mumlar son denetimden itibaren zaman sırasıyla yeniden oynatılır (bağlantı
     * kopmuşsa aradaki olaylar gerçekleştikleri sırayla işlenir), ardından anlık fiyat (akış taze ise akıştan, değilse
     * REST). Aynı anda tek koruma çalışır.
     */
    suspend fun protect() = protectLock.withLock {
        val now = clock.now()
        val snapshot = _state.value
        val positions = snapshot.book.positions
        var error: String? = null
        val results = ArrayList<KrExits.Replay>()
        var source = ""
        try {
            val fresh = positions.associate { p -> p.symbol to ticks[p.symbol]?.takeIf { now - it.time <= STREAM_FRESH } }
            val rest = if (positions.isNotEmpty() && fresh.values.any { it == null }) api.bookTickers().associateBy { it.symbol } else emptyMap()
            source = when {
                positions.isEmpty() -> ""
                fresh.values.all { it != null } -> "Fiyat akışı"
                fresh.values.any { it != null } -> "Fiyat akışı + REST"
                else -> "REST"
            }
            val gapFrom = snapshot.lastMonitorAt
            // Boşluk, her pozisyon için son denetim ile giriş zamanından geç olanından başlar (giriş anında durmuş olabilir).
            val gapStart = positions.map { maxOf(gapFrom, it.entryTime) }.filter { gapFrom > 0 && now - it > MONITOR_GAP }.minOrNull()
            val gap = gapStart?.let { Interval(it, now) }
            val untracked = snapshot.untracked + listOfNotNull(gap)
            for (p in positions) {
                val bars = minuteBars(p.symbol, p.checkedUntil, now)
                val live = fresh[p.symbol] ?: rest[p.symbol]
                results += KrExits.replay(p, bars, live, now, cfg) { a, b -> untracked.any { a < it.to && b > it.from } }
            }
            lock.withLock {
                var s = _state.value
                if (gap != null) {
                    s = s.copy(untracked = (s.untracked + gap).takeLast(500))
                        .log(now, "İzleme boşluğu: ${(gap.to - gap.from) / 1000} sn (servis çalışmadı veya bağlantı yoktu); olaylar sırayla yeniden işlendi")
                }
                for (r in results) {
                    if (!s.book.has(r.position.symbol)) continue
                    val t = r.trade
                    s = if (t != null) {
                        s.copy(book = s.book.close(t)).log(now, "${t.symbol} kapandı: ${t.reason.title}, net ${"%.2f".format(t.netPnl)} USDT (${t.source.title})")
                    } else s.copy(book = s.book.update(r.position))
                }
                val marks = (rest.mapValues { it.value.bid } + fresh.mapNotNull { (k, v) -> v?.let { k to it.bid } }).filterValues { it > 0 }
                val held = s.book.positions.map { it.symbol }.toSet()
                s = s.copy(
                    book = s.book.rollDay(now, marks), lastMonitorAt = now, lastPriceDataAt = if (positions.isEmpty()) s.lastPriceDataAt else now,
                    marks = s.marks.filterKeys { it in held } + marks.filterKeys { it in held },
                    protection = ProtectionStatus(now, source, null),
                )
                save(s)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            lock.withLock { val s = _state.value; save(s.copy(protection = s.protection.copy(lastError = error))) }
        }
    }

    // ---- Kararlar (tarama içinde) ----

    private suspend fun decide(
        s0: KrState, lastClose: Long, tickers: Map<String, BookTicker>, now: Long, mode: BotMode,
        members: Set<String>, watch: List<String>, failed: List<String>,
    ): KrState {
        var s = s0
        val universe = s.universe
        val trackers = this.trackers
        // Olaylar yalnız yeni kapanmış mumlardan; aynı kapanış ikinci kez işlenmez.
        val events: Map<String, List<TrackerEvent>> = if (lastClose > s.lastScanBar) HashMap(newEvents) else emptyMap()
        newEvents.clear()
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
                            mode != BotMode.FULL -> Rejection.ENTRIES_OFF
                            e.time != lastClose || now - lastClose > LATE_MILLIS -> Rejection.LATE
                            !listValid(s, now) -> Rejection.LIST_STALE
                            else -> KrFilters.market(plan, trackers.getValue(sym).barCount, btc4, btc24, cfg, btc?.trendAt(lastClose))
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
            Opportunity(sym, entry?.asset?.name ?: sym, entry?.asset?.rank ?: 0, stage, why, tickers[sym]?.bid ?: t?.lastBar?.close ?: Double.NaN, t?.pendingLevel ?: Double.NaN)
        }.sortedWith(compareBy<Opportunity> { it.stage.ordinal.let { o -> listOf(2, 1, 0, 3)[o] } }.thenBy { it.rank })
        val total = s.funnel.toMutableMap().apply { funnel.forEach { (k, v) -> this[k] = (this[k] ?: 0) + v } }
        return s.copy(
            lastScanBar = maxOf(s.lastScanBar, lastClose),
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
        if (m15[sym] == null) candleStore?.load(sym, M15)?.takeIf { it.isNotEmpty() }?.let { m15[sym] = ArrayList(it.takeLast(CACHE_BARS)) }
        val cached = m15[sym].orEmpty()
        val fresh = if (cached.isEmpty() || cached.last().closeTime < lastClose - 400 * M15) {
            api.klines(sym, Timeframe.M15, limit = CACHE_BARS)
        } else if (cached.last().closeTime < lastClose) {
            api.klines(sym, Timeframe.M15, limit = CACHE_BARS, startTime = cached.last().openTime + M15, endTime = lastClose - 1)
        } else emptyList()
        val merged = (cached + fresh).filter { it.closeTime <= lastClose }.associateBy { it.openTime }.values.sortedBy { it.openTime }.takeLast(CACHE_BARS)
        m15[sym] = merged
        val newClosed = fresh.filter { it.closeTime <= lastClose }
        if (newClosed.isNotEmpty()) runCatching { candleStore?.save(sym, M15, newClosed) }
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
        /** Açık pozisyon varken bu süreden uzun denetimsiz aralık "izleme boşluğu" sayılır. */
        const val MONITOR_GAP = 90_000L
        /** 3 dakikalık tarama aralığı (UTC saatine hizalı; 15M kapanışları tarama anlarına denk gelir). */
        const val SCAN_INTERVAL = 3 * 60_000L
        /** Fiyat akışı bu süreden eskiyse REST'e düşülür. */
        const val STREAM_FRESH = 15_000L
        /** Açık pozisyon varken koruma döngüsünün aralığı. */
        const val PROTECT_INTERVAL = 10_000L
        const val LATE_MILLIS = 5 * 60_000L
        const val CACHE_BARS = 500
        const val FETCH_PARALLEL = 6
        const val TERMS_REFRESH = 6 * H1
        const val UNIVERSE_REFRESH = 24 * H1
        const val UNIVERSE_RETRY = 30 * 60_000L
        /** CMC listesi bu süreden eskiyse yeni giriş yok (açık pozisyon takibi sürer). */
        const val LIST_VALIDITY = 48 * H1
    }
}

internal fun KrState.log(time: Long, text: String) = copy(log = (log + LogEntry(time, text)).takeLast(400))
