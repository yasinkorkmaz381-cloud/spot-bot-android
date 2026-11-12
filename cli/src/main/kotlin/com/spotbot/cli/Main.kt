package com.spotbot.cli

import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.InMemoryPaperStore
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Costs
import com.spotbot.core.rs1.Rs1History
import com.spotbot.core.rs1.Rs1Paper
import com.spotbot.core.rs1.Rs1Scanner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
internal val time: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
internal fun day(t: Long): String = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()
internal fun date(s: String): Long = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Momentum RS-1 komut satırı. Yalnız herkese açık MEXC verisi; gerçek emir yok. */
fun main(args: Array<String>) = runBlocking {
    val command = args.firstOrNull() ?: "help"
    val options = parseOptions(args.drop(1))
    when (command) {
        "scan" -> scan()
        "paper" -> paper()
        "download" -> download(options)
        "backtest" -> backtest(options)
        "research" -> research(options)
        "v6" -> v6(options)
        "v6final" -> v6Final(options)
        "v7" -> v7(options)
        else -> println(
            """
            CryptoScan Pro — Momentum RS-1 (yalnız paper trading)
              scan                                   Canlı geniş evren taraması (bütün MEXC USDT pariteleri)
              paper                                  Paper trading motorunu bir kez çalıştırır (bugünden başlar)
              download --out klasör [--from 2020-01-01]   Bütün uygun paritelerin 1D geçmişini indirir
              backtest --data klasör [--from T] [--to T] [--cost orta|iyimser|kotumser] [--out sonuç.json]
              research --data klasör [--out sonuç.json]   V5 ön kayıtlı protokolü (docs/RS1-ONKAYIT.md)
              v6 --data 1D --h4 4H --info exchangeInfo.json [--out rapor.txt] [--csv islemler.csv]   V6 araştırma protokolü
              v7 --data 1D --h4 4H --info exchangeInfo.json [--pre 1D-2018 --preh4 4H-2018] [--out rapor.txt] [--csvdir klasör]   V7 protokolü
              v6final --data 1D --h4 4H --info exchangeInfo.json [--from 2018-01-01] [--to 2020-01-01]   Bağımsız test (bir kez)
            """.trimIndent(),
        )
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val v = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            out[a.removePrefix("--")] = v ?: "true"
            i += if (v != null) 2 else 1
        } else i++
    }
    return out
}

internal fun costOf(name: String?): Rs1Costs = when (name?.lowercase()) {
    "iyimser" -> Rs1Costs.OPTIMISTIC
    "kotumser", "kötümser" -> Rs1Costs.PESSIMISTIC
    else -> Rs1Costs.MEDIUM
}

/** 1D veri klasörü: SYMBOL.json, ya [[openTime,o,h,l,c,v,quoteVolume],…] ya da Candle listesi. */
internal fun loadDaily(dir: File): Map<String, List<Candle>> {
    val files = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sortedBy { it.name }
    return files.associate { f ->
        val root = Json.parseToJsonElement(f.readText()) as JsonArray
        val candles = root.mapNotNull { e ->
            when (e) {
                is JsonArray -> {
                    val t = e[0].jsonPrimitive.long
                    Candle(t, e[1].jsonPrimitive.double, e[2].jsonPrimitive.double, e[3].jsonPrimitive.double, e[4].jsonPrimitive.double, e[5].jsonPrimitive.double, t + DAY_MS, e[6].jsonPrimitive.double)
                }
                is JsonObject -> json.decodeFromJsonElement(Candle.serializer(), e)
                else -> null
            }
        }
        f.name.removeSuffix(".json") to candles
    }.filterValues { it.isNotEmpty() }
}

private suspend fun scan() {
    val st = Rs1Scanner(MexcRestClient()).scan()
    st.lastError?.let { println("HATA: $it"); return }
    val r = st.result!!
    println("Tarama ${time.format(Instant.ofEpochMilli(r.time))} UTC · son günlük kapanış ${day(r.dailyClose - 1)} · ${r.requests} istek · ${r.durationMs / 1000} sn")
    println("BTC filtresi: ${if (r.btc.open) "AÇIK" else "KAPALI"} · kapanış %.2f · EMA100 %.2f · 30g %%%+.2f %s".format(r.btc.close, r.btc.ema100, r.btc.return30Percent, r.btc.reason ?: ""))
    println("Toplam USDT paritesi ${r.totalPairs} · 1D incelenen ${r.analyzed} · likidite ${r.liquidityPassed} · trend ${r.trendPassed} · bütün şartlar ${r.eligible} · aday ${r.candidates}")
    println("Elenme aşamaları:"); r.stages.forEach { (k, v) -> println("  %5d  %s".format(v, k)) }
    println("En güçlü ${r.top.size} (30 günlük getiri):")
    r.top.forEach { row ->
        val c = row.check
        println("  %-12s 30g %+7.1f%% 7g %+6.1f%% RS %+6.1f hacim %6.1fM spread %.3f%% derinlik %s/%s aralık %.2fATR %s%s".format(
            row.symbol, c.return30Percent, c.return7Percent, c.relativeStrength, c.medianVolume / 1e6, row.spreadPercent,
            row.bidDepth?.let { "%.0f".format(it) }, row.askDepth?.let { "%.0f".format(it) }, c.rangeAtr,
            if (row.eligible) "UYGUN" else row.failed.joinToString { it.title }, if (row.candidate) " ← ADAY" else "",
        ))
    }
    println("İşlem açılmama nedeni: ${r.noTradeReason ?: "yok (aday var)"} · sonraki haftalık değerlendirme ${time.format(Instant.ofEpochMilli(r.nextWeeklyEvaluation))} UTC")
}

private suspend fun paper() {
    val p = Rs1Paper(MexcRestClient(), InMemoryPaperStore(), cfg = Rs1Config()).refresh()
    println("Paper trading başlangıcı ${day(p.startedAt)} · son kapanış ${day(p.dataEnd - 1)} · hata: ${p.lastError ?: "yok"}")
    val r = p.result ?: return
    println("Özsermaye %.2f USDT · işlem ${r.trades.size} · açık ${r.open.size}".format(r.endEquity))
    r.weekly.forEach { println("  ${day(it.time)}: girilen ${it.entered} · neden ${it.noEntryReason ?: "-"}") }
}

private suspend fun download(options: Map<String, String>) {
    val out = File(options["out"] ?: error("--out klasörü gerekli")).apply { mkdirs() }
    val api = MexcRestClient()
    val from = date(options["from"] ?: "2020-01-01")
    val to = System.currentTimeMillis() / DAY_MS * DAY_MS
    val symbols = Rs1History.universe(api)
    println("İndirilecek parite: ${symbols.size}")
    val (data, failed) = Rs1History.download(
        api, symbols, from, to,
        cache = { s -> File(out, "$s.json").takeIf { it.exists() }?.let { f -> json.decodeFromString(ListSerializer(Candle.serializer()), f.readText()) } },
        store = { s, c -> File(out, "$s.json").writeText(json.encodeToString(ListSerializer(Candle.serializer()), c)) },
        onProgress = { i, n -> if (i % 100 == 0 || i == n) println("  $i / $n") },
    )
    println("Tamam: ${data.size} parite · hata ${failed.size} ${failed.take(10)}")
}
