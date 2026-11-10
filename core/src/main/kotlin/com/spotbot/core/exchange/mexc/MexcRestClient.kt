package com.spotbot.core.exchange.mexc

import com.spotbot.core.exchange.ExchangeException
import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.PriceLevel
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MEXC Spot v3 herkese açık REST istemcisi.
 *
 * Hız sınırı: MEXC her uç nokta için IP başına 10 saniyede 500 ağırlık tanır. İstemci istekleri
 * [minRequestIntervalMillis] aralığıyla sıraya koyar, 429/418 yanıtında `Retry-After` kadar bekler.
 */
class MexcRestClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    baseUrl: String = DEFAULT_BASE_URL,
    private val minRequestIntervalMillis: Long = 50,
    private val maxRetries: Int = 3,
) : MarketDataApi {

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val throttle = Mutex()
    private var lastRequestAt = 0L

    override suspend fun serverTime(): Long =
        get("/api/v3/time").jsonObject.long("serverTime")

    override suspend fun exchangeInfo(): List<SymbolInfo> =
        get("/api/v3/exchangeInfo").jsonObject["symbols"]!!.jsonArray.map { parseSymbolInfo(it.jsonObject) }

    override suspend fun klines(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
        startTime: Long?,
        endTime: Long?,
    ): List<Candle> {
        val params = buildMap {
            put("symbol", symbol)
            put("interval", timeframe.mexcInterval)
            put("limit", limit.coerceIn(1, MAX_KLINES).toString())
            startTime?.let { put("startTime", it.toString()) }
            endTime?.let { put("endTime", it.toString()) }
        }
        return get("/api/v3/klines", params).jsonArray.map { parseKline(it.jsonArray) }
    }

    override suspend fun tickers24h(): List<Ticker24h> =
        get("/api/v3/ticker/24hr").jsonArray.map { parseTicker24h(it.jsonObject) }

    override suspend fun bookTickers(): List<BookTicker> {
        val now = System.currentTimeMillis()
        return get("/api/v3/ticker/bookTicker").jsonArray.map { parseBookTicker(it.jsonObject, now) }
    }

    override suspend fun bookTicker(symbol: String): BookTicker =
        parseBookTicker(get("/api/v3/ticker/bookTicker", mapOf("symbol" to symbol)).jsonObject, System.currentTimeMillis())

    override suspend fun orderBook(symbol: String, limit: Int): OrderBook {
        val body = get("/api/v3/depth", mapOf("symbol" to symbol, "limit" to limit.toString())).jsonObject
        fun levels(key: String) = body[key]?.jsonArray.orEmpty().map {
            val level = it.jsonArray
            PriceLevel(level[0].double(), level[1].double())
        }
        return OrderBook(symbol, levels("bids"), levels("asks"))
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonElement {
        val url = baseUrl.newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        var attempt = 0
        while (true) {
            awaitTurn()
            val result = runCatching { execute(Request.Builder().url(url).get().build()) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                if (failure !is IOException || attempt >= maxRetries) {
                    throw ExchangeException("MEXC isteği başarısız: $path (${failure.message})", cause = failure)
                }
                delay(BACKOFF_BASE_MILLIS shl attempt)
                attempt++
                continue
            }
            val (code, body, retryAfter) = result.getOrThrow()
            when {
                code in 200..299 -> return json.parseToJsonElement(body)
                (code == 429 || code == 418 || code >= 500) && attempt < maxRetries -> {
                    delay(retryAfter?.times(1000) ?: (BACKOFF_BASE_MILLIS shl attempt))
                    attempt++
                }
                else -> throw ExchangeException("MEXC HTTP $code: $path ${body.take(200)}", httpCode = code)
            }
        }
    }

    private suspend fun awaitTurn() = throttle.withLock {
        val wait = lastRequestAt + minRequestIntervalMillis - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private suspend fun execute(request: Request): Triple<Int, String, Long?> =
        suspendCancellableCoroutine { cont: CancellableContinuation<Triple<Int, String, Long?>> ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = runCatching { it.body.string() }
                        body.onSuccess { text ->
                            val retryAfter = it.header("Retry-After")?.toLongOrNull()
                            if (cont.isActive) cont.resume(Triple(it.code, text, retryAfter))
                        }.onFailure { e -> if (cont.isActive) cont.resumeWithException(e) }
                    }
                }
            })
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.mexc.com"
        /** Belgeler 1000 dese de MEXC pratikte istek başına en fazla 500 mum döndürüyor (Kasım 2026). */
        const val MAX_KLINES = 500
        private const val BACKOFF_BASE_MILLIS = 1_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal fun parseKline(row: JsonArray): Candle = Candle(
    openTime = row[0].long(),
    open = row[1].double(),
    high = row[2].double(),
    low = row[3].double(),
    close = row[4].double(),
    volume = row[5].double(),
    closeTime = row[6].long(),
    quoteVolume = row[7].double(),
)

