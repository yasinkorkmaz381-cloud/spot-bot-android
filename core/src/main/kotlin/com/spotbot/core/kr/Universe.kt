package com.spotbot.core.kr

import com.spotbot.core.model.SymbolInfo
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** CoinMarketCap listesindeki bir varlık (CMC kimliğiyle; sembol tek başına kimlik değildir). */
@Serializable
data class CmcAsset(
    val id: Int,
    val name: String,
    val symbol: String,
    val slug: String,
    val rank: Int,
    val tags: List<String> = emptyList(),
    val platform: String? = null,
    val tokenAddress: String? = null,
    val priceUsd: Double = Double.NaN,
)

/**
 * CMC piyasa değeri sıralaması. [cmcTimestamp] CMC'nin verinin zamanı (status.timestamp); [fetchedAt] bizim aldığımız
 * an. [source] verinin geldiği uç noktadır: başka bir sağlayıcının listesi bu sınıfla CMC listesi gibi saklanmaz.
 */
@Serializable
data class CmcList(
    val cmcTimestamp: Long,
    val fetchedAt: Long,
    val assets: List<CmcAsset>,
    val source: String = SOURCE_LATEST,
) {
    companion object {
        const val SOURCE_LATEST = "CoinMarketCap Pro API /v1/cryptocurrency/listings/latest"
        const val SOURCE_HISTORICAL = "CoinMarketCap Pro API /v1/cryptocurrency/listings/historical"
    }
}

@Serializable
enum class UniverseStatus(val title: String) {
    INCLUDED("Taranıyor"),
    EXCLUDED_CATEGORY("Elendi (stablecoin / tokenleştirilmiş varlık / türev)"),
    NOT_ON_MEXC("MEXC'de USDT spot paritesi yok"),
    SYMBOL_CONFLICT("Aynı sembol, farklı varlık"),
    NOT_TRADABLE("MEXC'de işlem yapılamıyor / risk etiketi"),
    NO_PRICE("Doğrulama fiyatı yok"),
}

@Serializable
data class UniverseEntry(
    val asset: CmcAsset,
    val status: UniverseStatus,
    val mexcSymbol: String? = null,
    /** Eşleşmenin dayanağı ya da elenme ayrıntısı. */
    val note: String = "",
)

@Serializable
data class Universe(val list: CmcList, val entries: List<UniverseEntry>, val builtAt: Long) {
    val top: Int get() = entries.size
    val onMexc: Int get() = entries.count { it.mexcSymbol != null }
    val included: List<UniverseEntry> get() = entries.filter { it.status == UniverseStatus.INCLUDED }
    val symbols: Set<String> get() = included.mapNotNull { it.mexcSymbol }.toSet()
    fun bySymbol(symbol: String): UniverseEntry? = included.firstOrNull { it.mexcSymbol == symbol }
}

/**
 * CMC ilk 300 → MEXC USDT spot eşleşmesi.
 * 1. Kategori elemesi: stablecoin, tokenleştirilmiş hisse/emtia/ETF/tahvil, LP token (CMC etiketleri) ve adı
 *    "Wrapped/Bridged/Staked/Tokenized" olanlar. Kaldıraçlı ürünler (3L/3S…) MEXC tarafında elenir.
 * 2. Aday: MEXC'de taban varlığı CMC sembolüyle aynı olan USDT paritesi.
 * 3. Kimlik doğrulaması: sözleşme adresi iki tarafta da varsa ve aynıysa kabul; değilse MEXC fiyatı CMC fiyatının
 *    ±%10'u içinde olmalı. Aksi halde "aynı sembol, farklı varlık". İlk 300'de aynı MEXC paritesine iki varlık
 *    eşleşirse fiyatı daha yakın olan alınır.
 * 4. MEXC'de işlem açık, alım-satım serbest, ST (risk) etiketi yok.
 * Sayıyı 300'e tamamlamak için 301 ve sonrası eklenmez.
 */
object UniverseBuilder {
    const val TOP = 300
    const val PRICE_TOLERANCE = 0.10

