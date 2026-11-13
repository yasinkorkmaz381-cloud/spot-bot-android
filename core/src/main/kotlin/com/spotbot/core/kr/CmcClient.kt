package com.spotbot.core.kr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

/** CMC listesinin kaynağı (testlerde sahte). */
fun interface CmcSource {
    suspend fun latest(): CmcList
}

class CmcException(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * CoinMarketCap'in resmî Pro API'si. Anahtar [apiKey] ile çalışma anında verilir (uygulamada şifreli depodan; kaynak
 * koda gömülmez). Ücretsiz Basic plan `listings/latest` için yeterlidir; 300 kayıt 2 kredi harcar.
 */
class CmcClient(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://pro-api.coinmarketcap.com",
    private val clock: () -> Long = System::currentTimeMillis,
) : CmcSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): CmcList = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw CmcException("CMC API anahtarı girilmemiş")
        val url = "$baseUrl/v1/cryptocurrency/listings/latest".toHttpUrl().newBuilder()
            .addQueryParameter("start", "1").addQueryParameter("limit", UniverseBuilder.TOP.toString())
            .addQueryParameter("sort", "market_cap").addQueryParameter("convert", "USD")
            .addQueryParameter("aux", "platform,tags,cmc_rank").build()
        val request = Request.Builder().url(url).get().header(KEY_HEADER, key).header("Accept", "application/json").build()
        http.newCall(request).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw CmcException("CMC HTTP ${r.code}: ${body.take(160)}", r.code)
            parse(body, clock(), CmcList.SOURCE_LATEST)
        }
    }

    fun parse(body: String, fetchedAt: Long, source: String): CmcList = parseListing(json, body, fetchedAt, source)

    companion object {
        const val KEY_HEADER = "X-CMC_PRO_API_KEY"

        fun parseListing(json: Json, body: String, fetchedAt: Long, source: String): CmcList {
            val root = json.parseToJsonElement(body).jsonObject
            val status = root["status"]?.jsonObject
            val code = (status?.get("error_code") as? JsonPrimitive)?.intOrNull ?: 0
            if (code != 0) throw CmcException("CMC hata $code: ${(status?.get("error_message") as? JsonPrimitive)?.content}")
            val ts = (status?.get("timestamp") as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: fetchedAt
            val data = root["data"] as? JsonArray ?: throw CmcException("CMC yanıtında veri yok")
            val assets = data.mapIndexed { i, e ->
                val o = e.jsonObject
                val platform = o["platform"]?.takeUnless { it is JsonNull }?.jsonObject
                CmcAsset(
                    id = o.getValue("id").jsonPrimitive.content.toInt(),
                    name = o.getValue("name").jsonPrimitive.content,
                    symbol = o.getValue("symbol").jsonPrimitive.content,
                    slug = (o["slug"] as? JsonPrimitive)?.content.orEmpty(),
                    rank = (o["cmc_rank"] as? JsonPrimitive)?.intOrNull ?: (i + 1),
                    tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                    platform = (platform?.get("slug") as? JsonPrimitive)?.content,
                    tokenAddress = (platform?.get("token_address") as? JsonPrimitive)?.content,
                    priceUsd = (o["quote"]?.jsonObject?.get("USD")?.jsonObject?.get("price") as? JsonPrimitive)?.doubleOrNull ?: Double.NaN,
                )
            }
            return CmcList(ts, fetchedAt, assets, source)
        }
    }
}
