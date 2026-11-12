package com.spotbot.core.rs1

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

/**
 * Değerlendirme anında bir paritenin canlı ölçümü. [askVwap]: en büyük pozisyon tutarı kadar alışın emir defterindeki
 * ortalama fiyatı (spread ve kayma dahil); [mid]: orta fiyat.
 */
@Serializable
data class LiveCheck(
    val spreadPercent: Double,
    val bidDepth: Double,
    val askDepth: Double,
    val askVwap: Double = Double.NaN,
    val mid: Double = Double.NaN,
    /** MEXC exchangeInfo: taker ücreti, miktar adımı, asgari emir tutarı (USDT). */
    val takerFee: Double = Double.NaN,
    val quantityStep: Double = 0.0,
    val minNotional: Double = 0.0,
)

/** Bir giriş değerlendirmesinin (UTC gün başı [monday]; haftalık modda pazartesi) canlı ölçümleri. */
@Serializable
data class WeeklySnapshot(val monday: Long, val takenAt: Long, val checks: Map<String, LiveCheck>)

/** Takip edilmeyen zaman aralığı: uygulama çalışmıyordu; bu aralıktaki olaylar sonradan hesaplandı. */
@Serializable
data class Interval(val from: Long, val to: Long)

/** Paper trading: değerlendirme anında ölçülen spread, derinlik ve dolum fiyatı. */
class SnapshotGate(snapshots: List<WeeklySnapshot>, private val cfg: Rs1Config) : LiveGate {
    private val byDay = snapshots.associateBy { it.monday }

    override fun evaluated(day: Long) = day in byDay

    override fun entryFailures(symbol: String, day: Long, requiredDepth: Double): List<Rs1Condition>? {
        val c = byDay[day]?.checks?.get(symbol) ?: return listOf(Rs1Condition.SPREAD, Rs1Condition.DEPTH)
        return buildList {
            if (!(c.spreadPercent <= cfg.maxSpreadPercent)) add(Rs1Condition.SPREAD)
            // V6 ölçümlerinde defter pozisyonu karşılamazsa askVwap = 0 kaydedilir; V6 öncesi kayıtlarda alan yoktur (NaN).
            if (!(c.bidDepth >= requiredDepth && c.askDepth >= requiredDepth) || (!c.askVwap.isNaN() && !(c.askVwap > 0))) add(Rs1Condition.DEPTH)
        }
    }

    override fun stillEligible(symbol: String, day: Long, requiredDepth: Double): Boolean? {
        val c = byDay[day]?.checks?.get(symbol) ?: return null
        return c.spreadPercent <= cfg.maxSpreadPercent && c.bidDepth >= requiredDepth && c.askDepth >= requiredDepth
    }

    override fun entryFill(symbol: String, day: Long): EntryFill? {
        val snap = byDay[day] ?: return null
        val c = snap.checks[symbol] ?: return null
        // V6 öncesi kayıt (VWAP yok): eski model, günün açılışından. V6 ölçümleri: gerçek dolum fiyatı.
        if (c.askVwap.isNaN() || !(c.askVwap > 0) || !c.mid.isFinite()) return null
        return EntryFill(c.askVwap * (1 + cfg.costs.slippage * cfg.costs.multiplier), c.mid, snap.takenAt, c.quantityStep, c.minNotional)
    }

}

@Serializable
data class PaperState(
    val startedAt: Long,
    val capital: Double,
    val snapshots: List<WeeklySnapshot> = emptyList(),
    /** İzlenen pariteler (yalnız eklenir): değerlendirmedeki bütün adaylar + açık pozisyonlar + BTC. */
    val tracked: List<String> = listOf(BTC),
    /** İşlem / pozisyon anahtarı → uygulamanın onu ilk gördüğü an (geç kayıt tespiti). */
    val firstSeen: Map<String, Long> = emptyMap(),
    val result: Rs1Result? = null,
    val updatedAt: Long = 0,
    /** Hesaba katılan son tamamlanmış günlük kapanış. */
    val dataEnd: Long = 0,
    /** Son güncellemede yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
    /** Pozisyon tutulan günlerin 1H mumları (gün içi stop çözümü; yalnız eklenir, sonuç sonradan değişmez). */
    val intraday: Map<String, List<com.spotbot.core.model.Candle>> = emptyMap(),
    /** Uygulamanın çalışmadığı (takip edilmeyen) aralıklar. */
    val untracked: List<Interval> = emptyList(),
    /** Son başarılı güncelleme (veri alındı, motor çalıştı). */
    val lastSuccessAt: Long = 0,
) {
    /** [time] takip edilmeyen bir aralığa mı düşüyor? (O sırada gerçekleşen olaylar canlı izlenmedi.) */
    fun untrackedAt(time: Long): Boolean = untracked.any { time in it.from..it.to }

    /** Parite bazında ücret tablosu (değerlendirmelerde kaydedilen MEXC taker ücretleri; en yenisi geçerli). */
    fun feeTable(): Map<String, Double> = snapshots.flatMap { s -> s.checks.mapNotNull { (k, v) -> v.takerFee.takeIf { it.isFinite() }?.let { k to it } } }.toMap()
}

