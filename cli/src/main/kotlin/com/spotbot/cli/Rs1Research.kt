package com.spotbot.cli

import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.DailySeries
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Costs
import com.spotbot.core.rs1.Rs1Engine
import com.spotbot.core.rs1.Rs1Result
import com.spotbot.core.rs1.Rs1Rules
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.Executors

private fun line(label: String, r: Rs1Result) =
    "  %-24s %4d işlem · kazanma %%%5.1f · PF %5.2f · ort %+.3fR (t=%+.2f) · beklenen %+6.2f USDT/işlem · net %%%+7.2f (%8.2f→%8.2f) · düşüş %%%5.2f · ücret %5.1f · spread+kayma %5.1f · süre %5.1f gün · piyasada %%%3.0f%s".format(
        label, r.trades.size, r.winRatePercent, r.profitFactor, r.averageR, r.tStat, r.expectancy, r.netReturnPercent, r.startCapital, r.endEquity,
        r.maxDrawdownPercent, r.fees, r.friction, r.averageHoldingDays, r.exposurePercent, if (r.trades.size < 30) "  ⚠ <30 işlem" else "",
    )

/** Tek dönem backtest. */
internal fun backtest(options: Map<String, String>) {
    val cfg = Rs1Config(costs = costOf(options["cost"]))
    val raw = loadDaily(File(options["data"] ?: error("--data klasörü gerekli")))
    val series = raw.mapValues { (s, c) -> DailySeries(s, c, cfg) }
    val dataEnd = series.getValue("BTCUSDT").let { it.openTime[it.n - 1] + DAY_MS }
    val from = options["from"]?.let { date(it) } ?: date("2025-01-01")
    val to = options["to"]?.let { date(it) } ?: dataEnd
    val r = Rs1Engine(series, cfg).run(from, to)
    println("Momentum RS-1 · ${day(from)} → ${day(to - 1)} · ${cfg.costs.name} maliyet · ${series.size} parite")
    println(line("sonuç", r))
    println("  BTC al-tut %%%+.2f · USDT'de bekleme %%0".format(r.btcBuyHoldPercent))
    r.trades.forEach { t ->
        println("  %-12s %s → %s  %10.4f → %10.4f  net %+7.2f  %+.2fR  %s".format(t.symbol, day(t.entryTime), day(t.exitTime), t.entryPrice, t.exitPrice, t.netPnl, t.r, t.reason.title))
    }
    options["out"]?.let { File(it).writeText(json.encodeToString(Rs1Result.serializer(), r)) }
}

