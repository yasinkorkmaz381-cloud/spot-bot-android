package com.spotbot.core.kr

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.klinesBetween
import com.spotbot.core.kr.KrConfig.Companion.DAY
import com.spotbot.core.kr.KrConfig.Companion.H4
import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Timeframe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/** Evrenin hangi listeden geldiği. Bugünkü liste geçmişe uygulanmışsa sonuç bu biçimde etiketlenir. */
enum class UniverseSource(val title: String) {
    HISTORICAL("Her gün o günün CMC ilk 300 listesi"),
    TODAY("Bugünkü ilk 300 listesiyle geçmiş test"),
}

data class HistoryProgress(val phase: String, val done: Int, val total: Int) {
    val fraction: Double get() = if (total <= 0) 0.0 else done.toDouble() / total
}

class HistoryOutcome(
    val result: KrResult,
    val metrics: KrMetrics,
    val source: UniverseSource,
    /** İstenen ve gerçekten test edilen aralık (veri sınırları nedeniyle kısalabilir). */
    val requestedStart: Long,
    val start: Long,
    val end: Long,
    val warnings: List<String>,
    /** Gün başına ortalama: ilk N → MEXC'de → taranan. */
    val avgTop: Double,
    val avgOnMexc: Double,
    val avgScanned: Double,
    val coinsWithData: Int,
)

class HistoryException(message: String) : Exception(message)

/**
 * Uygulama içi geçmiş test. Kararları backtest motoru verir ([KrBacktest]); bu sınıf yalnız veriyi hazırlar:
 * 1. MEXC ücret/spread/miktar kuralları (bugünkü anlık görüntü).
 * 2. Evren: her gün için CMC geçmiş listesi (önbellekli). Plan izin vermezse bugünkü liste kullanılır ve sonuç
 *    [UniverseSource.TODAY] diye etiketlenir; tarihsel liste kullanılmış gibi sunulmaz.
 * 3. Kimlik doğrulama fiyatı: o günün 00:00 UTC kapanışı (1D mum).
 * 4. 15M (7 gün ısınma) ve 4H (70 gün ısınma) mumlar, disk önbelleği ile; yalnız eksik kısım indirilir.
 */
