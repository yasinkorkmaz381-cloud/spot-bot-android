package com.spotbot.cli

import com.spotbot.core.research.CostScenario
import com.spotbot.core.research.H4Dataset
import com.spotbot.core.research.Universe
import com.spotbot.core.research.V3Config
import com.spotbot.core.research.V3Engine
import com.spotbot.core.research.V3Result
import com.spotbot.core.research.V3Strategies
import com.spotbot.core.research.V3Strategy
import com.spotbot.core.research.V3Trade
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.math.sqrt

private val v3Json = Json { ignoreUnknownKeys = true }
private fun date(s: String) = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun d(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()

private fun line(label: String, r: V3Result) =
    "  %-26s %4d işlem kazanma %%%5.1f PF %5.2f ort %+.3fR (t=%+.2f) net %%%+7.2f düşüş %%%5.2f ücret %6.1f spread+kayma %6.1f piyasada %%%4.0f%s".format(
        label, r.trades, r.winRatePercent, r.profitFactor, r.averageR, r.tStat, r.netReturnPercent, r.maxDrawdownPercent, r.fees, r.friction, r.exposurePercent,
        if (r.trades < 30) "  ⚠ <30 işlem" else "",
    )

private fun passes(r: V3Result) = r.averageR > 0 && r.profitFactor >= 1.1 && r.trades >= 30

private fun pick(results: List<V3Result>): Int {
    val ok = results.indices.filter { results[it].trades >= 30 }
    return if (ok.isNotEmpty()) ok.maxBy { results[it].averageR } else results.indices.maxBy { results[it].trades }
}

/** Ön kayıtlı V3 araştırma protokolü (docs/STRATEJI-V3-ONKAYIT.md). */
suspend fun v3(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val universe = Universe.of(dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().map { v3Json.decodeFromString(H4Dataset.serializer(), it.readText()) })
    val dataEnd = universe.btc.bars.last().openTime + com.spotbot.core.research.H4_MS
    val train = date("2020-08-01") to date("2023-01-01")
    val valid = date("2023-01-01") to date("2025-01-01")
    val hold = date("2025-01-01") to dataEnd
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    fun run(jobs: List<Triple<V3Strategy, Pair<Long, Long>, CostScenario>>): List<V3Result> =
        jobs.map { (s, p, c) -> pool.submit<V3Result> { V3Engine(universe, V3Config(p.first, p.second, c)).run(s) } }.map { it.get() }

    println("Veri: ${universe.symbols.size} parite · ${d(universe.btc.bars.first().openTime)} → ${d(dataEnd)}")
    println("Dönemler: eğitim ${d(train.first)}→${d(train.second)} · doğrulama ${d(valid.first)}→${d(valid.second)} · son test ${d(hold.first)}→${d(hold.second)}")
    val tradableCounts = listOf(train.first, valid.first, hold.first, dataEnd - 86_400_000L).map { t -> d(t) to universe.symbols.values.count { it.tradable(t, 1_000_000.0) } }
    println("İşlem yapılabilir parite sayısı (hacim ≥ 1M USDT/gün, ≥ 210 gün geçmiş): " + tradableCounts.joinToString { "${it.first}: ${it.second}" })
    val all = ArrayList<V3Result>()
    val verdicts = ArrayList<String>()
    for ((family, opts) in V3Strategies.families) {
        println("\n════ ${opts.first().name} ════")
        val tr = run(opts.map { Triple(it, train, CostScenario.MEDIUM) })
        println("Eğitim (orta maliyet):"); opts.indices.forEach { println(line(opts[it].option, tr[it])) }
        val best = pick(tr)
        val chosen = opts[best]
        println("  → Seçilen: ${chosen.option}")
        val va = run(opts.map { Triple(it, valid, CostScenario.MEDIUM) })
        println("Doğrulama (orta maliyet; seçim dışı seçenekler yalnız duyarlılık için):"); opts.indices.forEach { println(line(opts[it].option + if (it == best) " ◀" else "", va[it])) }
        val ho = run(opts.map { Triple(it, hold, CostScenario.MEDIUM) })
        println("Son test (orta maliyet):"); opts.indices.forEach { println(line(opts[it].option + if (it == best) " ◀" else "", ho[it])) }
        val costs = run(CostScenario.all.flatMap { c -> listOf(Triple(chosen, valid, c), Triple(chosen, hold, c)) })
        println("Maliyet duyarlılığı (seçilen seçenek):")
        CostScenario.all.forEachIndexed { i, c -> println(line("doğrulama · ${c.name}", costs[2 * i])); println(line("son test · ${c.name}", costs[2 * i + 1])) }
        val full = run(listOf(Triple(chosen, train.first to dataEnd, CostScenario.MEDIUM))).single()
        println("Tüm dönem (orta maliyet): ${line("", full).trim()}")
        println("  BTC al-tut aynı dönem: %%%+.1f · USDT'de bekleme: %%0".format(full.btcBuyHoldPercent))
        println("BTC rejimine göre (tüm dönem, giriş anındaki rejim):")
        full.byRegime.forEach { println("  %-10s %4d işlem ort %+.3fR net %+8.2f USDT%s".format(it.regime, it.trades, it.averageR, it.netPnl, if (it.trades < 30) "  ⚠ <30" else "")) }

        // İleri yürüyen test: 12 ay eğitim → 6 ay test.
        val month = 30L * 86_400_000L
        val wfTrades = ArrayList<V3Trade>()
        var testStart = date("2021-08-01")
        println("İleri yürüyen test (12 ay eğitim → 6 ay test):")
        var foldReturns = 0.0
        while (testStart < dataEnd - 30 * 86_400_000L) {
            val testEnd = minOf(dataEnd, testStart + 6 * month)
            val trw = run(opts.map { Triple(it, testStart - 12 * month to testStart, CostScenario.MEDIUM) })
            val bi = pick(trw)
            val te = run(listOf(Triple(opts[bi], testStart to testEnd, CostScenario.MEDIUM))).single()
            wfTrades += te.tradeList
            foldReturns += te.netReturnPercent
            println("  %s→%s seçilen %-10s (eğitim %+.2fR, %d) · test %3d işlem %+.3fR net %%%+.2f".format(d(testStart), d(testEnd), opts[bi].option, trw[bi].averageR, trw[bi].trades, te.trades, te.averageR, te.netReturnPercent))
            testStart = testEnd
        }
        val rs = wfTrades.map { it.r }
        val mean = if (rs.isEmpty()) 0.0 else rs.average()
        val sd = if (rs.size < 2) 0.0 else sqrt(rs.sumOf { (it - mean) * (it - mean) } / (rs.size - 1))
        val gp = wfTrades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val gl = -wfTrades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        println("  Birleşik görülmemiş dönem: %d işlem ort %+.3fR (t=%+.2f) PF %.2f · katman getirileri toplamı %%%+.2f".format(
            wfTrades.size, mean, if (sd > 0) mean / sd * sqrt(rs.size.toDouble()) else 0.0, if (gl > 0) gp / gl else 0.0, foldReturns))

        val vOk = passes(va[best])
        val hOk = passes(ho[best])
        val verdict = when {
            vOk && hOk && mean > 0 -> "UMUT VERİCİ"
            vOk && !hOk -> "BAŞARISIZ (doğrulamayı geçti, son testte tutmadı)"
            !vOk -> "BAŞARISIZ (doğrulamayı geçemedi)"
            else -> "BAŞARISIZ (ileri yürüyen sonuç negatif)"
        }
        verdicts += "${opts.first().name} [${chosen.option}]: $verdict · " + "doğrulama %+.3fR PF %.2f (%d) · son test %+.3fR PF %.2f (%d) · ileri yürüyen %+.3fR (%d)".format(
            va[best].averageR, va[best].profitFactor, va[best].trades, ho[best].averageR, ho[best].profitFactor, ho[best].trades, mean, wfTrades.size)
        println("Karşılaştırma (seçilen seçenek, orta maliyet): doğrulama %%%+.2f · BTC al-tut %%%+.1f · USDT %%0 | son test %%%+.2f · BTC al-tut %%%+.1f · USDT %%0".format(
            va[best].netReturnPercent, va[best].btcBuyHoldPercent, ho[best].netReturnPercent, ho[best].btcBuyHoldPercent))
        all += tr + va + ho + costs + full
    }
    println("\n════ SONUÇ (ön kayıtlı ölçüt: orta maliyette ort R > 0, PF ≥ 1,1, ≥ 30 işlem) ════")
    verdicts.forEach { println("  $it") }
    pool.shutdown()
    options["out"]?.let { File(it).writeText(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(V3Result.serializer()), all.map { r -> r.copy(tradeList = emptyList()) })); println("Rapor: $it") }
}