/** Ön kayıtlı protokol (docs/RS1-ONKAYIT.md): dönemler × maliyet senaryoları, rejimler, yoğunlaşma, karar. */
internal fun research(options: Map<String, String>) {
    val base = Rs1Config()
    val raw = loadDaily(File(options["data"] ?: error("--data klasörü gerekli")))
    val dataEnd = raw.getValue("BTCUSDT").maxOf { it.openTime } + DAY_MS
    val periods = listOf(
        "eğitim" to (date("2020-08-01") to date("2023-01-01")),
        "doğrulama" to (date("2023-01-01") to date("2025-01-01")),
        "son test" to (date("2025-01-01") to dataEnd),
        "tüm dönem" to (date("2020-08-01") to dataEnd),
    )
    val engines = Rs1Costs.all.associate { c ->
        val cfg = base.copy(costs = c)
        c.name to Rs1Engine(raw.mapValues { (s, l) -> DailySeries(s, l, cfg) }, cfg)
    }
    val series = raw.mapValues { (s, l) -> DailySeries(s, l, base) }
    println("Veri: ${raw.size} parite (1D) · ${day(raw.values.minOf { l -> l.first().openTime })} → ${day(dataEnd - 1)}")
    println("Bu evren bugün MEXC'de bulunan paritelerdir; borsadan kaldırılanlar indirilemez (hayatta kalma yanlılığı). Spread ve derinlik şartları (5, 6) geçmişte ölçülemediği için backtest'te uygulanmaz; spread maliyet senaryolarıyla modellenir.")
    println("Zamana bağlı likidite (≥150 gün güvenilir geçmiş, 30 gün medyan hacim ≥ 5M USDT) ve BTC filtresi:")
    for (d in listOf("2021-01-04", "2022-01-03", "2023-01-02", "2024-01-01", "2025-01-06", "2026-01-05", day(dataEnd - DAY_MS))) {
        val t = date(d)
        val liquid = series.values.count { s -> val i = s.lastCompleted(t); i >= 0 && s.openTime[i] + DAY_MS == t && s.reliable(i, base) && s.medianVolume(i, base.volumeDays) >= base.minMedianVolume }
        val btc = Rs1Rules.btc(series["BTCUSDT"], t, base)
        println("  $d: likit ${"%3d".format(liquid)} · BTC filtresi ${if (btc.open) "açık" else "kapalı"}")
    }

    val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
    val jobs = periods.flatMap { p -> Rs1Costs.all.map { c -> p to c } }
    val results = jobs.map { (p, c) -> pool.submit<Rs1Result> { engines.getValue(c.name).run(p.second.first, p.second.second) } }.map { it.get() }
    pool.shutdown()
    val byKey = jobs.zip(results).associate { (k, r) -> (k.first.first to k.second.name) to r }

    for ((name, p) in periods) {
        println("\n════ $name · ${day(p.first)} → ${day(p.second - 1)} ════")
        Rs1Costs.all.forEach { c -> println(line(c.name, byKey.getValue(name to c.name))) }
        val r = byKey.getValue(name to Rs1Costs.MEDIUM.name)
        val btcOpenDays = (p.first until p.second step DAY_MS).count { Rs1Rules.btc(series["BTCUSDT"], it, base).open }
        val total = ((p.second - p.first) / DAY_MS).toInt()
        println("  BTC al-tut %%%+.2f · USDT'de bekleme %%0 · BTC filtresi açık günler %d/%d (%%%.0f)".format(r.btcBuyHoldPercent, btcOpenDays, total, btcOpenDays * 100.0 / total))
        val bySymbol = r.trades.groupBy { it.symbol }.mapValues { e -> e.value.sumOf { it.netPnl } }.toList().sortedByDescending { it.second }
        val best = bySymbol.firstOrNull()
        val withoutBest = r.trades.filter { it.symbol != best?.first }
        println("  parite ${bySymbol.size} · en iyi ${best?.let { "%s %+.1f".format(it.first, it.second) }} · onsuz %+.1f USDT, ort %+.3fR · en kötü ${bySymbol.lastOrNull()?.let { "%s %+.1f".format(it.first, it.second) }}".format(
            r.trades.sumOf { it.netPnl } - (best?.second ?: 0.0), if (withoutBest.isEmpty()) 0.0 else withoutBest.map { it.r }.average()))
        println("  rejim (giriş günü BTC): " + r.byRegime().joinToString(" · ") { "%s %d işlem %+.3fR %+.1f USDT%s".format(it.regime, it.trades, it.averageR, it.netPnl, if (it.trades < 30) " ⚠" else "") })
        println("  çıkış nedenleri: " + r.trades.groupingBy { it.reason.title }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("  haftalık değerlendirme ${r.weekly.size} · girişli ${r.weekly.count { it.entered.isNotEmpty() }} · girişsiz nedenler: " +
            r.weekly.mapNotNull { it.noEntryReason }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("  en kârlı 5 parite: " + bySymbol.take(5).joinToString { "%s %+.1f".format(it.first, it.second) })
    }

    val h = byKey.getValue("son test" to Rs1Costs.MEDIUM.name)
    val best = h.trades.groupBy { it.symbol }.maxByOrNull { e -> e.value.sumOf { it.netPnl } }?.key
    val hWithout = h.trades.filter { it.symbol != best }.map { it.r }.let { if (it.isEmpty()) 0.0 else it.average() }
    val enough = h.trades.size >= 30
    val pass = enough && h.averageR > 0 && h.profitFactor >= 1.1 && hWithout > 0
    println("\n════ SONUÇ (ön kayıt: son test, orta maliyet; ort R > 0, PF ≥ 1,1, ≥ 30 işlem, en iyi parite olmadan ort R > 0) ════")
    println("  " + when {
        !enough -> "İSTATİSTİK İÇİN YETERSİZ (${h.trades.size} işlem < 30)"
        pass -> "BAŞARILI"
        else -> "BAŞARISIZ"
    } + " · ort %+.3fR · PF %.2f · net %%%+.2f · en iyi parite (%s) olmadan %+.3fR · BTC al-tut %%%+.2f".format(h.averageR, h.profitFactor, h.netReturnPercent, best, hWithout, h.btcBuyHoldPercent))
    options["out"]?.let { f ->
        File(f).writeText(json.encodeToString(MapSerializer(String.serializer(), Rs1Result.serializer()), byKey.mapKeys { "${it.key.first} · ${it.key.second}" }))
    }
}
