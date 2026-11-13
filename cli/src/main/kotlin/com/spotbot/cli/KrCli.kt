package com.spotbot.cli

import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.exchange.mexc.parseSymbolInfo
import com.spotbot.core.kr.Bars
import com.spotbot.core.kr.CmcAsset
import com.spotbot.core.kr.CmcClient
import com.spotbot.core.kr.CmcList
import com.spotbot.core.kr.KrBacktest
import com.spotbot.core.kr.KrBacktestInput
import com.spotbot.core.kr.KrConfig
import com.spotbot.core.kr.KrConfig.Companion.H4
import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.kr.KrCosts
import com.spotbot.core.kr.KrMetrics
import com.spotbot.core.kr.KrReport
import com.spotbot.core.kr.KrResult
import com.spotbot.core.kr.KrTrade
import com.spotbot.core.kr.SymbolTerms
import com.spotbot.core.kr.Trend4h
import com.spotbot.core.kr.Universe
import com.spotbot.core.kr.UniverseBuilder
import com.spotbot.core.kr.UniverseStatus
import com.spotbot.core.kr.dayKey
import com.spotbot.core.model.Candle
import com.spotbot.core.model.SymbolInfo
import kotlinx.coroutines.launch
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
import java.io.File
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

private val json = Json { ignoreUnknownKeys = true }