/**
 * S1 ileri test izleyicisini gerçek MEXC verisiyle bir kez çalıştırır (uygulamadakiyle aynı kod). `--days N` ile
 * başlangıç N gün geriye alınır: yalnız doğrulama içindir; o kısım ileri test değil, geriye dönük testtir.
 */
suspend fun forward(options: Map<String, String>) {
    val days = options["days"]?.toLong() ?: 0L
    val start = (System.currentTimeMillis() - days * com.spotbot.core.research.DAY_MS) / com.spotbot.core.research.H4_MS * com.spotbot.core.research.H4_MS
    val store = com.spotbot.core.research.InMemoryForwardStore(
        com.spotbot.core.research.ForwardState("S1 Göreceli güç (BTC'ye göre)", "L=30 gün", start, 1_000.0).takeIf { days > 0 },
    )
    val s = com.spotbot.core.research.ForwardTracker(com.spotbot.core.exchange.mexc.MexcRestClient(), store).refresh()
    println("Başlangıç ${d(s.startedAt)} · son kapanmış 4H ${java.time.Instant.ofEpochMilli(s.dataEnd)} · parite ${s.symbolsLoaded} (işlem yapılabilir ${s.tradableNow}) · hata: ${s.lastError ?: "yok"}")
    val r = s.result ?: return println("Henüz yeni 4H mumu yok.")
    println(line("ileri test", r))
    r.tradeList.forEach { println("  ${it.symbol} ${d(it.entryTime)}→${d(it.exitTime)} ${"%.4f".format(it.entryPrice)}→${"%.4f".format(it.exitPrice)} net ${"%+.2f".format(it.netPnl)} ${it.reason}") }
    r.open.forEach { println("  AÇIK ${it.symbol} ${d(it.entryTime)} ${"%.4f".format(it.entryPrice)}→${"%.4f".format(it.lastPrice)} stop ${"%.4f".format(it.stop)} net ${"%+.2f".format(it.unrealizedPnl)}") }
}