internal fun parseSymbolInfo(o: JsonObject): SymbolInfo = SymbolInfo(
    symbol = o.string("symbol"),
    baseAsset = o.string("baseAsset"),
    quoteAsset = o.string("quoteAsset"),
    pricePrecision = o.int("quotePrecision"),
    quantityPrecision = o.int("baseAssetPrecision"),
    minQuantity = o.doubleOrZero("baseSizePrecision"),
    minNotional = o.doubleOrZero("quoteAmountPrecision"),
    makerFee = o.doubleOrZero("makerCommission"),
    takerFee = o.doubleOrZero("takerCommission"),
    isOnline = o.string("status") == "1",
    isSpotTradingAllowed = (o["isSpotTradingAllowed"] as? JsonPrimitive)?.content == "true",
    orderTypes = o["orderTypes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    riskFlagged = (o["st"] as? JsonPrimitive)?.content == "true",
    tradeSideType = (o["tradeSideType"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1,
    maxQuoteAmount = o.doubleOrZero("maxQuoteAmount"),
    minMarketNotional = o.doubleOrZero("quoteAmountPrecisionMarket"),
    maxMarketQuoteAmount = o.doubleOrZero("maxQuoteAmountMarket"),
    buyPriceBandUp = priceFilter(o, "bidMultiplierUp"),
    sellPriceBandDown = priceFilter(o, "askMultiplierDown"),
    sectors = (o["conceptPlates"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
)

private fun priceFilter(o: JsonObject, key: String): Double =
    o["filters"]?.jsonArray?.map { it.jsonObject }
        ?.firstOrNull { (it["filterType"] as? JsonPrimitive)?.content == "PERCENT_PRICE_BY_SIDE" }
        ?.doubleOrZero(key) ?: 0.0

internal fun parseTicker24h(o: JsonObject): Ticker24h = Ticker24h(
    symbol = o.string("symbol"),
    lastPrice = o.doubleOrZero("lastPrice"),
    // MEXC değişimi oran olarak verir (-0.0222 = %-2,22).
    changePercent = o.doubleOrZero("priceChangePercent") * 100,
    highPrice = o.doubleOrZero("highPrice"),
    lowPrice = o.doubleOrZero("lowPrice"),
    volume = o.doubleOrZero("volume"),
    quoteVolume = o.doubleOrZero("quoteVolume"),
    bidPrice = o.doubleOrZero("bidPrice"),
    askPrice = o.doubleOrZero("askPrice"),
)

internal fun parseBookTicker(o: JsonObject, time: Long): BookTicker = BookTicker(
    symbol = o.string("symbol"),
    bid = o.doubleOrZero("bidPrice"),
    bidQty = o.doubleOrZero("bidQty"),
    ask = o.doubleOrZero("askPrice"),
    askQty = o.doubleOrZero("askQty"),
    time = time,
)

private fun JsonElement.double(): Double = jsonPrimitive.content.toDouble()
private fun JsonElement.long(): Long = jsonPrimitive.longOrNull ?: jsonPrimitive.content.toDouble().toLong()
private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
private fun JsonObject.long(key: String): Long = this[key]!!.long()
private fun JsonObject.doubleOrZero(key: String): Double {
    val element = this[key]
    if (element == null || element is JsonNull) return 0.0
    return element.jsonPrimitive.content.toDoubleOrNull() ?: 0.0
}
