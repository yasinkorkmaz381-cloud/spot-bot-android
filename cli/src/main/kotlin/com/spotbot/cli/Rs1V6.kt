package com.spotbot.cli

import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.DailySeries
import com.spotbot.core.rs1.ExitReason
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Costs
import com.spotbot.core.rs1.Rs1Engine
import com.spotbot.core.rs1.Rs1Evidence
import com.spotbot.core.rs1.Rs1Result
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/** Ham veri kontrolü sayıları (yinelenen, sırasız, gün başına hizalı olmayan mumlar). */
internal data class DataQuality(var files: Int = 0, var candles: Int = 0, var duplicates: Int = 0, var unsorted: Int = 0, var misaligned: Int = 0)

internal fun loadDailyChecked(dir: File, q: DataQuality): Map<String, List<Candle>> {
    val raw = loadDaily(dir)
    for ((_, l) in raw) {
        q.files++; q.candles += l.size
        q.duplicates += l.size - l.distinctBy { it.openTime }.size
        q.unsorted += l.zipWithNext().count { (a, b) -> b.openTime < a.openTime }
        q.misaligned += l.count { it.openTime % DAY_MS != 0L }
    }
    return raw
}

/** 4H klasörü: {"symbol":..., "h4":[Candle,…]}. */
internal fun loadIntraday(dir: File?): Map<String, List<Candle>> {
    if (dir == null || !dir.exists()) return emptyMap()
    val j = Json { ignoreUnknownKeys = true }
    return dir.listFiles { f -> f.name.endsWith("USDT.json") }!!.associate { f ->
        val o = j.parseToJsonElement(f.readText()) as JsonObject
        f.name.removeSuffix(".json") to (o["h4"] as JsonArray).map { j.decodeFromJsonElement(Candle.serializer(), it) }
    }
}