/** Seçilen S1 seçeneğinin (L=30) kârı birkaç işleme ya da pariteye mi bağlı? Doğrulama ve son test için. */
fun v3Concentration(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data klasörü gerekli"))
    val universe = Universe.of(dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted().map { v3Json.decodeFromString(H4Dataset.serializer(), it.readText()) })
    val dataEnd = universe.btc.bars.last().openTime + com.spotbot.core.research.H4_MS
    for ((label, p) in listOf("doğrulama" to (date("2023-01-01") to date("2025-01-01")), "son test" to (date("2025-01-01") to dataEnd))) {
        val r = V3Engine(universe, V3Config(p.first, p.second)).run(com.spotbot.core.research.RelativeStrength(30))
        val pn = r.tradeList.map { it.netPnl }.sortedDescending()
        val total = pn.sum()
        val bySymbol = r.tradeList.groupBy { it.symbol }.mapValues { e -> e.value.sumOf { it.netPnl } }.toList().sortedByDescending { it.second }
        println("$label: ${r.trades} işlem, net %+.1f USDT · en iyi 5 işlem %+.1f · onlarsız %+.1f · en iyi 10 işlem olmadan %+.1f".format(total, pn.take(5).sum(), total - pn.take(5).sum(), total - pn.take(10).sum()))
        println("  parite katkısı (ilk 5): " + bySymbol.take(5).joinToString { "%s %+.1f".format(it.first, it.second) } + " · en iyi parite olmadan %+.1f".format(total - bySymbol.first().second))
    }
}

