package com.spotbot.core.rs1

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.exchange.klinesBetween
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe

/** Backtest için tarihsel 1D veri: evren bugünkü MEXC USDT paritelerinden (ürün ayıklaması dışında filtre yok). */
object Rs1History {
    /**
     * Bugün borsada görünen USDT paritelerinden desteklenmeyen ürünler (stablecoin, tokenleştirilmiş hisse, emtia,
     * kaldıraçlı) çıkarılır. Bugünkü ST etiketi ve işlem durumu geçmişte bilinmediği için **uygulanmaz** (geleceğe
     * bakma). Borsadan kaldırılmış pariteler zaten listede yoktur: hayatta kalma yanlılığı raporlanmalıdır.
     */
    suspend fun universe(api: MarketDataApi): List<String> =
        api.exchangeInfo().filter { it.quoteAsset == "USDT" && !Rs1Scanner.isUnsupportedProduct(it) }.map { it.symbol }.sorted()

    /** [from, to) aralığının 1D mumları; [onProgress] (yüklenen, toplam). Hata veren parite atlanır ve döndürülür. */
    suspend fun download(
        api: MarketDataApi,
        symbols: List<String>,
        from: Long,
        to: Long,
        cache: (String) -> List<Candle>? = { null },
        store: (String, List<Candle>) -> Unit = { _, _ -> },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Map<String, List<Candle>>, List<String>> {
        val out = LinkedHashMap<String, List<Candle>>()
        val failed = ArrayList<String>()
        symbols.forEachIndexed { i, s ->
            val cached = cache(s)
            val candles = if (cached != null) cached else try {
                api.klinesBetween(s, Timeframe.D1, from, to).filter { it.openTime + DAY_MS <= to }.also { store(s, it) }
            } catch (e: Exception) {
                failed += s
                null
            }
            if (!candles.isNullOrEmpty()) out[s] = candles
            onProgress(i + 1, symbols.size)
        }
        return out to failed
    }

    /** Normalleştirme sayıları: alınan, UTC'ye hizasız (atılan) ve 4H'den yeniden kurulan günler. */
    data class Normalization(val aligned: Int, val misaligned: Int, val rebuiltFrom4h: Int)

    /**
     * MEXC'nin eski günlük mumları (2018-08-23 öncesi) UTC 00:00 yerine UTC 16:00'da (UTC+8 gün sınırı) başlar. Bu
     * mumlar UTC günü olarak kullanılamaz; sessizce atılmaz, sayılır. Eksik UTC günleri, tam 6 adet 4H mum varsa
     * onlardan kurulur (aynı borsanın verisi; değer uydurulmaz). Kurulamayan gün boş kalır.
     */
    fun normalizeDaily(daily: List<Candle>, h4: List<Candle>): Pair<List<Candle>, Normalization> {
        val aligned = daily.filter { it.openTime % DAY_MS == 0L }.distinctBy { it.openTime }
        val have = aligned.map { it.openTime }.toHashSet()
        val rebuilt = h4.filter { it.openTime % (4 * HOUR_MS) == 0L }.distinctBy { it.openTime }
            .groupBy { Math.floorDiv(it.openTime, DAY_MS) * DAY_MS }
            .filter { (day, bars) -> day !in have && bars.size == 6 }
            .map { (day, bars) ->
                val b = bars.sortedBy { it.openTime }
                Candle(day, b.first().open, b.maxOf { it.high }, b.minOf { it.low }, b.last().close, b.sumOf { it.volume }, day + DAY_MS, b.sumOf { it.quoteVolume })
            }
        return (aligned + rebuilt).sortedBy { it.openTime } to Normalization(aligned.size, daily.size - aligned.size, rebuilt.size)
    }
}