/** MEXC exchangeInfo anlık görüntüsünden parite bazında taker ücreti. */
internal fun loadFees(file: File?): Map<String, Double> {
    if (file == null || !file.exists()) return emptyMap()
    val root = Json.parseToJsonElement(file.readText()) as JsonObject
    return (root["symbols"] as JsonArray).mapNotNull { e ->
        val o = e as JsonObject
        val s = (o["symbol"] as JsonPrimitive).content
        (o["takerCommission"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.let { s to it }
    }.toMap()
}

private fun row(label: String, r: Rs1Result) =
    "  %-34s %4d işlem · kazanma %%%5.1f · PF %5.2f · ort %+.3fR (t=%+.2f) · beklenen %+6.2f · net %%%+7.2f (gerç. %+7.1f / açık %+6.1f) · düşüş %%%5.2f · ücret %5.1f · spread+kayma %5.1f · süre %4.1f g · piyasada %%%3.0f%s".format(
        label, r.trades.size, r.winRatePercent, r.profitFactor, r.averageR, r.tStat, r.expectancy, r.netReturnPercent, r.realizedPnl, r.unrealizedPnl,
        r.maxDrawdownPercent, r.fees, r.friction, r.averageHoldingDays, r.exposurePercent, if (r.trades.size < 30) " ⚠<30" else "",
    )

internal class V6Context(
    val raw: Map<String, List<Candle>>,
    val intraday: Map<String, List<Candle>>,
    val fees: Map<String, Double>,
) {
    private val pool = Executors.newFixedThreadPool(minOf(4, Runtime.getRuntime().availableProcessors()))
    // Seriler yalnız gösterge parametrelerine bağlıdır; önbellek anahtarı onlardır (bellek tasarrufu, sonuç değişmez).
    private val cache = HashMap<Triple<Int, Int, Int>, Map<String, DailySeries>>()
    private fun series(cfg: Rs1Config) = synchronized(cache) {
        cache.getOrPut(Triple(cfg.emaTrend, cfg.emaExit, cfg.atrPeriod)) { raw.mapValues { (s, l) -> DailySeries(s, l, cfg) } }
    }
    var runs = 0

    fun run(cfg: Rs1Config, periods: List<Pair<Long, Long>>, useFees: Boolean = true, useIntraday: Boolean = true, closeAtEnd: Boolean = false): List<Rs1Result> {
        runs += periods.size
        val s = series(cfg)
        val engine = Rs1Engine(s, cfg, if (useFees) fees else emptyMap(), if (useIntraday) intraday else emptyMap())
        return periods.map { p -> pool.submit<Rs1Result> { engine.run(p.first, p.second, closeAtEnd = closeAtEnd) } }.map { it.get() }
    }

    fun shutdown() = pool.shutdown()
}

/** Ön kayıtlı V6 protokolü (docs/RS1-V6-ONKAYIT.md). Bağımsız test dönemi burada çalıştırılmaz. */
internal fun v6(options: Map<String, String>) {
    val q = DataQuality()
    val raw = loadDailyChecked(File(options["data"] ?: error("--data 1D klasörü gerekli")), q)
    val ctx = V6Context(raw, loadIntraday(options["h4"]?.let { File(it) }), loadFees(options["info"]?.let { File(it) }))
    val dataEnd = raw.getValue("BTCUSDT").maxOf { it.openTime } + DAY_MS
    val research = date("2020-08-01") to date("2023-01-01")
    val validation = date("2023-01-01") to date("2025-01-01")
    val seen = date("2025-01-01") to dataEnd
    val sel = listOf(research, validation)
    val out = StringBuilder()
    fun say(s: String = "") { println(s); out.appendLine(s) }

    say("V6 protokolü · 1D ${raw.size} parite · 4H ${ctx.intraday.size} parite · ücret tablosu ${ctx.fees.size} parite · veri sonu ${day(dataEnd - 1)}")
    say("Veri kontrolü: ${q.candles} mum · yinelenen ${q.duplicates} · sırasız ${q.unsorted} · gün başına hizalı olmayan ${q.misaligned} (hepsi DailySeries'te ayıklanır)")
    val feeDist = ctx.fees.filterKeys { it in raw }.values.groupingBy { it }.eachCount().toSortedMap()
    say("Ücret dağılımı (evrendeki pariteler, taker): " + feeDist.entries.joinToString { "%%%.2f: %d".format(it.key * 100, it.value) })
    say("Dönemler: araştırma ${day(research.first)}→${day(research.second - 1)} · doğrulama ${day(validation.first)}→${day(validation.second - 1)} · görülmüş ${day(seen.first)}→${day(seen.second - 1)} (seçimde kullanılmaz)")

    // ---- B0: V5'in birebir yeniden üretimi.
    // V6 protokolü V5 kural setinden başlar (açık sabit; varsayılanlar sonradan değişti).
    val v5 = com.spotbot.core.rs1.Rs1Versions.V5
    val b0 = ctx.run(v5.copy(minHistoryDays = 150), listOf(date("2020-08-01") to date("2023-01-01"), validation, seen, date("2020-08-01") to dataEnd), useFees = false, useIntraday = false, closeAtEnd = true)
    say("\n════ B0 · V5'in yeniden üretimi (düz %0,05 ücret, günlük stop, 150 gün, dönem sonunda kapanış) ════")
    listOf("eğitim/araştırma", "doğrulama", "görülmüş (V5 son test)", "tüm dönem").zip(b0).forEach { (n, r) -> say(row(n, r)) }
    say("  V5 raporu: son test 45 işlem 967,39 USDT; tüm dönem 147 işlem 1.106,37 USDT → yeniden üretim: son test ${b0[2].trades.size} işlem %.2f; tüm dönem ${b0[3].trades.size} işlem %.2f".format(b0[2].endEquity, b0[3].endEquity))

    // ---- B1: düzeltilmiş taban.
    val b1Cfg = v5.copy(minHistoryDays = 250)
    fun show(title: String, cfg: Rs1Config): List<Rs1Result> {
        val r = ctx.run(cfg, sel)
        say(row("$title · araştırma", r[0])); say(row("$title · doğrulama", r[1]))
        return r
    }
    fun notWorse(v: List<Rs1Result>, b: List<Rs1Result>, ddSlack: Double? = null) = v.indices.all { i ->
        v[i].averageR >= b[i].averageR && v[i].profitFactor >= b[i].profitFactor && (ddSlack == null || v[i].maxDrawdownPercent <= b[i].maxDrawdownPercent + ddSlack)
    }
    say("\n════ B1 · düzeltilmiş taban (parite ücreti, 4H gün içi stop, 250 gün, açık pozisyon değerlemesi) ════")
    val b1 = show("B1", b1Cfg)

    // ---- Adım 1: giriş sıklığı.
    say("\n════ Adım 1 · giriş sıklığı (A haftalık / B günlük) ════")
    val bDaily = show("B günlük", b1Cfg.copy(entryEveryDay = true))
    val step1 = if (notWorse(bDaily, b1)) b1Cfg.copy(entryEveryDay = true) else b1Cfg
    val step1R = if (step1.entryEveryDay) bDaily else b1
    say("  Karar: ${if (step1.entryEveryDay) "B (günlük) seçildi" else "A (haftalık) kaldı"} — B'nin ortalama R ve PF'si iki dönemde de A'dan düşük olmamalıydı.")

    // ---- Adım 2: çıkış bileşenleri.
    say("\n════ Adım 2 · çıkış bileşenleri (koruyucu stop her zaman var) ════")
    val exits = listOf<Pair<String, (Rs1Config) -> Rs1Config>>(
        "iz süren stop yok" to { it.copy(exitTrailing = false) },
        "EMA50 çıkışı yok" to { it.copy(exitEma = false) },
        "uygunluk kaybı çıkışı yok" to { it.copy(exitIneligible = false) },
        "BTC rejimi çıkışı yok" to { it.copy(exitBtc = false) },
    )
    val exitRemovals = exits.filter { (name, f) -> notWorse(show(name, f(step1)), step1R).also { say("    → ${if (it) "kaldırılabilir" else "korunur"}") } }
    var step2 = step1
    var step2R = step1R
    if (exitRemovals.isNotEmpty()) {
        val combined = exitRemovals.fold(step1) { c, (_, f) -> f(c) }
        val cr = show("birleşik (${exitRemovals.joinToString { it.first }})", combined)
        if (notWorse(cr, step1R)) { step2 = combined; step2R = cr; say("  Karar: kaldırıldı: ${exitRemovals.joinToString { it.first }}") }
        else say("  Karar: birleşik sonuç kötüleşti → hiçbir çıkış kaldırılmadı")
    } else say("  Karar: bütün çıkış bileşenleri korunur")

    // ---- Adım 3: giriş filtreleri.
    say("\n════ Adım 3 · giriş filtreleri ════")
    val filters = listOf<Pair<String, (Rs1Config) -> Rs1Config>>(
        "BTC giriş filtresi yok" to { it.copy(useBtcFilter = false) },
        "EMA100 şartı yok" to { it.copy(useTrendFilter = false) },
        "7g getiri şartı yok" to { it.copy(useReturn7 = false) },
        "30g getiri şartı yok" to { it.copy(useReturn30 = false) },
        "BTC'ye göre ≥3 puan yok" to { it.copy(useRelativeStrength = false) },
        "aralık ≤2,5 ATR yok" to { it.copy(useRangeFilter = false) },
    )
    val filterRemovals = filters.filter { (name, f) -> notWorse(show(name, f(step2)), step2R, ddSlack = 2.0).also { say("    → ${if (it) "kaldırılabilir" else "korunur"}") } }
    var final = step2
    var finalR = step2R
    if (filterRemovals.isNotEmpty()) {
        val combined = filterRemovals.fold(step2) { c, (_, f) -> f(c) }
        val cr = show("birleşik (${filterRemovals.joinToString { it.first }})", combined)
        if (notWorse(cr, step2R, ddSlack = 2.0)) { final = combined; finalR = cr; say("  Karar: kaldırıldı: ${filterRemovals.joinToString { it.first }}") }
        else say("  Karar: birleşik sonuç kötüleşti → hiçbir filtre kaldırılmadı")
    } else say("  Karar: bütün filtreler korunur")

    // ---- Adım 4: duyarlılık (seçim değil).
    say("\n════ Adım 4 · duyarlılık (nihai yapılandırma; yalnız rapor) ════")
    val sens = buildList<Pair<String, Rs1Config>> {
        if (final.useTrendFilter) { add("EMA 80" to final.copy(emaTrend = 80)); add("EMA 120" to final.copy(emaTrend = 120)) }
        if (final.useRelativeStrength) { add("RS 2 puan" to final.copy(minRelativeStrengthPoints = 2.0)); add("RS 4 puan" to final.copy(minRelativeStrengthPoints = 4.0)) }
        add("stop 2 ATR" to final.copy(initialStopAtr = 2.0)); add("stop 3 ATR" to final.copy(initialStopAtr = 3.0))
        if (final.exitTrailing) { add("iz 2,5 ATR" to final.copy(trailAtr = 2.5)); add("iz 3,5 ATR" to final.copy(trailAtr = 3.5)) }
        add("hacim 3M" to final.copy(minMedianVolume = 3e6)); add("hacim 8M" to final.copy(minMedianVolume = 8e6))
    }
    var fragile = 0
    for ((name, c) in sens) {
        val r = show(name, c)
        val bad = r.indices.any { i -> (r[i].averageR > 0) != (finalR[i].averageR > 0) || r[i].profitFactor < finalR[i].profitFactor / 2 }
        if (bad) { fragile++; say("    → KIRILGAN (işaret değişti ya da PF yarıdan fazla düştü)") }
    }

    // ---- Nihai yapılandırma ve raporlar.
    say("\n════ NİHAİ YAPILANDIRMA (dondurulacak) ════")
    say("  giriş: ${if (final.entryEveryDay) "her gün" else "haftalık (pazartesi)"} · çıkışlar: iz süren ${final.exitTrailing}, EMA50 ${final.exitEma}, uygunluk kaybı ${final.exitIneligible}, BTC ${final.exitBtc}")
    say("  filtreler: BTC ${final.useBtcFilter}, EMA100 ${final.useTrendFilter}, 7g ${final.useReturn7}, 30g ${final.useReturn30}, RS ${final.useRelativeStrength}, aralık ${final.useRangeFilter}, hacim ${final.useVolumeFilter} · geçmiş ${final.minHistoryDays} gün")
    say("  duyarlılıkta kırılgan: $fragile / ${sens.size}")
    val all = ctx.run(final, listOf(research, validation, seen))
    val dbl = ctx.run(final.copy(costs = final.costs.doubled()), listOf(research, validation, seen))
    val b1seen = ctx.run(b1Cfg, listOf(seen))[0]
    say("\n════ Nihai yapılandırmanın sonuçları ════")
    listOf("araştırma", "doğrulama", "görülmüş (bağımsız DEĞİL)").forEachIndexed { i, n ->
        say(row(n, all[i])); say(row("$n · ×2 maliyet", dbl[i]))
        val e = Rs1Evidence.classify(all[i], dbl[i])
        say("    sınıf: ${e.classification.title} · ort R %%90 GA [%+.3f, %+.3f] · en iyi coin olmadan beklenen %+.2f · en iyi 3 işlem olmadan net %+.1f".format(e.ciLow, e.ciHigh, e.expectancyWithoutBestCoin, e.netWithoutTop3))
        say("    BTC al-tut %%%+.1f · USDT %%0".format(all[i].btcBuyHoldPercent))
    }
    say(row("B0 (V5) · görülmüş", b0[2])); say(row("B1 · görülmüş", b1seen))

    say("\n════ Uygunluk kaybı çıkışlarından sonra 30 gün (gereksiz satış mı?) ════")
    for ((name, rs) in listOf("B1" to b1, "nihai" to all.take(2))) {
        val after = rs.flatMap { it.trades }.filter { it.reason == ExitReason.WEEKLY }.mapNotNull { t ->
            val l = raw[t.symbol] ?: return@mapNotNull null
            val i = l.indexOfFirst { it.openTime == t.exitTime }
            if (i < 0 || i + 30 >= l.size) null else l[i + 30].close / l[i].open - 1
        }
        if (after.isEmpty()) say("  $name: uygunluk kaybı çıkışı yok") else
            say("  $name: ${after.size} çıkış · sonraki 30 gün ortalama %%%+.1f · medyan %%%+.1f · yükselen %%%.0f".format(after.average() * 100, after.sorted()[after.size / 2] * 100, after.count { it > 0 } * 100.0 / after.size))
    }

    say("\n════ Coin ve yıl bazında (nihai, araştırma + doğrulama + görülmüş) ════")
    val trades = all.flatMap { it.trades }
    trades.groupBy { it.symbol }.mapValues { e -> e.value.size to e.value.sumOf { it.netPnl } }.toList().sortedByDescending { it.second.second }.let { l ->
        say("  en iyi 8: " + l.take(8).joinToString { "%s %d/%+.1f".format(it.first, it.second.first, it.second.second) })
        say("  en kötü 8: " + l.takeLast(8).joinToString { "%s %d/%+.1f".format(it.first, it.second.first, it.second.second) })
    }
    trades.groupBy { day(it.entryTime).take(4) }.toSortedMap().forEach { (y, l) -> say("  $y: ${l.size} işlem · net %+.1f USDT · ort %+.3fR".format(l.sumOf { it.netPnl }, l.map { it.r }.average())) }
    say("  çıkış nedenleri: " + trades.groupBy { it.reason.title }.mapValues { e -> "%d (%+.2fR)".format(e.value.size, e.value.map { it.r }.average()) }.entries.joinToString { "${it.key} ${it.value}" })
    say("  gün içi (4H) çözülen stop: ${trades.count { it.intradayResolved }} · günlük mumla çözülen: ${all.sumOf { it.dailyResolvedStops }}")
    say("\nToplam çalıştırma (dönem × yapılandırma): ${ctx.runs}")

    options["csv"]?.let { f ->
        File(f).writeText("donem,coin,giris,cikis,giris_fiyat,cikis_fiyat,miktar,ilk_stop,net_usdt,r,ucret,spread_kayma,neden,stop_degisim,rejim\n" + listOf("araştırma", "doğrulama", "görülmüş").zip(all).flatMap { (n, r) ->
            r.trades.map { t -> listOf(n, t.symbol, day(t.entryTime), day(t.exitTime), t.entryPrice, t.exitPrice, t.quantity, t.initialStop, "%.4f".format(t.netPnl), "%.4f".format(t.r), "%.4f".format(t.fees), "%.4f".format(t.friction), t.reason.title, t.stopChanges.size, t.regime).joinToString(",") }
        }.joinToString("\n"))
    }
    options["out"]?.let { File(it).writeText(out.toString()) }
    ctx.shutdown()
}

/** Dondurulmuş yapılandırmayla (Rs1Config varsayılanları) bağımsız test: bir kez. */
internal fun v6Final(options: Map<String, String>) {
    val q = DataQuality()
    val raw0 = loadDailyChecked(File(options["data"] ?: error("--data gerekli")), q)
    val h4 = loadIntraday(options["h4"]?.let { File(it) })
    // UTC'ye hizasız eski günlük mumlar atılır ve sayılır; eksik günler 4H'den kurulur (Rs1History.normalizeDaily).
    var mis = 0; var reb = 0
    val raw = (raw0.keys + h4.keys).associateWith { s ->
        val (l, n) = com.spotbot.core.rs1.Rs1History.normalizeDaily(raw0[s].orEmpty(), h4[s].orEmpty())
        mis += n.misaligned; reb += n.rebuiltFrom4h
        l
    }.filterValues { it.isNotEmpty() }
    val ctx = V6Context(raw, h4, loadFees(options["info"]?.let { File(it) }))
    val period = date(options["from"] ?: "2018-01-01") to date(options["to"] ?: "2020-01-01")
    val cfg = com.spotbot.core.rs1.Rs1Versions.V6
    val out = StringBuilder()
    fun say(s: String = "") { println(s); out.appendLine(s) }
    say("BAĞIMSIZ TEST · dondurulmuş Rs1Config · ${day(period.first)} → ${day(period.second - 1)} · 1D ${raw.size} parite · 4H ${ctx.intraday.size} · veri: ${q.candles} mum, yinelenen ${q.duplicates}, sırasız ${q.unsorted}")
    say("UTC'ye hizasız günlük mum (atıldı): $mis · 4H'den kurulan UTC günü: $reb · BTC ilk UTC günü ${raw["BTCUSDT"]?.firstOrNull()?.let { day(it.openTime) }}")
    val r = ctx.run(cfg, listOf(period))[0]
    val d = ctx.run(cfg.copy(costs = cfg.costs.doubled()), listOf(period))[0]
    val p = ctx.run(cfg.copy(costs = Rs1Costs.PESSIMISTIC), listOf(period))[0]
    val b0 = ctx.run(cfg.copy(minHistoryDays = 150, entryEveryDay = false, exitTrailing = true, exitEma = true, exitIneligible = true, exitBtc = true, useBtcFilter = true, useTrendFilter = true, useReturn7 = true, useReturn30 = true, useRelativeStrength = true, useRangeFilter = true), listOf(period), useFees = false, useIntraday = false)[0]
    say(row("orta maliyet", r)); say(row("×2 maliyet", d)); say(row("kötümser", p)); say(row("V5 kuralları (B0)", b0))
    say("  BTC al-tut %%%+.2f · USDT %%0".format(r.btcBuyHoldPercent))
    val e = Rs1Evidence.classify(r, d)
    say("\nSINIF: ${e.classification.title}")
    e.checks.forEach { (k, ok) -> say("  ${if (ok) "✓" else "✗"} $k") }
    say("  beklenen %+.2f USDT · PF %.2f · düşüş %%%.2f · ort R %%90 GA [%+.3f, %+.3f] · en iyi coin olmadan %+.2f · en iyi 3 işlem olmadan %+.1f".format(e.expectancy, e.profitFactor, e.maxDrawdownPercent, e.ciLow, e.ciHigh, e.expectancyWithoutBestCoin, e.netWithoutTop3))
    say("  coinler: " + r.trades.groupBy { it.symbol }.mapValues { (_, l) -> "%d/%+.1f".format(l.size, l.sumOf { it.netPnl }) }.entries.joinToString { "${it.key} ${it.value}" })
    say("  çıkış nedenleri: " + r.trades.groupingBy { it.reason.title }.eachCount())
    say("  girişsiz değerlendirme nedenleri: " + r.weekly.mapNotNull { it.noEntryReason }.groupingBy { it }.eachCount())
    r.trades.forEach { t -> say("  %-10s %s → %s %10.4f → %10.4f net %+6.2f %+.2fR %s".format(t.symbol, day(t.entryTime), day(t.exitTime), t.entryPrice, t.exitPrice, t.netPnl, t.r, t.reason.title)) }
    r.open.forEach { o -> say("  AÇIK %-10s %s %10.4f → %10.4f gerçekleşmemiş %+6.2f".format(o.symbol, day(o.entryTime), o.entryPrice, o.lastPrice, o.unrealizedPnl)) }
    options["out"]?.let { File(it).writeText(out.toString()) }
    ctx.shutdown()
}
