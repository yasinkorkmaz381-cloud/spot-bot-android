package com.spotbot.cli

import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Evidence
import com.spotbot.core.rs1.Rs1History
import com.spotbot.core.rs1.Rs1Result
import com.spotbot.core.rs1.Rs1Trade
import com.spotbot.core.rs1.Rs1Versions
import java.io.File

/** Bir çalıştırmanın ön kayıtlı ölçüleri (docs/RS1-V7-ONKAYIT.md). */
internal data class V7Metrics(
    val trades: Int,
    val netPercent: Double,
    val realized: Double,
    val unrealized: Double,
    val maxDd: Double,
    val pf: Double,
    val expectancy: Double,
    val avgR: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val net2xPercent: Double,
    val expNoBestCoin: Double,
    val netNoTop3: Double,
    val exposure: Double,
    val capitalUse: Double,
    val costs: Double,
) {
    companion object {
        fun of(r: Rs1Result, d: Rs1Result): V7Metrics {
            val e = Rs1Evidence.classify(r, d)
            return V7Metrics(
                r.trades.size, r.netReturnPercent, r.realizedPnl, r.unrealizedPnl, r.maxDrawdownPercent, r.profitFactor, r.expectancy,
                r.averageR, e.ciLow, e.ciHigh, d.netReturnPercent, e.expectancyWithoutBestCoin, e.netWithoutTop3, r.exposurePercent,
                r.capitalUsePercent, r.fees + r.friction,
            )
        }
    }

    /** Ön kayıttaki 8 ölçü: hepsinde geçilmeyen ve en az birinde geçen (Pareto üstünlüğü). NaN karşılaştırılamaz → üstün değil. */
    fun dominates(o: V7Metrics): Boolean {
        val pairs = listOf(
            netPercent to o.netPercent, -maxDd to -o.maxDd, pf to o.pf, expectancy to o.expectancy,
            ciLow to o.ciLow, net2xPercent to o.net2xPercent, expNoBestCoin to o.expNoBestCoin, netNoTop3 to o.netNoTop3,
        )
        if (pairs.any { (a, b) -> a.isNaN() || b.isNaN() }) return false
        return pairs.all { (a, b) -> a >= b } && pairs.any { (a, b) -> a > b }
    }
}

private fun mrow(label: String, m: V7Metrics) =
    "  %-26s %4d işlem · net %%%+7.2f (gerç %+7.1f / açık %+6.1f) · düşüş %%%5.2f · PF %5.2f · beklenti %+6.2f · ort %+.3fR [%+.2f, %+.2f] · ×2 %%%+7.2f · en iyi coin yok %+6.2f · en iyi 3 yok %+7.1f · piyasada %%%3.0f · sermaye %%%3.0f · maliyet %5.1f%s".format(
        label, m.trades, m.netPercent, m.realized, m.unrealized, m.maxDd, m.pf, m.expectancy, m.avgR, m.ciLow, m.ciHigh, m.net2xPercent,
        m.expNoBestCoin, m.netNoTop3, m.exposure, m.capitalUse, m.costs, if (m.trades < 30) " ⚠<30" else "",
    )

