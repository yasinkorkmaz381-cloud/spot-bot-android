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
