package com.spotbot.core.rs1

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.klinesBetween
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe

/** Backtest gerçekleşme modu. */
enum class BacktestMode(val title: String, val description: String) {
    RESEARCH(
        "Araştırma eşdeğeri (4H)",
        "Günlük sinyal, ertesi günün açılışından dolum; stop gün içinde 4H mumlarla çözülür (boşlukta mum açılışından). Araştırma motoruyla aynı model.",
    ),
    APPROXIMATE(
        "Yaklaşık (yalnız günlük mum)",
        "Gün içi veri indirilmez; stop günlük mumla çözülür, gün içi boşluklar görülmez. Araştırma sonucuyla eşdeğer DEĞİLDİR.",
    ),
}

/** Hazırlanmış backtest verisi. */
class BacktestData(
    val daily: Map<String, List<Candle>>,
    val intraday: Map<String, List<Candle>>,
    val fees: Map<String, Double>,
    val mode: BacktestMode,
    val failed: List<String>,
)

/**
 * Backtest'in tek yolu: CLI araştırması ve Android aynı veri hazırlığını ve aynı motoru kullanır. Aynı girdilerle aynı
 * sonuç çıkar (testli).
 */
object Rs1Backtest {
    /** [from, to) aralığında 30 gün medyan USDT hacmi en az bir gün eşiği geçen pariteler (+ BTC): 4H indirilecekler. */
    fun liquidCandidates(daily: Map<String, List<Candle>>, cfg: Rs1Config, from: Long, to: Long): List<String> =
        daily.filter { (s, l) ->
            s == BTC || run {
                val v = l.filter { it.openTime < to }.map { it.quoteVolume }
                (cfg.volumeDays - 1 until v.size).any { i ->
                    l[i].openTime >= from - 30 * DAY_MS && v.subList(i - cfg.volumeDays + 1, i + 1).sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 } >= cfg.minMedianVolume
                }
            }
        }.keys.sorted()

    /** Hazır veriyle backtest (CLI ve Android aynı fonksiyon). Günlük mumlar normalleştirilir (UTC; eksikler 4H'den). */
    fun run(data: BacktestData, cfg: Rs1Config, start: Long, end: Long, closeAtEnd: Boolean = false): Rs1Result {
        val intraday = if (data.mode == BacktestMode.RESEARCH) data.intraday else emptyMap()
        val series = data.daily.mapNotNull { (s, l) ->
            val (norm, _) = Rs1History.normalizeDaily(l, intraday[s].orEmpty())
            if (norm.isEmpty()) null else s to DailySeries(s, norm, cfg)
        }.toMap()
        return Rs1Engine(series, cfg, data.fees, intraday).run(start, end, closeAtEnd = closeAtEnd)
    }

    /**
     * Android için veri hazırlığı: evren (bugünkü MEXC USDT pariteleri, ürün ayıklaması), parite ücretleri, 1D ve
     * (araştırma modunda) likit adayların 4H mumları. [cache]/[store] anahtarları sembol + aralık + zaman dilimi.
     */
    suspend fun prepare(
        api: MarketDataApi,
        from: Long,
        to: Long,
        mode: BacktestMode,
        cfg: Rs1Config,
        cache: (String) -> List<Candle>? = { null },
        store: (String, List<Candle>) -> Unit = { _, _ -> },
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
    ): BacktestData {
        val info = api.exchangeInfo()
        val fees = info.associate { it.symbol to it.takerFee }
        val symbols = info.filter { it.quoteAsset == "USDT" && !Rs1Scanner.isUnsupportedProduct(it) }.map { it.symbol }.sorted()
        val warm = from - 400 * DAY_MS
        val (daily, failed) = Rs1History.download(
            api, symbols, warm, to, cache = { cache("1d:$it:$warm:$to") }, store = { s, c -> store("1d:$s:$warm:$to", c) },
            onProgress = { i, n -> onProgress("1D veri", i, n) },
        )
        val intraday = LinkedHashMap<String, List<Candle>>()
        val failures = failed.toMutableList()
        if (mode == BacktestMode.RESEARCH) {
            val candidates = liquidCandidates(daily, cfg, from, to)
            candidates.forEachIndexed { i, s ->
                val key = "4h:$s:$from:$to"
                val bars = cache(key) ?: runCatching { api.klinesBetween(s, Timeframe.H4, from, to).filter { it.openTime + 4 * HOUR_MS <= to } }
                    .onSuccess { store(key, it) }.getOrElse { failures += "$s (4H)"; null }
                if (!bars.isNullOrEmpty()) intraday[s] = bars
                onProgress("4H veri (likit adaylar)", i + 1, candidates.size)
            }
        }
        return BacktestData(daily, intraday, fees, mode, failures)
    }
}