    val EXCLUDED_TAGS = setOf(
        "stablecoin", "usd-stablecoin", "fiat-stablecoin", "eur-stablecoin", "asset-backed-stablecoin", "algorithmic-stablecoin",
        "tokenized-assets", "tokenized-stock", "tokenized-commodities", "tokenized-gold", "tokenized-etfs", "tokenized-treasury-bonds",
        "commodities", "xstocks-ecosystem", "bstocks", "ondo-stocks-ecosystem", "lp-tokens", "wrapped-tokens",
    )
    private val EXCLUDED_NAME = Regex("""\b(wrapped|bridged|staked|restaked|tokenized)\b""", RegexOption.IGNORE_CASE)
    private val LEVERAGED = Regex("""\d+[LS]$""")

    fun excluded(a: CmcAsset): String? {
        val tag = a.tags.firstOrNull { it in EXCLUDED_TAGS }
        return when {
            tag != null -> "CMC etiketi: $tag"
            EXCLUDED_NAME.containsMatchIn(a.name) -> "Türev/sarılmış varlık: ${a.name}"
            else -> null
        }
    }

    /** [prices]: MEXC sembol → doğrulama fiyatı (canlıda son fiyat; backtestte listenin zamanındaki kapanış). */
    fun build(list: CmcList, mexc: List<SymbolInfo>, prices: Map<String, Double>, now: Long, checkTradable: Boolean = true): Universe {
        val pairs = mexc.filter { it.quoteAsset == "USDT" && !LEVERAGED.containsMatchIn(it.baseAsset) }
            .groupBy { it.baseAsset.uppercase() }
        val assets = list.assets.sortedBy { it.rank }.take(TOP)
        val draft = assets.map { a ->
            excluded(a)?.let { return@map UniverseEntry(a, UniverseStatus.EXCLUDED_CATEGORY, note = it) }
            val candidates = pairs[a.symbol.uppercase()].orEmpty()
            if (candidates.isEmpty()) return@map UniverseEntry(a, UniverseStatus.NOT_ON_MEXC)
            val info = candidates.first()
            val address = a.tokenAddress?.trim()?.lowercase().orEmpty()
            val mexcAddress = info.contractAddress.trim().lowercase()
            val price = prices[info.symbol]
            val basis = when {
                address.isNotEmpty() && address == mexcAddress -> "Sözleşme adresi aynı"
                price == null || !(price > 0) || a.priceUsd.isNaN() -> return@map UniverseEntry(a, UniverseStatus.NO_PRICE, info.symbol, "MEXC veya CMC fiyatı yok")
                abs(price / a.priceUsd - 1) <= PRICE_TOLERANCE -> "Fiyat uyumlu (MEXC ${fmt(price)} / CMC ${fmt(a.priceUsd)})"
                else -> return@map UniverseEntry(
                    a, UniverseStatus.SYMBOL_CONFLICT, info.symbol,
                    "MEXC ${info.symbol} fiyatı ${fmt(price)}, CMC ${a.name} fiyatı ${fmt(a.priceUsd)}: farklı varlık",
                )
            }
            if (checkTradable && (!info.isOnline || !info.isSpotTradingAllowed || !info.canTradeBothSides || info.riskFlagged)) {
                return@map UniverseEntry(a, UniverseStatus.NOT_TRADABLE, info.symbol, if (info.riskFlagged) "MEXC risk (ST) etiketi" else "İşlem kapalı / tek yönlü")
            }
            UniverseEntry(a, UniverseStatus.INCLUDED, info.symbol, basis)
        }
        // Aynı MEXC paritesine iki varlık eşleştiyse fiyatı yakın olan kalır.
        val winners = draft.filter { it.status == UniverseStatus.INCLUDED }.groupBy { it.mexcSymbol!! }.mapValues { (sym, list) ->
            list.minBy { e -> prices[sym]?.let { abs(it / e.asset.priceUsd - 1) } ?: Double.MAX_VALUE }.asset.id
        }
        val entries = draft.map { e ->
            if (e.status == UniverseStatus.INCLUDED && winners[e.mexcSymbol] != e.asset.id) {
                e.copy(status = UniverseStatus.SYMBOL_CONFLICT, note = "Aynı MEXC paritesine daha uygun başka bir varlık eşleşti")
            } else e
        }
        return Universe(list, entries, now)
    }

    private fun fmt(p: Double) = SetupTracker.fmt(p)
}
