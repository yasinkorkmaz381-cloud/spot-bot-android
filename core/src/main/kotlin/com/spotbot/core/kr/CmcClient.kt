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

/** Belirli bir UTC gününün (00:00) ilk 300 listesi. Plan izin vermezse [CmcException] fırlatır. */
fun interface CmcHistorySource {
    suspend fun historical(dayStartUtc: Long): CmcList
}

/** Anahtar doğrulaması (`/v1/key/info`, kredi harcamaz). */
data class CmcKeyInfo(val creditsLeftMonth: Long, val creditLimitMonth: Long, val rateLimitMinute: Int)

class CmcException(message: String, val httpCode: Int? = null, val cmcCode: Int? = null) : Exception(message) {
    /** Planın geçmiş liste erişimi yok ya da tarih plan sınırının dışında. */
    val planLimited: Boolean get() = cmcCode in setOf(1005, 1006) || httpCode == 403 ||
        (httpCode == 400 && message?.contains("plan", ignoreCase = true) == true)
}

/**
 * Yapıştırılan anahtarı temizler: boşluk, tırnak ve "X-CMC_PRO_API_KEY:" gibi önekler atılır. Metinde UUID biçiminde
 * bir anahtar varsa yalnız o alınır (CMC anahtarları UUID biçimindedir).
 */
fun sanitizeCmcKey(raw: String): String {
    val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""").find(raw)
    if (uuid != null) return uuid.value.lowercase()
    return raw.substringAfter(':').trim().trim('"', '\'', ' ', '\n', '\r', '\t')
}

/** CMC hata kodunu Türkçe açıklamaya çevirir. */
fun cmcMessage(httpCode: Int?, cmcCode: Int?, raw: String?): String = when {
    cmcCode == 1001 || cmcCode == 1002 || httpCode == 401 -> "CMC anahtarı geçersiz (yanlış ya da eksik kopyalanmış olabilir)"
    cmcCode == 1003 || cmcCode == 1004 -> "CMC anahtarı askıya alınmış ya da süresi dolmuş"
    cmcCode == 1005 || cmcCode == 1006 || httpCode == 403 -> "CMC planınız bu veriye izin vermiyor"
    cmcCode == 1008 || cmcCode == 1009 || cmcCode == 1010 || cmcCode == 1011 || httpCode == 429 -> "CMC istek/kredi sınırı aşıldı; daha sonra tekrar denenecek"
    else -> "CMC hatası${httpCode?.let { " (HTTP $it)" } ?: ""}: ${raw?.take(140) ?: "bilinmiyor"}"
}

/**
 * CoinMarketCap'in resmî Pro API'si. Anahtar [apiKey] ile çalışma anında verilir (uygulamada şifreli depodan; kaynak
 * koda gömülmez). Ücretsiz Basic plan `listings/latest` için yeterlidir; 300 kayıt 2 kredi harcar.
 */
class CmcClient(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://pro-api.coinmarketcap.com",
    private val clock: () -> Long = System::currentTimeMillis,
) : CmcSource, CmcHistorySource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): CmcList = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/cryptocurrency/listings/latest".toHttpUrl().newBuilder()
            .addQueryParameter("start", "1").addQueryParameter("limit", UniverseBuilder.TOP.toString())
            .addQueryParameter("sort", "market_cap").addQueryParameter("convert", "USD")
            .addQueryParameter("aux", "platform,tags,cmc_rank").build()
        parse(call(url), clock(), CmcList.SOURCE_LATEST)
    }

    override suspend fun historical(dayStartUtc: Long): CmcList = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/cryptocurrency/listings/historical".toHttpUrl().newBuilder()
            .addQueryParameter("date", Instant.ofEpochMilli(dayStartUtc).toString())
            .addQueryParameter("start", "1").addQueryParameter("limit", UniverseBuilder.TOP.toString())
            .addQueryParameter("sort", "cmc_rank").addQueryParameter("convert", "USD")
            .addQueryParameter("aux", "platform,tags,cmc_rank").build()
        parse(call(url), clock(), CmcList.SOURCE_HISTORICAL).copy(cmcTimestamp = dayStartUtc)
    }

    suspend fun keyInfo(): CmcKeyInfo = withContext(Dispatchers.IO) {
        val o = json.parseToJsonElement(call("$baseUrl/v1/key/info".toHttpUrl())).jsonObject["data"]!!.jsonObject
        val plan = o["plan"]!!.jsonObject
        val usage = o["usage"]?.jsonObject?.get("current_month")?.jsonObject
        CmcKeyInfo(
            creditsLeftMonth = (usage?.get("credits_left") as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
            creditLimitMonth = (plan["credit_limit_monthly"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1,
            rateLimitMinute = (plan["rate_limit_minute"] as? JsonPrimitive)?.intOrNull ?: 30,
        )
    }

    private fun call(url: okhttp3.HttpUrl): String {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw CmcException("CMC API anahtarı girilmemiş")
        val request = Request.Builder().url(url).get().header(KEY_HEADER, key).header("Accept", "application/json").build()
        return http.newCall(request).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) {
                val code = runCatching { (json.parseToJsonElement(body).jsonObject["status"]?.jsonObject?.get("error_code") as? JsonPrimitive)?.intOrNull }.getOrNull()
                val msg = runCatching { (json.parseToJsonElement(body).jsonObject["status"]?.jsonObject?.get("error_message") as? JsonPrimitive)?.content }.getOrNull()
                throw CmcException(cmcMessage(r.code, code, msg ?: body), r.code, code)
            }
            body
        }
    }

    fun parse(body: String, fetchedAt: Long, source: String): CmcList = parseListing(json, body, fetchedAt, source)

    companion object {
        const val KEY_HEADER = "X-CMC_PRO_API_KEY"

        fun parseListing(json: Json, body: String, fetchedAt: Long, source: String): CmcList {
            val root = json.parseToJsonElement(body).jsonObject
            val status = root["status"]?.jsonObject
            val code = (status?.get("error_code") as? JsonPrimitive)?.intOrNull ?: 0
            if (code != 0) throw CmcException(cmcMessage(null, code, (status?.get("error_message") as? JsonPrimitive)?.content), cmcCode = code)
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
