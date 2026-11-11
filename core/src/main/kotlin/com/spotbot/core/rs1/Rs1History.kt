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
}