/** Ön kayıtlı V7 protokolü. */
internal fun v7(options: Map<String, String>) {
    val out = StringBuilder()
    fun say(s: String = "") { println(s); out.appendLine(s) }
    val q = DataQuality()
    val h4 = loadIntraday(options["h4"]?.let { File(it) })
    val raw0 = loadDailyChecked(File(options["data"] ?: error("--data gerekli")), q)
    var mis = 0; var reb = 0
    val raw = raw0.mapValues { (s, l) -> Rs1History.normalizeDaily(l, h4[s].orEmpty()).also { mis += it.second.misaligned; reb += it.second.rebuiltFrom4h }.first }.filterValues { it.isNotEmpty() }
    val fees = loadFees(options["info"]?.let { File(it) })
    val ctx = V6Context(raw, h4, fees)
    val dataEnd = raw.getValue("BTCUSDT").maxOf { it.openTime } + DAY_MS
    val configs = linkedMapOf(
        "W0" to Rs1Versions.V6,
        "W1" to Rs1Versions.V6.copy(exitTrailing = true),
        "D0" to Rs1Versions.V6.copy(entryEveryDay = true),
        "D1" to Rs1Versions.V6.copy(entryEveryDay = true, exitTrailing = true),
    )
    val names = mapOf("W0" to "W0 haftalık · V6 çıkış (mevcut)", "W1" to "W1 haftalık · + iz süren", "D0" to "D0 günlük · V6 çıkış", "D1" to "D1 günlük · + iz süren")
    val t0 = date("2020-08-01")
    val evalStarts = listOf("2022-08-01", "2023-02-01", "2023-08-01", "2024-02-01", "2024-08-01", "2025-02-01", "2025-08-01", "2026-02-01", "2026-08-01").map { date(it) }
    val windows = evalStarts.mapIndexed { i, s -> Triple(i + 1, s, evalStarts.getOrNull(i + 1) ?: dataEnd) }

    say("V7 protokolü · 1D ${raw.size} parite (${q.candles} mum; yinelenen ${q.duplicates}, sırasız ${q.unsorted}; UTC'ye hizasız ${mis}, 4H'den kurulan $reb) · 4H ${h4.size} parite · ücret tablosu ${fees.size} · veri sonu ${day(dataEnd - 1)}")
    say("Ortak kurallar: Rs1Versions.V6 (yalnız giriş sıklığı ve iz süren stop değişir; iz süren = en yüksek kapanış − ${Rs1Versions.V6.trailAtr} ATR). Orta maliyet + parite ücreti; ×2 maliyet. Her çalıştırma 1.000 USDT, pozisyon devri yok, açık pozisyon dönem sonunda gerçekleşmemiş.")
    say("UYARI: 2020–2026 daha önce incelendi; bütün sonuçlar tarihsel sağlamlık incelemesidir, bağımsız test değildir.")

    fun runPair(cfg: Rs1Config, a: Long, b: Long): Triple<Rs1Result, Rs1Result, V7Metrics> {
        val r = ctx.run(cfg, listOf(a to b))[0]
        val d = ctx.run(cfg.copy(costs = cfg.costs.doubled()), listOf(a to b))[0]
        return Triple(r, d, V7Metrics.of(r, d))
    }

    // ---- Pencereler.
    val oos = configs.keys.associateWith { ArrayList<Pair<Int, Rs1Result>>() }
    val oos2x = configs.keys.associateWith { ArrayList<Rs1Result>() }
    val selectedPath = ArrayList<Pair<Int, Pair<String, Rs1Result>>>()
    for ((idx, a, b) in windows) {
        say("\n════ Pencere $idx · eğitim ${day(t0)} → ${day(a - 1)} · değerlendirme ${day(a)} → ${day(b - 1)}${if (b - a < 150 * DAY_MS) " (kısa)" else ""} ════")
        val train = configs.mapValues { (_, c) -> runPair(c, t0, a).third }
        train.forEach { (k, m) -> say(mrow("eğitim $k", m)) }
        val winner = train.entries.firstOrNull { (k, m) -> m.trades >= 30 && train.filterKeys { it != k }.values.all { m.dominates(it) } }?.key
        val chosen = winner ?: "W0"
        say("  Seçim (yalnız eğitim verisi): ${winner?.let { "$it açık biçimde üstün" } ?: "kazanan yok → W0 (mevcut V6)"}")
        for ((k, c) in configs) {
            val (r, d, m) = runPair(c, a, b)
            oos.getValue(k) += idx to r
            oos2x.getValue(k) += d
            say(mrow("değerlendirme $k", m))
            if (k == chosen) selectedPath += idx to (k to r)
        }
    }

    // ---- Birleşik pencere dışı sonuçlar.
    fun pooled(label: String, rs: List<Rs1Result>, rs2x: List<Rs1Result>?, w0: List<Rs1Result>?): String {
        val trades = rs.flatMap { it.trades }
        val net = rs.sumOf { it.netPnl }
        val gp = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val gl = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        val pf = if (gl > 0) gp / gl else Double.NaN
        val exp = if (trades.isEmpty()) 0.0 else trades.sumOf { it.netPnl } / trades.size
        val (lo, hi) = Rs1Evidence.bootstrapMeanCi(trades.map { it.r })
        val best = trades.groupBy { it.symbol }.maxByOrNull { e -> e.value.sumOf { it.netPnl } }
        val noBest = trades.filter { it.symbol != best?.key }.let { l -> if (l.isEmpty()) 0.0 else l.sumOf { it.netPnl } / l.size }
        val top3 = trades.sortedByDescending { it.netPnl }.take(3).sumOf { it.netPnl }
        val worstDd = rs.maxOf { it.maxDrawdownPercent }
        val positive = rs.count { it.netPnl > 0 }
        val vsW0 = w0?.let { b -> rs.indices.count { rs[it].netPnl >= b[it].netPnl } }
        val n2x = rs2x?.sumOf { it.netPnl }
        val cls = when {
            trades.size >= 30 && exp > 0 && pf >= 1.2 && worstDd <= 10 && noBest > 0 && net - top3 > 0 && (n2x ?: 0.0) > 0 && lo > 0 -> "Kanıt var (tarihsel)"
            trades.size >= 30 && (exp <= 0 || pf < 1.0) -> "Başarısız"
            else -> "Yetersiz kanıt"
        }
        return "  %-30s %4d işlem · toplam net %+8.1f USDT (pencere başı 1.000) · ×2 maliyet %s · PF %s · beklenti %+6.2f · ort R [%+.2f, %+.2f] · en kötü pencere düşüşü %%%.2f · pozitif pencere %d/%d%s · en iyi coin %s %+.1f, onsuz beklenti %+.2f · en iyi 3 işlem olmadan %+.1f · piyasada %%%.0f · sermaye %%%.0f → %s".format(
            label, trades.size, net, n2x?.let { "%+.1f".format(it) } ?: "—", if (pf.isNaN()) "—" else "%.2f".format(pf), exp, lo, hi, worstDd, positive, rs.size,
            vsW0?.let { " · W0'dan kötü olmayan pencere $it/${rs.size}" } ?: "", best?.key, best?.value?.sumOf { it.netPnl } ?: 0.0, noBest, net - top3,
            rs.map { it.exposurePercent }.average(), rs.map { it.capitalUsePercent }.average(), cls,
        )
    }
    say("\n════ Birleşik pencere dışı (9 pencere) ════")
    val w0r = oos.getValue("W0").map { it.second }
    for (k in configs.keys) say(pooled(names.getValue(k), oos.getValue(k).map { it.second }, oos2x.getValue(k), if (k == "W0") null else w0r))
    say(pooled("seçim yolu (pencere başına seçilen)", selectedPath.map { it.second.second }, null, w0r))
    say("  seçimler: " + selectedPath.joinToString { "P${it.first}=${it.second.first}" })

    say("\n════ Pencere bazında net (USDT) ════")
    say("  pencere  " + configs.keys.joinToString("  ") { "%8s".format(it) })
    for (i in windows.indices) say("  P%-2d %s ".format(windows[i].first, day(windows[i].second)) + configs.keys.joinToString("  ") { "%+8.1f".format(oos.getValue(it)[i].second.netPnl) })

    // ---- Tüm geçmiş ve nihai karar.
    say("\n════ Tüm geçmiş ${day(t0)} → ${day(dataEnd - 1)} ════")
    val full = configs.mapValues { (_, c) -> runPair(c, t0, dataEnd) }
    full.forEach { (k, v) -> say(mrow(k, v.third)) }
    val fullWinner = full.entries.firstOrNull { (k, v) -> v.third.trades >= 30 && full.filterKeys { it != k }.values.all { v.third.dominates(it.third) } }?.key
    say("  Tüm geçmişte açık üstünlük: ${fullWinner ?: "yok"}")
    val decision = fullWinner?.takeIf { it != "W0" }?.let { x ->
        val xs = oos.getValue(x).map { it.second }
        val b1 = xs.sumOf { it.netPnl } >= w0r.sumOf { it.netPnl }
        val b2 = xs.maxOf { it.maxDrawdownPercent } <= w0r.maxOf { it.maxDrawdownPercent }
        fun pf(l: List<Rs1Trade>) = l.filter { it.netPnl > 0 }.sumOf { it.netPnl } / -l.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        val b3 = pf(xs.flatMap { it.trades }) >= pf(w0r.flatMap { it.trades })
        val c = xs.indices.count { xs[it].netPnl >= w0r[it].netPnl } >= 6
        say("  $x için (b) toplam net ≥ W0: $b1 · en kötü düşüş ≤ W0: $b2 · birleşik PF ≥ W0: $b3 · (c) ≥6/9 pencerede W0'dan kötü değil: $c")
        x.takeIf { b1 && b2 && b3 && c }
    }
    say("\nNİHAİ KARAR: " + (decision?.let { "V7 = $it (${names[it]})" } ?: "KAZANAN YOK → V7 kuralları V6 ile aynı kalır (W0)."))

    // ---- 2018–2019 (betimleyici; V6 için bir kez kullanıldı, seçimde yok).
    options["pre"]?.let { preDir ->
        val preH4 = loadIntraday(options["preh4"]?.let { File(it) })
        val pre = loadDaily(File(preDir)).mapValues { (s, l) -> Rs1History.normalizeDaily(l, preH4[s].orEmpty()).first }.filterValues { it.isNotEmpty() }
        val pctx = V6Context(pre, preH4, fees)
        say("\n════ 2018–2019 (betimleyici; seçimde kullanılmadı) ════")
        for ((k, c) in configs) {
            val r = pctx.run(c, listOf(date("2018-01-01") to date("2020-01-01")))[0]
            val d = pctx.run(c.copy(costs = c.costs.doubled()), listOf(date("2018-01-01") to date("2020-01-01")))[0]
            say(mrow(k, V7Metrics.of(r, d)))
        }
        pctx.shutdown()
    }

    // ---- Coin bazında (birleşik pencere dışı).
    say("\n════ Coin bazında (birleşik pencere dışı) ════")
    for (k in configs.keys) {
        val l = oos.getValue(k).flatMap { it.second.trades }.groupBy { it.symbol }.mapValues { (_, t) -> t.size to t.sumOf { it.netPnl } }.toList().sortedByDescending { it.second.second }
        say("  $k en iyi: " + l.take(6).joinToString { "%s %d/%+.1f".format(it.first, it.second.first, it.second.second) })
        say("  $k en kötü: " + l.takeLast(6).joinToString { "%s %d/%+.1f".format(it.first, it.second.first, it.second.second) })
    }
    say("\nToplam çalıştırma (dönem × yapılandırma × maliyet): ${ctx.runs}")

    // ---- CSV: her yapılandırmanın pencere dışı ve tüm geçmiş işlemleri.
    options["csvdir"]?.let { dir ->
        File(dir).mkdirs()
        val header = "yapilandirma,kapsam,pencere,coin,giris,cikis,giris_fiyat,cikis_fiyat,miktar,ilk_stop,son_stop,stop_degisim,net_usdt,r,ucret,spread_kayma,neden,gun_ici_cozum,rejim\n"
        fun line(k: String, scope: String, w: Int?, t: Rs1Trade) = listOf(k, scope, w ?: "", t.symbol, day(t.entryTime), day(t.exitTime), t.entryPrice, t.exitPrice, t.quantity, t.initialStop,
            t.stopChanges.lastOrNull()?.stop ?: t.initialStop, t.stopChanges.size - 1, "%.4f".format(t.netPnl), "%.4f".format(t.r), "%.4f".format(t.fees), "%.4f".format(t.friction),
            t.reason.title, t.intradayResolved, t.regime).joinToString(",")
        for (k in configs.keys) {
            val rows = oos.getValue(k).flatMap { (w, r) -> r.trades.map { line(k, "pencere_disi", w, it) } } + full.getValue(k).first.trades.map { line(k, "tum_gecmis", null, it) }
            File(dir, "rs1-v7-$k-islemler.csv").writeText(header + rows.joinToString("\n") + "\n")
        }
    }
    options["out"]?.let { File(it).writeText(out.toString()) }
    ctx.shutdown()
}