/** V4 geniş evren taramasını gerçek MEXC verisiyle bir kez çalıştırır (uygulamadakiyle aynı kod). */
suspend fun wideScan() {
    val st = com.spotbot.core.scanner.WideScanner(com.spotbot.core.exchange.mexc.MexcRestClient()).scan()
    st.lastError?.let { println("HATA: $it") }
    val r = st.result ?: return
    println("Toplam USDT paritesi ${r.totalPairs} · ürün dışı ${r.productExcluded} · işlem kapalı/ST ${r.notTradable} · 1D incelenen ${r.analyzed}")
    println("Likidite filtresini geçen ${r.liquidityPassed} · trend ${r.trendPassed} · BTC'den güçlü ${r.strengthPassed} · aday ${r.candidates}")
    println("BTC 30g %%%+.1f · istek ${r.requests} · süre ${r.durationMs / 1000} sn".format(r.btcMomentum30Percent))
    println("Elenme nedenleri:"); r.rejections.forEach { (k, v) -> println("  %5d  %s".format(v, k)) }
    println("En güçlü 20:")
    r.top.forEach { c ->
        println("  %2s %-12s RS %+6.1f  7g %+6.1f  30g %+6.1f  hacim %6.1fM spread %.3f%% stop %%%.1f vol %%%.1f derinlik %s poz %s korel %s %s".format(
            c.rank ?: "-", c.symbol, c.relativeStrength, c.momentum7Percent, c.momentum30Percent, c.medianVolume30 / 1e6, c.spreadPercent,
            c.stopDistancePercent ?: Double.NaN, c.volatilityPercent ?: Double.NaN, c.bidDepth1Percent?.let { "%.0f".format(it) }, c.positionSize?.let { "%.0f".format(it) },
            c.maxCorrelation?.let { "%.2f".format(it) }, (c.sectors.take(3) + c.warnings).joinToString(" | ")))
    }
    println("Güçlü ama elenen:"); r.strongestRejected.forEach { println("  %-12s 30g %+6.1f  %s".format(it.symbol, it.momentum30Percent, it.rejection?.title)) }
    println("Sektörler (uygun adaylar): ${r.sectorCounts}")
}

/**
 * V4 ön kayıtlı evren karşılaştırması (docs/EVREN-V4-ONKAYIT.md): S1 (L=30) kuralları sabit; dar 28 parite, zamana bağlı
 * ilk 20/50/100 ve tüm uygun pariteler.
 */
