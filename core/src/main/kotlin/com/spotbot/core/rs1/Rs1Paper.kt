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

/** Haftalık değerlendirmede bir paritenin canlı ölçümü. */
@Serializable
data class LiveCheck(val spreadPercent: Double, val bidDepth: Double, val askDepth: Double)

@Serializable
data class WeeklySnapshot(val monday: Long, val takenAt: Long, val checks: Map<String, LiveCheck>)

/** Paper trading: haftalık değerlendirmede ölçülen spread ve derinlik. */
class SnapshotGate(snapshots: List<WeeklySnapshot>, private val cfg: Rs1Config) : LiveGate {
    private val byMonday = snapshots.associateBy { it.monday }

    override fun evaluated(monday: Long) = monday in byMonday

    override fun entryFailures(symbol: String, monday: Long, requiredDepth: Double): List<Rs1Condition>? {
        val c = byMonday[monday]?.checks?.get(symbol) ?: return listOf(Rs1Condition.SPREAD, Rs1Condition.DEPTH)
        return buildList {
            if (!(c.spreadPercent <= cfg.maxSpreadPercent)) add(Rs1Condition.SPREAD)
            if (!(c.bidDepth >= requiredDepth && c.askDepth >= requiredDepth)) add(Rs1Condition.DEPTH)
        }
    }

    override fun stillEligible(symbol: String, monday: Long, requiredDepth: Double): Boolean? {
        val c = byMonday[monday]?.checks?.get(symbol) ?: return null
        return c.spreadPercent <= cfg.maxSpreadPercent && c.bidDepth >= requiredDepth && c.askDepth >= requiredDepth
    }
}

@Serializable
data class PaperState(
    val startedAt: Long,
    val capital: Double,
    val snapshots: List<WeeklySnapshot> = emptyList(),
    /** İzlenen pariteler (yalnız eklenir): haftalık ölçümdeki bütün adaylar + açık pozisyonlar + BTC. */
    val tracked: List<String> = listOf(BTC),
    /** İşlem / pozisyon anahtarı → uygulamanın onu ilk gördüğü an (geç kayıt tespiti). */
    val firstSeen: Map<String, Long> = emptyMap(),
    val result: Rs1Result? = null,
    val updatedAt: Long = 0,
    /** Hesaba katılan son tamamlanmış günlük kapanış. */
    val dataEnd: Long = 0,
    /** Son güncellemede yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
)

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
 * gerçek MEXC 1D mumları, kaydedilmiş haftalık canlı ölçümler ([SnapshotGate]) ve devam eden günün 1H mumlarıyla
 * (yalnız gün içi stop) yeniden çalıştırılır. Aynı veriyle her zaman aynı sonuç çıkar; uygulama yeniden başlasa da
 * pozisyonlar kaybolmaz. Gerçek emir gönderilmez.
 */
class Rs1Paper(
    private val api: MarketDataApi,
    private val store: PaperStore = InMemoryPaperStore(),
    private val clock: TimeSource = TimeSource.System,
    private val cfg: Rs1Config = Rs1Config(),
    /** Göstergeler ve 150 günlük geçmiş şartı için başlangıçtan önce yüklenen veri. */
    private val warmupDays: Int = 400,
    private val refreshMs: Long = 5 * 60_000L,
) {
    private val mutex = Mutex()
    private val daily = HashMap<String, Pair<Long, List<Candle>>>()
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<PaperState?> = _state.asStateFlow()

    private fun fresh(now: Long) = PaperState(startedAt = now / DAY_MS * DAY_MS, capital = cfg.capital)

    /** Haftalık canlı ölçümü kaydeder (yalnız pazartesi 00:15–04:00 UTC penceresinde ve o hafta için bir kez). */
    suspend fun recordSnapshot(snapshot: WeeklySnapshot): Boolean = mutex.withLock {
        val s = _state.value ?: fresh(clock.now())
        val inWindow = isWeekday(snapshot.monday, cfg.entryWeekday) &&
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

    /** Sanal hesabı bugünden yeniden başlatır (eski kayıt arşivlenmek üzere döndürülür). */
    suspend fun reset(): PaperState? = mutex.withLock {
        val old = _state.value
        val s = fresh(clock.now())
        _state.value = s
        store.save(s)
        old
    }

    private suspend fun refreshLocked(now: Long): PaperState {
        val base = _state.value ?: fresh(now)
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
            // Devam eden gün: açık pozisyonlar ve bu pazartesinin adayları için 1H mumlarından kısmi gün (yalnız stop ve dolum).
            val todaySymbols = (held + (base.snapshots.lastOrNull { it.monday == end }?.checks?.keys ?: emptySet())).distinct()
            val partial = HashMap<String, Candle>()
            for (symbol in todaySymbols) {
                val h1 = runCatching { api.klines(symbol, Timeframe.H1, limit = 30, startTime = end, endTime = now) }.getOrNull()
                    ?.filter { it.openTime >= end && it.openTime < end + DAY_MS }?.sortedBy { it.openTime }.orEmpty()
                if (h1.isEmpty()) continue
                partial[symbol] = Candle(
                    end, h1.first().open, h1.maxOf { it.high }, h1.minOf { it.low }, h1.last().close,
                    h1.sumOf { it.volume }, end + DAY_MS, h1.sumOf { it.quoteVolume },
                )
            }
            val result = Rs1Engine(series, cfg).run(base.startedAt, end, SnapshotGate(base.snapshots, cfg), partial, closeAtEnd = false)
            val seen = base.firstSeen.toMutableMap()
            (result.trades.map { "T:" + it.key } + result.open.map { "P:" + it.key }).forEach { seen.putIfAbsent(it, now) }
            base.copy(
                tracked = tracked, firstSeen = seen, result = result, updatedAt = now, dataEnd = end,
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