/** Araştırma verisi (indirme betikleri: docs/research/kr/indirme.md). */
class KrData(val dir: File) {
    val infos: List<SymbolInfo> = json.parseToJsonElement(File(dir, "mexc/exchangeInfo.json").readText()).jsonObject
        .getValue("symbols").jsonArray.map { parseSymbolInfo(it.jsonObject) }
    val snapshotTime: String = File(dir, "mexc/snapshot_time.txt").takeIf { it.exists() }?.readText()?.trim() ?: "?"
    val halfSpread: Map<String, Double> = json.parseToJsonElement(File(dir, "mexc/bookTicker.json").readText()).jsonArray.mapNotNull { e ->
        val o = e.jsonObject
        val bid = (o["bidPrice"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
        val ask = (o["askPrice"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
        if (bid <= 0 || ask <= 0) null else o.getValue("symbol").jsonPrimitive.content to (ask - bid) / 2 / ((ask + bid) / 2)
    }.toMap()
    val terms: Map<String, SymbolTerms> = infos.filter { it.quoteAsset == "USDT" }.associate {
        it.symbol to SymbolTerms(it.takerFee, halfSpread[it.symbol], it.minQuantity, maxOf(it.minNotional, it.minMarketNotional))
    }
    /** UTC gün başı → o günün 00:00 CMC listesi. */
    val cmc: TreeMap<Long, CmcList> = TreeMap<Long, CmcList>().apply {
        File(dir, "cmc/hist").listFiles { f -> f.name.endsWith(".json") }!!.forEach { f ->
            val o = json.parseToJsonElement(f.readText()).jsonObject
            val d = date(o.getValue("date").jsonPrimitive.content)
            put(d, CmcList(d, d, o.getValue("data").jsonArray.map { asset(it.jsonObject) }, CmcList.SOURCE_HISTORICAL))
        }
    }

    private fun asset(o: JsonObject): CmcAsset {
        val platform = o["platform"]?.takeUnless { it is JsonNull }?.jsonObject
        return CmcAsset(
            id = o.getValue("id").jsonPrimitive.content.toInt(), name = o.getValue("name").jsonPrimitive.content,
            symbol = o.getValue("symbol").jsonPrimitive.content, slug = (o["slug"] as? JsonPrimitive)?.content.orEmpty(),
            rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 0, tags = (o["tags"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
            platform = (platform?.get("slug") as? JsonPrimitive)?.content, tokenAddress = (platform?.get("token_address") as? JsonPrimitive)?.content,
            priceUsd = (o["price"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN,
        )
    }

    fun candles(sub: String, period: Long): Map<String, List<Candle>> {
        val files = File(dir, sub).listFiles { f -> f.name.endsWith(".csv.gz") }.orEmpty().sortedBy { it.name }
        val pool = Executors.newFixedThreadPool(4)
        try {
            return files.map { f -> pool.submit(Callable { f.name.removeSuffix(".csv.gz") to read(f, period) }) }
                .map { it.get() }.filter { it.second.isNotEmpty() }.toMap()
        } finally { pool.shutdown() }
    }

    private fun read(f: File, period: Long): List<Candle> = GZIPInputStream(f.inputStream()).bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.map { line ->
            val p = line.split(',')
            val t = p[0].toLong()
            Candle(t, p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), t + period, p[6].toDouble())
        }.toList()
    }.sortedBy { it.openTime }.distinctBy { it.openTime }

    /** [day] UTC gününün evreni: o gün 00:00'daki CMC listesi (yoksa en fazla 48 saat önceki), fiyat doğrulaması o anki MEXC kapanışıyla. */
    fun universeAt(day: Long, m15: Map<String, List<Candle>>): Universe? {
        val e = cmc.floorEntry(day) ?: return null
        if (day - e.key > 2 * KrConfig.DAY) return null
        val prices = HashMap<String, Double>()
        for ((s, list) in m15) {
            var lo = 0; var hi = list.size - 1; var ans = -1
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (list[mid].closeTime <= day) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
            if (ans >= 0 && day - list[ans].closeTime <= KrConfig.DAY) prices[s] = list[ans].close
        }
        return UniverseBuilder.build(e.value, infos, prices, day, checkTradable = false)
    }
}

private class Period(val name: String, val start: Long, val end: Long)

private val RESEARCH = Period("Araştırma", date("2025-11-28"), date("2026-05-01"))
private val VALIDATION = Period("Doğrulama", date("2026-05-01"), date("2026-08-01"))
private val FINAL = Period("Son değerlendirme", date("2026-08-01"), date("2026-11-13"))

private fun f2(x: Double) = String.format(Locale.ROOT, "%.2f", x)
private fun f4(x: Double) = String.format(Locale.ROOT, "%.4f", x)
private fun px(x: Double) = String.format(Locale.ROOT, "%.8g", x)
private fun tr(x: Double, d: Int = 2) = String.format(Locale("tr"), "%,.${d}f", x)

fun krResearch(o: Map<String, String>) {
    val data = KrData(File(o["data"] ?: "/home/user/research-v8"))
    val out = File(o["out"] ?: "docs/research/kr").apply { mkdirs() }
    val phase = o["phase"] ?: "research"
    val cfg = KrConfig()
    println("Veri yükleniyor…")
    val m15 = data.candles("mexc/m15", M15)
    val h4 = data.candles("mexc/h4", H4).mapValues { Trend4h(it.value, cfg) }
    println("15M: ${m15.size} parite, 4H: ${h4.size}; CMC günleri: ${data.cmc.size}")
    val cache = HashMap<Long, Universe?>()
    val universe = { d: Long -> cache.getOrPut(d) { data.universeAt(d, m15) } }
    val input = KrBacktestInput(m15, h4, { d -> universe(d)?.symbols.orEmpty() }, data.terms, "MEXC 15M")
    val report = StringBuilder()
    report.appendLine("Kırılım–Retest backtest — aşama: $phase")
    report.appendLine("Veri: MEXC 15M ${m15.size} parite (2025-11-14 → 2026-11-13), MEXC 4H; CMC günlük ilk 300 (${data.cmc.firstKey().let(::day)} → ${data.cmc.lastKey().let(::day)}).")
    report.appendLine("Ücret ve spread: MEXC exchangeInfo / bookTicker anlık görüntüsü ${data.snapshotTime} (geçmiş ücret/spread verisi yok).")
    report.appendLine()

    fun run(p: Period, c: KrConfig = cfg, costs: KrCosts = cfg.costs, tag: String = ""): KrResult {
        val t0 = System.currentTimeMillis()
        val r = KrBacktest(c, costs).run(input, p.start, p.end)
        println("${p.name} ${costs.name} $tag: ${r.trades.size} işlem, net ${f2(r.trades.sumOf { it.netPnl })} (${(System.currentTimeMillis() - t0) / 1000} sn)")
        return r
    }

    when (phase) {
        "research" -> {
            val normal = run(RESEARCH)
            val doubled = run(RESEARCH, costs = cfg.costs.doubled())
            report.append(section(normal, RESEARCH, universe))
            report.append(section(doubled, RESEARCH, universe, brief = true))
            writeCsv(normal, File(out, "kr-arastirma-islemler.csv"))
            writeCsv(doubled, File(out, "kr-arastirma-islemler-x2.csv"))
            writeDaily(normal, File(out, "kr-arastirma-gunluk.csv"))
            report.appendLine("== Sınırlı duyarlılık (yalnız araştırma dönemi; ana yapılandırma bu sonuçlara göre değiştirilmez) ==")
            report.appendLine("Değişiklik | İşlem | Net USDT | PF | Ort. R | En büyük düşüş %")
            val base = KrReport.metrics(normal.trades, normal.startEquity, normal.equityCurve, normal.start, normal.end)
            report.appendLine("Ana yapılandırma | ${base.trades} | ${f2(base.netPnl)} | ${f2(base.profitFactor)} | ${f4(base.meanR)} | ${f2(base.maxDrawdownPercent)}")
            val variants = listOf(
                "Net R:R ≥ 1,0" to cfg.copy(minNetRewardRisk = 1.0), "Net R:R ≥ 1,5" to cfg.copy(minNetRewardRisk = 1.5),
                "Retest 4 mum" to cfg.copy(retestBars = 4), "Retest 12 mum" to cfg.copy(retestBars = 12),
                "Hedef 1,0 × ATR1H" to cfg.copy(targetAtr1h = 1.0), "Hedef 2,0 × ATR1H" to cfg.copy(targetAtr1h = 2.0),
                "Kırılım hacmi 1,2×" to cfg.copy(breakoutVolumeMultiple = 1.2), "Kırılım hacmi 2,0×" to cfg.copy(breakoutVolumeMultiple = 2.0),
            )
            for ((name, c) in variants) {
                val r = run(RESEARCH, c, tag = name)
                val m = KrReport.metrics(r.trades, r.startEquity, r.equityCurve, r.start, r.end)
                report.appendLine("$name | ${m.trades} | ${f2(m.netPnl)} | ${f2(m.profitFactor)} | ${f4(m.meanR)} | ${f2(m.maxDrawdownPercent)}")
            }
            File(out, "kr-arastirma.txt").writeText(report.toString())
        }
        "final" -> {
            val results = LinkedHashMap<String, KrResult>()
            for (p in listOf(RESEARCH, VALIDATION, FINAL)) {
                val n = run(p)
                val d = run(p, costs = cfg.costs.doubled())
                results[p.name] = n
                results[p.name + " ×2"] = d
                report.append(section(n, p, universe))
                report.append(section(d, p, universe, brief = true))
                val slug = p.name.lowercase(Locale("tr")).replace(' ', '-').replace("ğ", "g").replace("ş", "s").replace("ı", "i").replace("ö", "o").replace("ü", "u").replace("ç", "c")
                writeCsv(n, File(out, "kr-$slug-islemler.csv"))
                writeCsv(d, File(out, "kr-$slug-islemler-x2.csv"))
                writeDaily(n, File(out, "kr-$slug-gunluk.csv"))
            }
            val v = results.getValue(VALIDATION.name)
            val f = results.getValue(FINAL.name)
            val vd = results.getValue(VALIDATION.name + " ×2")
            val fd = results.getValue(FINAL.name + " ×2")
            val dd = maxOf(
                KrReport.metrics(v.trades, v.startEquity, v.equityCurve, v.start, v.end).maxDrawdownPercent,
                KrReport.metrics(f.trades, f.startEquity, f.equityCurve, f.start, f.end).maxDrawdownPercent,
            )
            val verdict = KrReport.classify(v.trades + f.trades, dd, vd.trades + fd.trades, v.trades, f.trades)
            report.appendLine("== Ön kayıtlı sınıf (doğrulama + son değerlendirme, normal maliyet) ==")
            verdict.checks.forEach { (k, ok) -> report.appendLine((if (ok) "[✓] " else "[✗] ") + k) }
            report.appendLine("SONUÇ: ${verdict.evidence.title}")
            val all = v.trades + f.trades
            val ci = KrReport.bootstrapMeanR(all)
            report.appendLine("Ortalama net R: ${f4(all.map { it.rMultiple }.average())}, %90 GA [${f4(ci.first)}, ${f4(ci.second)}]")
            File(out, "kr-sonuc.txt").writeText(report.toString())
            writeUniverse(data, m15, File(out, "kr-evren.txt"), universe)
        }
    }
    println(report)
}

/** Yardımcı test: Binance 15M (farklı borsa) + CMC'nin en eski erişilebilir listesi → yanlı; ana sonuç değildir. */
fun krAux(o: Map<String, String>) {
    val data = KrData(File(o["data"] ?: "/home/user/research-v8"))
    val out = File(o["out"] ?: "docs/research/kr").apply { mkdirs() }
    val cfg = KrConfig()
    val mexc = data.candles("mexc/m15", M15)
    val first = data.universeAt(date("2025-11-14"), mexc)!!
    val m15 = data.candles("binance/m15", M15).filterKeys { it in first.symbols || it == "BTCUSDT" }
    val h4 = m15.mapValues { Trend4h(Bars.aggregate(it.value, H4), cfg) }
    val members = first.symbols intersect m15.keys
    val input = KrBacktestInput(m15, h4, { members }, data.terms, "Binance 15M (yardımcı)")
    val p = Period("Yardımcı (Binance)", date("2023-11-15"), date("2025-11-01"))
    val report = StringBuilder()
    report.appendLine("YARDIMCI TEST — ana sonuç değildir.")
    report.appendLine("Fiyat verisi Binance 15M (MEXC değil). Evren: CMC'nin erişilebilen en eski listesi (2025-11-14) ile MEXC'de eşleşen ve Binance'te verisi olan ${members.size} coin, bütün dönem boyunca sabit.")
    report.appendLine("Bu liste geleceğe bakar (2025'te ilk 300'de olanlar seçildi) → sonuç yukarı yanlıdır. Maliyetler MEXC ücret/spread anlık görüntüsüyle.")
    report.appendLine()
    val n = KrBacktest(cfg).run(input, p.start, p.end)
    val d = KrBacktest(cfg, cfg.costs.doubled()).run(input, p.start, p.end)
    report.append(section(n, p, { null }))
    report.append(section(d, p, { null }, brief = true))
    writeCsv(n, File(out, "kr-yardimci-binance-islemler.csv"))
    File(out, "kr-yardimci.txt").writeText(report.toString())
    println(report)
}

private fun section(r: KrResult, p: Period, universe: (Long) -> Universe?, brief: Boolean = false): String {
    val m = KrReport.metrics(r.trades, r.startEquity, r.equityCurve, r.start, r.end)
    val sb = StringBuilder()
    sb.appendLine("== ${p.name} (${day(p.start)} → ${day(p.end - 1)}) — ${r.label}, maliyet: ${r.costs.name} ==")
    sb.appendLine(metricsLine(m))
    if (r.book.positions.isNotEmpty()) sb.appendLine("Dönem sonunda açık ${r.book.positions.size} pozisyon (gerçekleşmemiş): ${r.book.positions.joinToString { it.symbol }}; dönem sonu özsermaye ${f2(r.endEquity)}")
    if (brief) { sb.appendLine(); return sb.toString() }
    val ci = KrReport.bootstrapMeanR(r.trades)
    sb.appendLine("Ortalama net R %90 bootstrap GA: [${f4(ci.first)}, ${f4(ci.second)}]")
    sb.appendLine("Çıkış nedenleri: " + r.trades.groupBy { it.reason }.entries.joinToString { (k, v) -> "${k.title} ${v.size} (net ${f2(v.sumOf { it.netPnl })})" })
    sb.appendLine("Aynı 15M mumda stop ve hedef (sıra bilinmiyor → stop sayıldı): ${m.ambiguous}")
    val scanned = (p.start until p.end step KrConfig.DAY).mapNotNull { universe(it) }
    if (scanned.isNotEmpty()) {
        sb.appendLine("Evren (gün başına ort.): ilk ${f2(scanned.map { it.top }.average())} → MEXC'de ${f2(scanned.map { it.onMexc }.average())} → taranan ${f2(scanned.map { it.symbols.size }.average())} (min ${scanned.minOf { it.symbols.size }}, maks ${scanned.maxOf { it.symbols.size }})")
    }
    val byCoin = r.trades.groupBy { it.symbol }.mapValues { (_, t) -> t.size to t.sumOf { it.netPnl } }
    sb.appendLine("Coin sayısı: ${byCoin.size}. En çok işlem: " + byCoin.entries.sortedByDescending { it.value.first }.take(8).joinToString { "${it.key} ${it.value.first} (${f2(it.value.second)})" })
    sb.appendLine("En iyi 5 coin: " + byCoin.entries.sortedByDescending { it.value.second }.take(5).joinToString { "${it.key} ${f2(it.value.second)}" })
    sb.appendLine("En kötü 5 coin: " + byCoin.entries.sortedBy { it.value.second }.take(5).joinToString { "${it.key} ${f2(it.value.second)}" })
    val best = byCoin.maxByOrNull { it.value.second }
    if (best != null) sb.appendLine("En iyi coin (${best.key}) çıkarılınca net: ${f2(r.trades.filter { it.symbol != best.key }.sumOf { it.netPnl })}")
    sb.appendLine("Aylık: " + r.trades.groupBy { day(it.entryTime).take(7) }.toSortedMap().entries.joinToString { (k, v) -> "$k: ${v.size} işlem, ${f2(v.sumOf { it.netPnl })}" })
    sb.appendLine("Huni (kaçan fırsat nedenleri, evrendeki coinler):")
    r.funnel.entries.sortedByDescending { it.value }.forEach { (k, v) -> sb.appendLine("  $k: $v") }
    sb.appendLine()
    return sb.toString()
}

private fun metricsLine(m: KrMetrics) = buildString {
    appendLine("İşlem ${m.trades} | Net ${f2(m.netPnl)} USDT (%${f2(m.netPercent)}) | PF ${f2(m.profitFactor)} | Beklenti ${f4(m.expectancy)} USDT/işlem | Ort. net R ${f4(m.meanR)} | Kazanan %${f2(m.winRate)} | En büyük düşüş %${f2(m.maxDrawdownPercent)}")
    appendLine("Maliyet ${f2(m.totalCosts)} USDT | Kazanan işlemlerin brüt kârı ${f2(m.grossWins)} | Maliyet/brüt kâr %${f2(m.costToGrossProfit * 100)}")
    append("Elde tutma ort. ${f2(m.avgHoldMinutes)} dk, medyan ${f2(m.medianHoldMinutes)} dk | Günde ${f2(m.tradesPerDay)}, haftada ${f2(m.tradesPerWeek)} işlem | Aktif gün ${m.activeDays}/${f2(m.days)}, aktif gün başına ${f2(m.tradesPerActiveDay)}")
}

private fun writeCsv(r: KrResult, f: File) {
    f.printWriter().use { w ->
        w.println("id,sembol,giris_utc,giris_tsi_gunu,giris_fiyat,miktar,stop,hedef,seviye,planlanan_net_rr,risk_usdt,cikis_utc,cikis_fiyat,cikis_nedeni,brut_usdt,komisyon_usdt,maliyet_usdt,net_usdt,net_r,sure_dk,ayni_mumda_stop_hedef,giris_komisyon,giris_yarim_spread,kayma")
        for (t in r.trades) {
            w.println(listOf(
                t.id, t.symbol, utc.format(java.time.Instant.ofEpochMilli(t.entryTime)), dayKey(t.entryTime), px(t.entryPrice), px(t.quantity), px(t.stop), px(t.target), px(t.level),
                f4(t.plannedNetRewardRisk), f4(t.riskAmount), utc.format(java.time.Instant.ofEpochMilli(t.exitTime)), px(t.exitPrice), t.reason.name,
                f4(t.grossPnl), f4(t.fees), f4(t.costs), f4(t.netPnl), f4(t.rMultiple), t.holdMinutes.toString(), t.ambiguous.toString(),
                px(t.entryLeg.fee), px(t.entryLeg.halfSpread), px(t.entryLeg.slippage),
            ).joinToString(","))
        }
    }
}

private fun writeDaily(r: KrResult, f: File) {
    val byDay = r.trades.groupBy { dayKey(it.entryTime) }
    f.printWriter().use { w ->
        w.println("tsi_gunu,islem,net_usdt")
        var d = r.start
        while (d < r.end) {
            val k = dayKey(d + 3 * 3600_000L)
            val t = byDay[k].orEmpty()
            w.println("$k,${t.size},${f4(t.sumOf { it.netPnl })}")
            d += KrConfig.DAY
        }
    }
}

private fun writeUniverse(data: KrData, m15: Map<String, List<Candle>>, f: File, universe: (Long) -> Universe?) {
    val sb = StringBuilder()
    val last = data.cmc.lastKey()
    val u = universe(last)!!
    sb.appendLine("CMC ilk 300 → MEXC eşleşmesi (liste: ${day(last)} 00:00 UTC, kaynak: ${u.list.source})")
    sb.appendLine("İlk ${u.top} → MEXC'de USDT paritesi ${u.onMexc} → taranan ${u.symbols.size}")
    UniverseStatus.entries.forEach { s -> sb.appendLine("  ${s.title}: ${u.entries.count { it.status == s }}") }
    for (s in UniverseStatus.entries.filter { it != UniverseStatus.INCLUDED }) {
        sb.appendLine()
        sb.appendLine("-- ${s.title} --")
        u.entries.filter { it.status == s }.forEach { e -> sb.appendLine("#${e.asset.rank} ${e.asset.symbol} (${e.asset.name}, CMC id ${e.asset.id})${e.mexcSymbol?.let { " → $it" } ?: ""} ${e.note}") }
    }
    sb.appendLine()
    sb.appendLine("Not: Backtestte MEXC'nin bugünkü exchangeInfo'su kullanıldı; MEXC'den kalkmış pariteler listede yok (hayatta kalma yanlılığı), ST etiketi ve işlem durumu geçmişe uygulanmadı.")
    f.writeText(sb.toString())
}

/** Canlı evren: CMC anahtarı ortam değişkeninden (CMC_API_KEY) okunur, kaynakta tutulmaz. */
suspend fun universeLive(o: Map<String, String>) {
    val key = System.getenv("CMC_API_KEY")
    val cmc = CmcClient({ key })
    val api = MexcRestClient()
    val list = cmc.latest()
    val infos = api.exchangeInfo()
    val prices = api.tickers24h().associate { it.symbol to it.lastPrice }
    val u = UniverseBuilder.build(list, infos, prices, System.currentTimeMillis())
    val sb = StringBuilder()
    sb.appendLine("CMC listesi zamanı: ${utc.format(java.time.Instant.ofEpochMilli(list.cmcTimestamp))} UTC (kaynak: ${list.source})")
    sb.appendLine("İlk ${u.top} → MEXC'de ${u.onMexc} → taranan ${u.symbols.size}")
    UniverseStatus.entries.forEach { s -> sb.appendLine("  ${s.title}: ${u.entries.count { it.status == s }}") }
    for (s in UniverseStatus.entries.filter { it != UniverseStatus.INCLUDED }) {
        sb.appendLine("-- ${s.title} --")
        u.entries.filter { it.status == s }.forEach { e -> sb.appendLine("#${e.asset.rank} ${e.asset.symbol} (${e.asset.name})${e.mexcSymbol?.let { " → $it" } ?: ""} ${e.note}") }
    }
    o["out"]?.let { File(it).writeText(sb.toString()) }
    println(sb)
}

/**
 * Uygulamadaki "Geçmiş test" ekranının kullandığı hattın aynısı ([com.spotbot.core.kr.KrHistory]) — canlı MEXC ve CMC
 * ile. [--karsilastir] verilirse aynı aralık araştırma verisiyle (dosyadan) de çalıştırılıp karşılaştırılır.
 */
suspend fun appHistory(o: Map<String, String>) {
    val days = (o["gun"] ?: "30").toInt()
    val equity = (o["bakiye"] ?: "1000").toDouble()
    val cache = File(o["onbellek"] ?: "/home/user/research-v8/app-cache")
    val key = System.getenv("CMC_API_KEY")
    val cmc = CmcClient({ key })
    val api = MexcRestClient()
    val now = System.currentTimeMillis()
    val today = now - Math.floorMod(now, KrConfig.DAY)
    val from = o["from"]?.let(::date) ?: (today - days * KrConfig.DAY)
    val to = o["to"]?.let(::date) ?: (now + 1)
    val history = com.spotbot.core.kr.KrHistory(api, cmc, if (o["bugun"] == "true") null else cmc, com.spotbot.core.kr.CandleFileStore(File(cache, "candles")), com.spotbot.core.kr.CmcListFileCache(File(cache, "cmc")))
    var lastPhase = ""
    val t0 = System.currentTimeMillis()
    val out = history.run(from, to, equity) { p ->
        if (p.phase != lastPhase || p.done == p.total || p.done % 50 == 0) { println("[${(System.currentTimeMillis() - t0) / 1000}s] ${p.phase} ${p.done}/${p.total}"); lastPhase = p.phase }
    }
    val m = out.metrics
    println("Evren kaynağı: ${out.source.title}")
    out.warnings.forEach { println("UYARI: $it") }
    println("Aralık: ${utc.format(java.time.Instant.ofEpochMilli(out.start))} → ${utc.format(java.time.Instant.ofEpochMilli(out.end))} UTC; verisi olan coin ${out.coinsWithData}; gün başına ilk ${f2(out.avgTop)} → MEXC'de ${f2(out.avgOnMexc)} → taranan ${f2(out.avgScanned)}")
    println(metricsLine(m))
    println("Son bakiye ${f2(out.result.endEquity)} | Toplam maliyet ${f2(m.totalCosts)} | Süre ${(System.currentTimeMillis() - t0) / 1000} sn")
    o["csv"]?.let { writeCsv(out.result, File(it)) }
    o["karsilastir"]?.let { dir ->
        val data = KrData(File(dir))
        val cfg = KrConfig()
        val m15 = data.candles("mexc/m15", M15)
        val h4 = data.candles("mexc/h4", H4).mapValues { Trend4h(it.value, cfg) }
        val cacheU = HashMap<Long, Universe?>()
        val input = KrBacktestInput(m15, h4, { d -> cacheU.getOrPut(d) { data.universeAt(d, m15) }?.symbols.orEmpty() }, data.terms, "araştırma verisi")
        val r = KrBacktest(cfg.copy(risk = cfg.risk.copy(startEquity = equity))).run(input, out.start, minOf(out.end, date("2026-11-13")))
        val app = out.result.trades.filter { it.entryTime < date("2026-11-13") }
        println("KARŞILAŞTIRMA (aynı aralık, 13.11 öncesi girişler): uygulama hattı ${app.size} işlem / net ${f2(app.sumOf { it.netPnl })}; araştırma verisi ${r.trades.size} işlem / net ${f2(r.trades.sumOf { it.netPnl })}")
        val a = app.map { it.symbol to it.entryTime }.toSet(); val b = r.trades.map { it.symbol to it.entryTime }.toSet()
        println("Ortak giriş: ${(a intersect b).size}; yalnız uygulamada: ${(a - b).size} ${(a - b).take(8)}; yalnız araştırmada: ${(b - a).size} ${(b - a).take(8)}")
    }
}

/** MEXC WebSocket fiyat akışı denemesi: [--sembol] için [--sn] saniye bookTicker dinler. */
suspend fun streamTest(o: Map<String, String>): Unit = kotlinx.coroutines.coroutineScope {
    val symbols = (o["sembol"] ?: "BTCUSDT,ETHUSDT").split(',')
    val seconds = (o["sn"] ?: "15").toLong()
    val ws = com.spotbot.core.exchange.mexc.MexcWebSocketClient(this)
    var n = 0
    val job = launch {
        ws.events.collect { e ->
            if (e is com.spotbot.core.exchange.mexc.StreamEvent.BookTickerUpdate) {
                n++
                if (n <= 5) println("${e.ticker.symbol} alış ${e.ticker.bid} satış ${e.ticker.ask}")
            }
        }
    }
    ws.setSubscriptions(symbols.map { com.spotbot.core.exchange.mexc.MexcChannels.bookTicker(it) }.toSet())
    ws.start()
    repeat(seconds.toInt()) { kotlinx.coroutines.delay(1000); println("t=${it + 1}s ${ws.state.value.status} msj=$n hata=${ws.state.value.lastError}") }
    println("Durum: ${ws.state.value}")
    println("$seconds sn'de $n fiyat güncellemesi")
    ws.stop(); job.cancel()
}

/** 2. tur (docs/KR2-ONKAYIT.md): B0 + V1–V4 araştırmada; kuralla VK seçimi; değerlendirme 1 (MEXC) ve 2 (Binance) bir kez. */
fun krRound2(o: Map<String, String>) {
    val data = KrData(File(o["data"] ?: "/home/user/research-v8"))
    val out = File(o["out"] ?: "docs/research/kr2").apply { mkdirs() }
    val base = KrConfig()
    val variants = linkedMapOf(
        "B0 temel" to base,
        "V1 stop tamponu 1,0 ATR" to base.copy(stopBufferAtr = 1.0),
        "V2 BTC 4H yön şartı" to base.copy(requireBtcUptrend = true),
        "V3 retest kalitesi" to base.copy(retestMinCloseFraction = 0.5, retestMinCloseAboveAtr = 0.1),
        "V4 maliyet payı %25" to base.copy(maxCostShareOfTarget = 0.25),
    )
    val m15 = data.candles("mexc/m15", M15)
    val h4 = data.candles("mexc/h4", H4).mapValues { Trend4h(it.value, base) }
    val cacheU = HashMap<Long, Universe?>()
    val input = KrBacktestInput(m15, h4, { d -> synchronized(cacheU) { cacheU.getOrPut(d) { data.universeAt(d, m15) } }?.symbols.orEmpty() }, data.terms, "MEXC 15M")
    val research = Period("Araştırma", date("2025-11-28"), date("2026-08-01"))
    val eval1 = Period("Değerlendirme 1 (MEXC; ilk turda temel kurallarla görüldü)", date("2026-08-01"), date("2026-11-13"))
    val sb = StringBuilder()
    sb.appendLine("Kırılım–Retest 2. tur — docs/KR2-ONKAYIT.md")
    sb.appendLine("Ücret/spread anlık görüntüsü: ${data.snapshotTime}. Her dönem 1.000 USDT.")
    sb.appendLine()
    fun line(name: String, r: KrResult): String {
        val m = KrReport.metrics(r.trades, r.startEquity, r.equityCurve, r.start, r.end)
        val wins = r.trades.filter { it.netPnl > 0 }; val losses = r.trades.filter { it.netPnl <= 0 }
        return "$name | ${m.trades} | ${f2(m.netPnl)} | ${f4(m.expectancy)} | ${f4(m.meanR)} | ${f2(m.profitFactor)} | ${f2(m.maxDrawdownPercent)} | ${f2(m.tradesPerDay)} | ${f2(m.avgHoldMinutes)} | ${f2(m.totalCosts)} | ${f2(m.costToGrossProfit * 100)} | ${f2(m.winRate)} | ${f4(wins.map { it.rMultiple }.average())} | ${f4(losses.map { it.rMultiple }.average())}"
    }
    val header = "Yapılandırma | İşlem | Net USDT | Beklenti USDT | Ort. net R | PF | Maks. düşüş % | Günlük işlem | Ort. süre dk | Toplam maliyet | Maliyet/brüt kâr % | Kazanan % | Ort. kazanç R | Ort. kayıp R"
    sb.appendLine("== Araştırma ${day(research.start)} → ${day(research.end - 1)} ==")
    sb.appendLine(header)
    val res = LinkedHashMap<String, Pair<KrResult, KrResult>>()
    for ((name, c) in variants) {
        val n = KrBacktest(c).run(input, research.start, research.end)
        val d = KrBacktest(c, c.costs.doubled()).run(input, research.start, research.end)
        res[name] = n to d
        sb.appendLine(line(name, n)); sb.appendLine(line("$name ×2", d))
        writeCsv(n, File(out, "kr2-arastirma-${name.substringBefore(' ')}-islemler.csv"))
        println("araştırma $name: ${n.trades.size} / ${f2(n.trades.sumOf { it.netPnl })}")
    }
    fun meanR(r: KrResult) = if (r.trades.isEmpty()) Double.NaN else r.trades.sumOf { it.rMultiple } / r.trades.size
    val (b0n, b0d) = res.getValue("B0 temel")
    sb.appendLine()
    sb.appendLine("== Seçim kuralı (ön kayıt §4) ==")
    val improved = ArrayList<String>()
    for ((name, pair) in res) {
        if (name.startsWith("B0")) continue
        val dR = meanR(pair.first) - meanR(b0n)
        val ok = dR >= 0.05 && pair.first.trades.size >= 60 && meanR(pair.second) >= meanR(b0d)
        sb.appendLine("$name: Δ ort. R ${f4(dR)}, işlem ${pair.first.trades.size}, ×2 ort. R ${f4(meanR(pair.second))} (B0 ×2 ${f4(meanR(b0d))}) → ${if (ok) "İYİLEŞTİRDİ" else "iyileştirmedi"}")
        if (ok) improved += name
    }
    if (improved.isEmpty()) {
        sb.appendLine("Hiçbir varyant iyileştirmedi → B0 kalır; avantaj bulunamadı.")
        File(out, "kr2-sonuc.txt").writeText(sb.toString()); println(sb); return
    }
    var vk = base
    if ("V1 stop tamponu 1,0 ATR" in improved) vk = vk.copy(stopBufferAtr = 1.0)
    if ("V2 BTC 4H yön şartı" in improved) vk = vk.copy(requireBtcUptrend = true)
    if ("V3 retest kalitesi" in improved) vk = vk.copy(retestMinCloseFraction = 0.5, retestMinCloseAboveAtr = 0.1)
    if ("V4 maliyet payı %25" in improved) vk = vk.copy(maxCostShareOfTarget = 0.25)
    sb.appendLine("VK = ${improved.joinToString(" + ")}")
    val vkR = KrBacktest(vk).run(input, research.start, research.end)
    val vkRd = KrBacktest(vk, vk.costs.doubled()).run(input, research.start, research.end)
    sb.appendLine(header); sb.appendLine(line("VK araştırma", vkR)); sb.appendLine(line("VK araştırma ×2", vkRd))
    sb.appendLine()
    sb.appendLine("== ${eval1.name} ${day(eval1.start)} → ${day(eval1.end - 1)} (bir kez) ==")
    val e1 = KrBacktest(vk).run(input, eval1.start, eval1.end)
    val e1d = KrBacktest(vk, vk.costs.doubled()).run(input, eval1.start, eval1.end)
    val b1 = KrBacktest(base).run(input, eval1.start, eval1.end)
    sb.appendLine(header); sb.appendLine(line("VK", e1)); sb.appendLine(line("VK ×2", e1d)); sb.appendLine(line("B0 (karşılaştırma)", b1))
    writeCsv(e1, File(out, "kr2-degerlendirme1-VK-islemler.csv"))
    writeCsv(e1d, File(out, "kr2-degerlendirme1-VK-islemler-x2.csv"))
    writeDaily(e1, File(out, "kr2-degerlendirme1-VK-gunluk.csv"))
    // Değerlendirme 2: Binance (yardımcı, yanlı).
    val first = data.universeAt(date("2025-11-14"), m15)!!
    val bm15 = data.candles("binance/m15", M15).filterKeys { it in first.symbols || it == "BTCUSDT" }
    val members = first.symbols intersect bm15.keys
    val binput = KrBacktestInput(bm15, bm15.mapValues { Trend4h(Bars.aggregate(it.value, H4), vk) }, { members }, data.terms, "Binance 15M (yardımcı)")
    val e2 = KrBacktest(vk).run(binput, date("2023-11-15"), date("2025-11-01"))
    val e2d = KrBacktest(vk, vk.costs.doubled()).run(binput, date("2023-11-15"), date("2025-11-01"))
    sb.appendLine()
    sb.appendLine("== Değerlendirme 2 (Binance 15M 2023-11-15 → 2025-10-31; yardımcı, yanlı; ilk turda temel kurallarla görüldü) ==")
    sb.appendLine(header); sb.appendLine(line("VK", e2)); sb.appendLine(line("VK ×2", e2d))
    writeCsv(e2, File(out, "kr2-degerlendirme2-binance-VK-islemler.csv"))
    // Sınıf (§5).
    val m = KrReport.metrics(e1.trades, e1.startEquity, e1.equityCurve, e1.start, e1.end)
    val ci = KrReport.bootstrapMeanR(e1.trades)
    val best = e1.trades.groupBy { it.symbol }.maxByOrNull { (_, t) -> t.sumOf { it.netPnl } }?.key
    val checks = listOf(
        "Değerlendirme 1'de ≥ 60 işlem (${m.trades})" to (m.trades >= 60),
        "Net beklenti > 0 (${f4(m.expectancy)})" to (m.trades > 0 && m.netPnl > 0),
        "PF ≥ 1,2 (${f2(m.profitFactor)})" to (m.profitFactor >= 1.2),
        "En büyük düşüş ≤ %6 (%${f2(m.maxDrawdownPercent)})" to (m.maxDrawdownPercent <= 6),
        "×2 maliyette net > 0 (${f2(e1d.trades.sumOf { it.netPnl })})" to (e1d.trades.sumOf { it.netPnl } > 0),
        "Ort. R %90 GA alt sınırı > 0 (${f4(ci.first)})" to (ci.first > 0),
        "En iyi coin ($best) çıkarılınca net > 0" to (e1.trades.filter { it.symbol != best }.sumOf { it.netPnl } > 0),
        "Değerlendirme 2 net > 0 (${f2(e2.trades.sumOf { it.netPnl })})" to (e2.trades.sumOf { it.netPnl } > 0),
    )
    sb.appendLine()
    sb.appendLine("== Sınıf (ön kayıt §5) ==")
    checks.forEach { (k, ok) -> sb.appendLine((if (ok) "[✓] " else "[✗] ") + k) }
    val verdict = when { m.trades == 0 || m.netPnl <= 0 -> "Başarısız"; checks.all { it.second } -> "Avantaj gösterildi (canlı paper doğrulaması gerekli)"; else -> "Yetersiz kanıt" }
    sb.appendLine("SONUÇ: $verdict")
    File(out, "kr2-sonuc.txt").writeText(sb.toString())
    println(sb)
}

/**
 * Uygulama servisinin döngüleri canlı veriyle (geçici kayıtla): 3 dakikalık hizalı tarama + 10 sn koruma.
 * [--dk] dakika çalışır; her taramanın süresi, gecikmesi ve sonucu yazılır.
 */
suspend fun paperLive(o: Map<String, String>): Unit = kotlinx.coroutines.coroutineScope {
    val minutes = (o["dk"] ?: "7").toLong()
    val dir = File(o["klasor"] ?: "/home/user/research-v8/paper-canli").apply { mkdirs() }
    val key = System.getenv("CMC_API_KEY")
    val paper = com.spotbot.core.kr.KrPaper(MexcRestClient(), CmcClient({ key }), com.spotbot.core.kr.MemoryKrStore(), cfg = KrConfig(), candleStore = com.spotbot.core.kr.CandleFileStore(File(dir, "mumlar")))
    val end = System.currentTimeMillis() + minutes * 60_000
    val protect = launch { while (true) { paper.protect(); kotlinx.coroutines.delay(10_000) } }
    while (System.currentTimeMillis() < end) {
        val now = System.currentTimeMillis()
        val slot = now - Math.floorMod(now, com.spotbot.core.kr.KrPaper.SCAN_INTERVAL) + com.spotbot.core.kr.KrPaper.SCAN_INTERVAL + com.spotbot.core.kr.KrPaper.SETTLE_MILLIS
        if (slot > end) break
        kotlinx.coroutines.delay(slot - now)
        launch {
            val ran = paper.scan(com.spotbot.core.kr.BotMode.FULL, slot)
            val s = paper.state.value
            val t = s.scanTiming
            println("[${utc.format(java.time.Instant.ofEpochMilli(slot))} UTC] tarama ${if (ran) "yapıldı" else "ATLANDI (önceki sürüyor)"}: süre ${t.lastDurationMs} ms, gecikme ${t.lastDelayMs} ms, hata ${t.lastError}, " +
                "evren ${s.universe?.top}→${s.universe?.onMexc}→${s.universe?.included?.size}, değerlendirilen 15M ${s.scan?.barClose?.let { utc.format(java.time.Instant.ofEpochMilli(it)) }}, " +
                "durumlar ${s.scan?.items?.groupBy { it.stage }?.mapValues { it.value.size }}, açık ${s.book.positions.size}, işlem ${s.book.trades.size}, atlanan ${t.skippedOverlap}")
        }
    }
    kotlinx.coroutines.delay(30_000)
    protect.cancel()
    val s = paper.state.value
    println("Huni (bu çalıştırma): ${s.funnel}")
    s.log.takeLast(15).forEach { println("günlük: ${utc.format(java.time.Instant.ofEpochMilli(it.time))} ${it.text}") }
}