fun v4(options: Map<String, String>) {
    val dir = File(options["data"] ?: error("--data 4H klasörü gerekli"))
    val plates: Map<String, List<String>> = options["info"]?.let { f ->
        kotlinx.serialization.json.Json.parseToJsonElement(File(f).readText()).let { root ->
            (root as kotlinx.serialization.json.JsonObject)["symbols"]!!.let { it as kotlinx.serialization.json.JsonArray }.associate { e ->
                val o = e as kotlinx.serialization.json.JsonObject
                (o["symbol"] as kotlinx.serialization.json.JsonPrimitive).content to
                    ((o["conceptPlates"] as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty())
            }
        }
    }.orEmpty()
    val files = dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.sorted()
    val universe = Universe.of(files.map { v3Json.decodeFromString(H4Dataset.serializer(), it.readText()) })
    val dataEnd = universe.btc.bars.last().openTime + com.spotbot.core.research.H4_MS
    val valid = date("2023-01-01") to date("2025-01-01")
    val hold = date("2025-01-01") to dataEnd
    val full = date("2020-08-01") to dataEnd
    val narrow = com.spotbot.core.research.V3_UNIVERSE.toSet()
    data class U(val name: String, val max: Int?, val fixed: Set<String>?)
    val universes = listOf(U("Dar liste (28)", null, narrow), U("İlk 20", 20, null), U("İlk 50", 50, null), U("İlk 100", 100, null), U("Tümü", null, null))
    val costs = listOf(CostScenario.MEDIUM, CostScenario.TIERED_MEDIUM, CostScenario.TIERED_PESSIMISTIC)
    val s1 = com.spotbot.core.research.RelativeStrength(30)
    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    fun cfg(p: Pair<Long, Long>, c: CostScenario, x: U) = V3Config(p.first, p.second, c, maxUniverse = x.max, fixedUniverse = x.fixed)

    println("Veri: ${universe.symbols.size} parite (4H) · ${d(universe.btc.bars.first().openTime)} → ${d(dataEnd)}")
    println("Bu veri yalnız bugün MEXC'de bulunan paritelerdir; borsadan kaldırılanlar indirilemedi (hayatta kalma yanlılığı).")
    val listing = universe.symbols.values.map { it.bars.first().openTime }
    println("İlk mum yılı dağılımı: " + listing.groupBy { d(it).take(4) }.toSortedMap().map { "${it.key}: ${it.value.size}" }.joinToString())
    println("Zamana bağlı işlem yapılabilir parite sayısı (≥210 gün, medyan ≥1M USDT):")
    for (day in listOf("2021-01-01", "2022-01-01", "2023-01-01", "2024-01-01", "2025-01-01", "2026-01-01", d(dataEnd - 86_400_000L))) {
        val t = date(day)
        val n = universe.symbols.values.count { it.tradable(t, 1_000_000.0) }
        val inNarrow = universe.symbols.values.count { it.symbol in narrow && it.tradable(t, 1_000_000.0) }
        println("  $day: tümü $n · dar listeden $inNarrow")
    }
    var binding = IntArray(3)
    var dayCount = 0
    var t = full.first
    while (t < full.second) {
        val n = universe.symbols.values.count { it.tradable(t, 1_000_000.0) }
        listOf(20, 50, 100).forEachIndexed { i, k -> if (n > k) binding[i]++ }
        dayCount++
        t += com.spotbot.core.research.DAY_MS
    }
    println("İşlem yapılabilir parite sayısının N'yi aştığı günler (N sınırı etkili): ilk 20 → ${binding[0]}/$dayCount gün · ilk 50 → ${binding[1]} · ilk 100 → ${binding[2]}")

    val jobs = universes.flatMap { x -> listOf("doğrulama" to valid, "son test" to hold, "tüm dönem" to full).flatMap { p -> costs.map { c -> Triple(x, p, c) } } }
    val results = jobs.map { (x, p, c) -> pool.submit<V3Result> { V3Engine(universe, cfg(p.second, c, x)).run(s1) } }.map { it.get() }
    val byKey = jobs.zip(results).associate { (k, r) -> Triple(k.first.name, k.second.first, k.third.name) to r }

    fun concentration(r: V3Result): String {
        val bySymbol = r.tradeList.groupBy { it.symbol }.mapValues { e -> e.value.sumOf { it.netPnl } }.toList().sortedByDescending { it.second }
        val total = r.tradeList.sumOf { it.netPnl }
        val withoutBest = r.tradeList.filter { it.symbol != bySymbol.firstOrNull()?.first }
        val rWithout = if (withoutBest.isEmpty()) 0.0 else withoutBest.map { it.r }.average()
        return "parite ${bySymbol.size} · en iyi ${bySymbol.firstOrNull()?.let { "%s %+.1f".format(it.first, it.second) }} · onsuz %+.1f USDT, ort %+.3fR".format(total - (bySymbol.firstOrNull()?.second ?: 0.0), rWithout)
    }
    fun correlation(r: V3Result): String {
        val tr = r.tradeList.sortedBy { it.entryTime }
        val cs = ArrayList<Double>()
        for (i in tr.indices) for (j in i + 1 until tr.size) {
            val a = tr[i]; val b = tr[j]
            if (b.entryTime >= a.exitTime) continue
            val da = universe.symbols.getValue(a.symbol); val db = universe.symbols.getValue(b.symbol)
            val ia = da.day(b.entryTime); val ib = db.day(b.entryTime)
            if (ia < 61 || ib < 61) continue
            val ra = DoubleArray(60) { k -> da.dClose[ia - 59 + k] / da.dClose[ia - 60 + k] - 1 }
            val rb = DoubleArray(60) { k -> db.dClose[ib - 59 + k] / db.dClose[ib - 60 + k] - 1 }
            cs += com.spotbot.core.scanner.WideScanner.correlation(ra, rb)
        }
        return if (cs.isEmpty()) "eş zamanlı çift yok" else "eş zamanlı ${cs.size} çift · ort. korelasyon %.2f · ≥0,8 olan %%%.0f".format(cs.average(), cs.count { it >= 0.8 } * 100.0 / cs.size)
    }
    fun sectors(r: V3Result): String = r.tradeList.flatMap { tr -> plates[tr.symbol].orEmpty().filter { it in com.spotbot.core.scanner.WideScanner.SECTOR_LABELS }.ifEmpty { listOf("Diğer") } }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(8).joinToString { "${it.first} ${it.second}" }

    for (x in universes) {
        println("\n════ ${x.name} ════")
        for ((pn, p) in listOf("doğrulama" to valid, "son test" to hold, "tüm dönem" to full)) {
            for (c in costs) println(line("$pn · ${c.name}", byKey.getValue(Triple(x.name, pn, c.name))))
            val r = byKey.getValue(Triple(x.name, pn, CostScenario.TIERED_MEDIUM.name))
            println("    BTC al-tut %%%+.1f · ${concentration(r)}".format(r.btcBuyHoldPercent))
            println("    ${correlation(r)}")
            println("    sektör (işlem): ${sectors(r)}")
        }
    }

    println("\n════ İki aşamalı tarama doğrulaması (Tümü, tüm dönem, kademeli orta) ════")
    val allCfg = cfg(full, CostScenario.TIERED_MEDIUM, universes.last())
    val stage2 = HashSet<String>()
    var day = (full.first / com.spotbot.core.research.DAY_MS) * com.spotbot.core.research.DAY_MS
    while (day <= full.second) { s1.ranking(universe, day, allCfg).take(20).forEach { stage2 += it.first }; day += com.spotbot.core.research.DAY_MS }
    val fullRun = byKey.getValue(Triple("Tümü", "tüm dönem", CostScenario.TIERED_MEDIUM.name))
    val sub = Universe(universe.symbols.filterKeys { it in stage2 || it == "BTCUSDT" })
    val subRun = V3Engine(sub, allCfg).run(s1)
    val same = fullRun.tradeList == subRun.tradeList
    println("Günlük ilk 20'ye en az bir kez giren parite: ${stage2.size} / ${universe.symbols.size}")
    println("Tam evren ${fullRun.trades} işlem net %%%+.2f · alt küme ${subRun.trades} işlem net %%%+.2f · işlemler birebir aynı: ${if (same) "EVET" else "HAYIR"}".format(fullRun.netReturnPercent, subRun.netReturnPercent))

    println("\n════ SONUÇ (ön kayıtlı karar kuralı: kademeli orta maliyette doğrulama ve son test ort R > 0, PF ≥ 1,1, ≥ 30 işlem; son test en iyi parite olmadan ort R > 0) ════")
    for (x in universes) {
        val v = byKey.getValue(Triple(x.name, "doğrulama", CostScenario.TIERED_MEDIUM.name))
        val h = byKey.getValue(Triple(x.name, "son test", CostScenario.TIERED_MEDIUM.name))
        val best = h.tradeList.groupBy { it.symbol }.maxByOrNull { e -> e.value.sumOf { it.netPnl } }?.key
        val hWithout = h.tradeList.filter { it.symbol != best }.map { it.r }.let { if (it.isEmpty()) 0.0 else it.average() }
        val ok = passes(v) && passes(h) && hWithout > 0
        println("  ${x.name}: " + (if (ok) "GEÇTİ" else "GEÇMEDİ") + " · doğrulama %+.3fR PF %.2f (%d) · son test %+.3fR PF %.2f (%d) · son test en iyi parite (%s) olmadan %+.3fR".format(
            v.averageR, v.profitFactor, v.trades, h.averageR, h.profitFactor, h.trades, best, hWithout))
    }
    pool.shutdown()
}