class KrHistory(
    private val api: MarketDataApi,
    private val cmcLatest: CmcSource,
    private val cmcHistory: CmcHistorySource?,
    private val store: CandleFileStore,
    private val listCache: CmcListFileCache,
    private val cfg: KrConfig = KrConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    /** CMC geçmiş istekleri arasındaki bekleme (dakikalık sınır için). */
    private val cmcPaceMillis: Long = 1_300,
    private val parallel: Int = 4,
) {
    suspend fun run(fromDay: Long, toExclusive: Long, startEquity: Double, costs: KrCosts = cfg.costs, onProgress: (HistoryProgress) -> Unit = {}): HistoryOutcome {
        val warnings = ArrayList<String>()
        val lastClose = Timeframe.M15.lastCloseAtOrBefore(now())
        val end = minOf(toExclusive, lastClose)
        if (end <= fromDay) throw HistoryException("Bitiş tarihi başlangıçtan sonra olmalı")

        onProgress(HistoryProgress("MEXC kuralları ve ücretleri", 0, 1))
        val infos = api.exchangeInfo()
        val half = runCatching { api.bookTickers() }.getOrDefault(emptyList()).associate { it.symbol to if (it.bid > 0 && it.ask > 0) (it.ask - it.bid) / 2 / it.mid else Double.NaN }
        val terms = infos.filter { it.quoteAsset == "USDT" }.associate {
            it.symbol to SymbolTerms(it.takerFee, half[it.symbol]?.takeIf { h -> !h.isNaN() }, it.minQuantity, maxOf(it.minNotional, it.minMarketNotional))
        }

        // MEXC 15M geçmişi sınırlı (~360 gün): test başlangıcı + ısınma veri içinde kalmalı.
        val earliest = earliest15m(fromDay - WARMUP_15M)
        var start = fromDay
        if (earliest > fromDay - WARMUP_15M) {
            start = (earliest + WARMUP_15M).let { it - Math.floorMod(it, DAY) + DAY }
            warnings += "MEXC 15 dakikalık geçmişi yalnız ${dayKey(earliest)} tarihine kadar gidiyor; test ${dayKey(start)} tarihinden başladı."
        }
        if (start >= end) throw HistoryException("Bu aralık için MEXC 15 dakikalık veri yok")

        // Evren.
        val days = (start - Math.floorMod(start, DAY) until end step DAY).toList()
        var source = UniverseSource.HISTORICAL
        val lists = HashMap<Long, CmcList>()
        if (cmcHistory == null) {
            source = UniverseSource.TODAY
            warnings += "Geçmiş CMC listesi kaynağı yok."
        } else {
            for ((i, d) in days.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(HistoryProgress("CMC geçmiş listeleri", i, days.size))
                val cached = listCache.get(d)
                if (cached != null) { lists[d] = cached; continue }
                val got = try {
                    retry { cmcHistory.historical(d) }
                } catch (e: CmcException) {
                    // Son 1–2 günün listesi CMC'de henüz yayımlanmamış olabilir ("out of range"). Paper'daki kuralla aynı:
                    // en fazla 48 saat önceki son liste geçerlidir. Daha eskiyse ya da plan izin vermiyorsa bugünkü liste.
                    val previous = lists.entries.filter { it.key < d }.maxByOrNull { it.key }
                    if (!e.planLimited && previous != null && d - previous.key <= 2 * DAY && now() - d <= 3 * DAY) {
                        lists[d] = previous.value
                        warnings += "${dayKey(d)} için CMC geçmiş listesi henüz yayımlanmamış; ${dayKey(previous.key)} listesi kullanıldı (48 saat kuralı)."
                        continue
                    }
                    source = UniverseSource.TODAY
                    warnings += "Geçmiş CMC sıralaması alınamadı (${dayKey(d)}): ${e.message}"
                    break
                }
                listCache.put(d, got)
                lists[d] = got
                delay(cmcPaceMillis)
            }
        }
        val members = HashMap<Long, Set<String>>()
        val stats = ArrayList<Triple<Int, Int, Int>>()
        if (source == UniverseSource.HISTORICAL) {
            val bases = lists.values.flatMap { l -> l.assets.map { it.symbol.uppercase() } }.toSet()
            val candidates = usdtPairs(infos).filter { it.baseAsset.uppercase() in bases }
            val dailyClose = HashMap<String, Map<Long, Double>>()
            var n = 0
            parallelEach(candidates.map { it.symbol }) { sym ->
                val d1 = api.klinesBetween(sym, Timeframe.D1, days.first() - DAY, end)
                synchronized(dailyClose) { dailyClose[sym] = d1.associate { it.openTime + DAY to it.close }; onProgress(HistoryProgress("Günlük fiyatlar (kimlik doğrulama)", ++n, candidates.size)) }
            }
            for (d in days) {
                val list = lists[d] ?: continue
                val prices = dailyClose.mapNotNull { (s, m) -> m[d]?.let { s to it } }.toMap()
                val u = UniverseBuilder.build(list, infos, prices, d, checkTradable = false)
                members[d] = u.symbols
                stats += Triple(u.top, u.onMexc, u.symbols.size)
            }
        } else {
            warnings.add(0, "Bugünkü ilk 300 listesiyle geçmiş test: geçmişte ilk 300'de olmayan coinler de teste girdi, düşenler girmedi. Sonuç tarihsel ilk 300 sonucu değildir ve yanlı olabilir.")
            onProgress(HistoryProgress("CMC bugünkü liste", 0, 1))
            val list = try { cmcLatest.latest() } catch (e: CmcException) { throw HistoryException(e.message ?: "CMC listesi alınamadı") }
            val prices = api.tickers24h().associate { it.symbol to it.lastPrice }
            val u = UniverseBuilder.build(list, infos, prices, now())
            days.forEach { members[it] = u.symbols }
            stats += Triple(u.top, u.onMexc, u.symbols.size)
        }
        val symbols = (members.values.flatten() + KrFilters.BTC).toSortedSet().toList()

        // Mumlar.
        var done = 0
        val m15 = HashMap<String, List<Candle>>()
        val h4 = HashMap<String, Trend4h>()
        parallelEach(symbols) { sym ->
            val a = cached(sym, M15, Timeframe.M15, start - WARMUP_15M, end)
            val b = cached(sym, H4, Timeframe.H4, start - WARMUP_4H, end)
            synchronized(m15) {
                if (a.isNotEmpty()) { m15[sym] = a; h4[sym] = Trend4h(b, cfg) }
                onProgress(HistoryProgress("Mum verisi (15M + 4H)", ++done, symbols.size))
            }
        }
        if (KrFilters.BTC !in m15) throw HistoryException("BTCUSDT verisi alınamadı")

        val c = cfg.copy(risk = cfg.risk.copy(startEquity = startEquity))
        val input = KrBacktestInput(m15, h4, { d -> members[d].orEmpty() }, terms, "MEXC 15M (uygulama)")
        val ctx = coroutineContext
        val result = KrBacktest(c, costs).run(
            input, start, end,
            onProgress = { onProgress(HistoryProgress("Test çalışıyor", (it * 1000).toInt(), 1000)) },
            isCancelled = { !ctx[kotlinx.coroutines.Job]!!.isActive },
        )
        val metrics = KrReport.metrics(result.trades, startEquity, result.equityCurve, start, end)
        return HistoryOutcome(
            result, metrics, source, fromDay, start, end, warnings,
            stats.map { it.first }.average(), stats.map { it.second }.average(), stats.map { it.third }.average(), m15.size,
        )
    }

    /** Önbellekte denetlenmiş aralık yeterliyse diskten; değilse yalnız eksik uçlar indirilir. */
    private suspend fun cached(sym: String, period: Long, tf: Timeframe, from: Long, to: Long): List<Candle> {
        val cov = store.coverage(sym, period)
        val fresh = ArrayList<Candle>()
        if (cov == null || cov.first > from) fresh += api.klinesBetween(sym, tf, from, cov?.first ?: to)
        if (cov != null && cov.second < to) fresh += api.klinesBetween(sym, tf, cov.second, to)
        val closed = fresh.filter { it.closeTime <= now() }
        if (closed.isNotEmpty()) store.save(sym, period, closed)
        if (cov == null || cov.first > from || cov.second < to) store.setCoverage(sym, period, minOf(from, cov?.first ?: from), maxOf(to, cov?.second ?: to))
        return window(store.load(sym, period), from, to)
    }

    private fun window(list: List<Candle>, from: Long, to: Long): List<Candle> {
        fun lower(t: Long): Int { var lo = 0; var hi = list.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (list[m].openTime < t) lo = m + 1 else hi = m }; return lo }
        return list.subList(lower(from), lower(to))
    }

    /** BTCUSDT 15M verisinin [want] anından itibaren mevcut olduğu en eski gün (ikili arama). */
    private suspend fun earliest15m(want: Long): Long {
        suspend fun has(t: Long) = api.klines(KrFilters.BTC, Timeframe.M15, 10, t, t + DAY).isNotEmpty()
        if (has(want)) return want
        var lo = want
        var hi = now() - 2 * DAY
        if (!has(hi)) throw HistoryException("MEXC 15 dakikalık veri alınamadı")
        while (hi - lo > DAY) { val mid = lo + (hi - lo) / 2; if (has(mid)) hi = mid else lo = mid }
        return hi
    }

    private fun usdtPairs(infos: List<SymbolInfo>) = infos.filter { it.quoteAsset == "USDT" && !Regex("""\d+[LS]$""").containsMatchIn(it.baseAsset) }

    private suspend fun <T> retry(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try { return block() } catch (e: CmcException) {
                if (e.planLimited || e.httpCode == 400 || e.cmcCode == 1001 || e.cmcCode == 1002 || e.httpCode == 401) throw e
                last = e; delay(5_000L * (attempt + 1))
            } catch (e: java.io.IOException) { last = e; delay(5_000L * (attempt + 1)) }
        }
        throw (last as? CmcException) ?: CmcException("CMC'ye ulaşılamadı: ${last?.message}")
    }

    private suspend fun parallelEach(items: List<String>, block: suspend (String) -> Unit) = coroutineScope {
        val gate = Semaphore(parallel)
        items.map { s -> async { gate.withPermit { coroutineContext.ensureActive(); block(s) } } }.awaitAll()
    }

    companion object {
        const val WARMUP_15M = 7 * DAY
        const val WARMUP_4H = 70 * DAY
    }
}