interface PaperStore {
    fun load(): PaperState?
    fun save(state: PaperState)
}

class InMemoryPaperStore(private var state: PaperState? = null) : PaperStore {
    override fun load() = state
    override fun save(state: PaperState) { this.state = state }
}

/**
 * Momentum RS-1 paper trading. Kararlar backtest'le aynı motorla ([Rs1Engine]) verilir: motor, takip başladığından beri
 * gerçek MEXC 1D mumları, kaydedilmiş canlı ölçümler ([SnapshotGate]: spread, derinlik, gerçek dolum fiyatı) ve
 * pozisyon günlerinin 1H mumlarıyla (gün içi stop) yeniden çalıştırılır. Aynı veriyle her zaman aynı sonuç çıkar;
 * yeniden başlatma, bağlantı kopması ya da saat farkı çift işlem üretmez. Gerçek emir gönderilmez.
 *
 * Backtest modelinden farklar (bilerek): giriş, değerlendirme anındaki emir defteri VWAP'ından (backtest: günün açılışı
 * + maliyet senaryosu); gün içi stop 1H mumlarıyla (backtest: 4H, yoksa günlük).
 */
class Rs1Paper(
    private val api: MarketDataApi,
    private val store: PaperStore = InMemoryPaperStore(),
    private val clock: TimeSource = TimeSource.System,
    private val cfg: Rs1Config = Rs1Config(),
    /** Göstergelerin ısınması ve geçmiş şartı için başlangıçtan önce yüklenen veri. */
    private val warmupDays: Int = 500,
    private val refreshMs: Long = 5 * 60_000L,
    /** Bu süreden uzun güncelleme boşluğu "takip edilmeyen aralık" sayılır. */
    private val gapMs: Long = 20 * 60_000L,
) {
    private val mutex = Mutex()
    private val daily = HashMap<String, Pair<Long, List<Candle>>>()
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<PaperState?> = _state.asStateFlow()

    private fun fresh(now: Long) = PaperState(startedAt = now / DAY_MS * DAY_MS, capital = cfg.capital)

    /** Değerlendirme günü mü (haftalık modda yalnız giriş günü)? */
    fun isEvaluationDay(day: Long) = cfg.entryEveryDay || isWeekday(day, cfg.entryWeekday)

    /**
     * Canlı ölçümü kaydeder: yalnız değerlendirme gününde 00:15–[Rs1Config.weeklyWindowHours]:00 UTC penceresinde ve o gün
     * için bir kez. Pencere dışında kaydedilmez: sinyalin süresi dolmuştur, eski fiyattan dolum yapılmaz.
     */
    suspend fun recordSnapshot(snapshot: WeeklySnapshot): Boolean = mutex.withLock {
        val s = _state.value ?: fresh(clock.now())
        val inWindow = isEvaluationDay(snapshot.monday) &&
            snapshot.takenAt in snapshot.monday + 15 * 60_000L until snapshot.monday + cfg.weeklyWindowHours * HOUR_MS
        if (!inWindow || s.snapshots.any { it.monday == snapshot.monday } || snapshot.monday < s.startedAt) return false
        val next = s.copy(snapshots = s.snapshots + snapshot, tracked = (s.tracked + snapshot.checks.keys).distinct().sorted())
        _state.value = next
        store.save(next)
        true
    }

    suspend fun refreshIfDue(): PaperState = mutex.withLock {
        val now = clock.now()
        val s = _state.value
        if (s != null && s.lastError == null && now - s.updatedAt < refreshMs) s else refreshLocked(now)
    }

    suspend fun refresh(): PaperState = mutex.withLock { refreshLocked(clock.now()) }

    /** Sanal hesabı bugünden yeniden başlatır (eski kayıt döndürülür, çağıran arşivler). */
    suspend fun reset(): PaperState? = mutex.withLock {
        val old = _state.value
        val s = fresh(clock.now())
        _state.value = s
        store.save(s)
        old
    }

    private suspend fun refreshLocked(now: Long): PaperState {
        val base0 = _state.value ?: fresh(now)
        // Takip boşluğu: son güncellemeden bu yana uygulama çalışmadıysa aralık kaydedilir.
        val base = if (base0.updatedAt > 0 && now - base0.updatedAt > gapMs) base0.copy(untracked = base0.untracked + Interval(base0.updatedAt, now)) else base0
        val end = now / DAY_MS * DAY_MS
        val next = try {
            val held = base.result?.open?.map { it.symbol }.orEmpty()
            val tracked = (base.tracked + held + BTC).distinct().sorted()
            val series = HashMap<String, DailySeries>()
            val failures = ArrayList<String>()
            for (symbol in tracked) {
                val candles = try { loadDaily(symbol, base.startedAt - warmupDays * DAY_MS, end) } catch (e: Exception) { failures += symbol; continue }
                if (candles.isNotEmpty()) series[symbol] = DailySeries(symbol, candles, cfg)
            }
            if (BTC !in series) error("BTCUSDT verisi alınamadı")
            // 1H mumlar: açık pozisyonlar ve bugünün adayları (gün içi stop, kısmi gün). Saklanır; yalnız eklenir.
            val intraday = base.intraday.toMutableMap()
            val todaySnapshot = base.snapshots.lastOrNull { it.monday == end }
            val needH1 = (held + (todaySnapshot?.checks?.keys ?: emptySet())).distinct()
            for (symbol in needH1) {
                val have = intraday[symbol].orEmpty()
                val position = base.result?.open?.firstOrNull { it.symbol == symbol }
                val from = maxOf(have.lastOrNull()?.let { it.openTime + HOUR_MS } ?: 0L, (position?.entryTime ?: end) / DAY_MS * DAY_MS)
                val fetched = runCatching { api.klinesBetween(symbol, Timeframe.H1, from, now) }.getOrNull() ?: continue
                intraday[symbol] = (have + fetched.filter { it.openTime + HOUR_MS <= now }).distinctBy { it.openTime }.sortedBy { it.openTime }
            }
            // Devam eden gün: kapanmış 1H mumlarından kısmi gün (yalnız stop, dolum ve değerleme).
            val partial = HashMap<String, Candle>()
            for (symbol in needH1) {
                val h1 = intraday[symbol].orEmpty().filter { it.openTime >= end && it.openTime < end + DAY_MS }
                if (h1.isEmpty()) continue
                partial[symbol] = Candle(end, h1.first().open, h1.maxOf { it.high }, h1.minOf { it.low }, h1.last().close, h1.sumOf { it.volume }, end + DAY_MS, h1.sumOf { it.quoteVolume })
            }
            val result = Rs1Engine(series, cfg, base.feeTable(), intraday).run(base.startedAt, end, SnapshotGate(base.snapshots, cfg), partial, closeAtEnd = false)
            val seen = base.firstSeen.toMutableMap()
            (result.trades.map { "T:" + it.key } + result.open.map { "P:" + it.key }).forEach { seen.putIfAbsent(it, now) }
            base.copy(
                tracked = tracked, firstSeen = seen, result = result, updatedAt = now, dataEnd = end, intraday = intraday, lastSuccessAt = now,
                lastError = if (failures.isEmpty()) null else "Veri alınamayan pariteler: ${failures.joinToString()}",
            )
        } catch (e: Exception) {
            base.copy(updatedAt = now, lastError = "Paper trading güncellenemedi: ${e.message ?: e::class.simpleName}")
        }
        _state.value = next
        store.save(next)
        return next
    }

    /** 1D mumlar: günde bir kez indirilir (önbellek); yalnız tamamlanmış günler. */
    private suspend fun loadDaily(symbol: String, from: Long, end: Long): List<Candle> {
        val cached = daily[symbol]
        if (cached != null && cached.first >= end) return cached.second
        val have = cached?.second.orEmpty().filter { it.openTime >= from }
        val start = if (have.isEmpty()) from else have.last().openTime + DAY_MS
        val fresh = if (start < end) api.klinesBetween(symbol, Timeframe.D1, start, end) else emptyList()
        val merged = (have + fresh).filter { it.openTime + DAY_MS <= end }.distinctBy { it.openTime }.sortedBy { it.openTime }
        daily[symbol] = end to merged
        return merged
    }
}
