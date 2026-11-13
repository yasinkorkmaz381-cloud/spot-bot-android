package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.model.Candle

/**
 * Backtest girdileri. [m15] kapanmış 15M mumlar (sıralı), [h4] her coinin 4H yön kaynağı, [members] o güne ait evren
 * (UTC gün başı → semboller; o gün ilk 300 + MEXC eşleşmesi), [terms] coin başına ücret/spread/miktar kuralları.
 */
class KrBacktestInput(
    val m15: Map<String, List<Candle>>,
    val h4: Map<String, Trend4h>,
    val members: (dayStartUtc: Long) -> Set<String>,
    val terms: Map<String, SymbolTerms>,
    val label: String,
)

class KrResult(
    val label: String,
    val costs: KrCosts,
    val start: Long,
    val end: Long,
    val startEquity: Double,
    val book: KrBook,
    val marks: Map<String, Double>,
    /** Her 15M kapanışında piyasa değeri (zaman, özsermaye). */
    val equityCurve: List<Pair<Long, Double>>,
    /** Kaçan fırsat hunisi: aşama/neden → sayı. */
    val funnel: Map<String, Int>,
    /** UTC gün → o gün evrende verisi olan coin sayısı. */
    val scannedPerDay: Map<Long, Int>,
    val ambiguousBars: Int,
) {
    val endEquity: Double get() = book.equity(marks)
    val trades: List<KrTrade> get() = book.trades
}

/**
 * Bütün coinleri zaman sırasıyla birlikte işleyen backtest. Her 15M mum için sıra:
 * 1) önceki kapanışta verilen girişler bu mumun açılışında dolar (sıralı: net R:R ↓, maliyet ↑, sembol);
 * 2) bekleyen kapanış çıkışları açılışta; 3) gün içi stop/hedef; 4) kapanışta kurulum izleyiciler ve kapanış çıkışları;
 * 5) retest onayları süzgeçlerden geçip bir sonraki açılışa yazılır.
 */
class KrBacktest(private val cfg: KrConfig, private val costs: KrCosts = cfg.costs) {

    private class Scheduled(val plan: SetupPlan)

    /**
     * [onProgress] 0..1 arası ilerleme bildirir; [isCancelled] true dönerse [java.util.concurrent.CancellationException]
     * fırlatılır (uygulamadaki iptal düğmesi).
     */
    fun run(
        input: KrBacktestInput,
        start: Long,
        end: Long,
        onProgress: ((Double) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): KrResult {
        val symbols = input.m15.keys.sorted()
        val trackers = symbols.associateWith { SetupTracker(it, cfg, input.h4[it]) }
        val index = HashMap<String, Int>().apply { symbols.forEach { put(it, 0) } }
        val funnel = LinkedHashMap<String, Int>()
        fun count(key: String) { funnel[key] = (funnel[key] ?: 0) + 1 }
        val scanned = LinkedHashMap<Long, Int>()
        val marks = HashMap<String, Double>()
        var book = KrBook(cfg.risk.startEquity)
        val curve = ArrayList<Pair<Long, Double>>()
        var scheduled = ArrayList<Scheduled>()
        var ambiguous = 0
        var currentDay = Long.MIN_VALUE
        var members: Set<String> = emptySet()

        val first = input.m15.values.mapNotNull { it.firstOrNull()?.openTime }.minOrNull() ?: start
        var g = first - Math.floorMod(first, M15)
        val g0 = g
        var step = 0
        while (g < end) {
            if (++step % 96 == 0) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("İptal edildi")
                onProgress?.invoke((g - g0).toDouble() / (end - g0))
            }
            val bars = HashMap<String, Candle>()
            for (s in symbols) {
                val list = input.m15.getValue(s)
                var i = index.getValue(s)
                while (i < list.size && list[i].openTime < g) i++
                index[s] = i
                if (i < list.size && list[i].openTime == g) bars[s] = list[i]
            }
            val trading = g >= start
            val day = g - Math.floorMod(g, KrConfig.DAY)
            // Evren o UTC gününün listesidir; dönem gün ortasında başlasa da ilk işlem mumunda yüklenir.
            if (trading && day != currentDay) {
                currentDay = day
                members = input.members(day)
                scanned[day] = members.count { input.m15[it]?.isNotEmpty() == true }
            }

            // 1) Girişler.
            if (scheduled.isNotEmpty()) {
                val quotes = scheduled.mapNotNull { s ->
                    val bar = bars[s.plan.symbol]
                    if (bar == null) { count("Giriş: ${Rejection.NO_BAR.title}"); return@mapNotNull null }
                    val t = input.terms[s.plan.symbol] ?: SymbolTerms()
                    val entryLeg = costs.legFor(t.takerFee, t.halfSpread)
                    val q = KrPricing.quote(s.plan, bar.open, entryLeg, entryLeg, cfg)
                    Triple(s, q, entryLeg)
                }.sortedWith(compareByDescending<Triple<Scheduled, KrPricing.Quote, Leg>> { it.second.netRewardRisk.takeUnless { r -> r.isNaN() } ?: -1.0 }
                    .thenBy { it.third.total }.thenBy { it.first.plan.symbol })
                for ((s, q, leg) in quotes) {
                    val plan = s.plan
                    val reason = when {
                        book.has(plan.symbol) -> Rejection.ALREADY_OPEN
                        q.fill > plan.maxEntry -> Rejection.CHASE
                        q.fill <= plan.stop -> Rejection.BAD_STOP
                        !q.target.isFinite() || q.target <= q.fill -> Rejection.NO_TARGET
                        !(q.netRewardRisk >= cfg.minNetRewardRisk) -> Rejection.RR_LOW
                        q.costShare > cfg.maxCostShareOfTarget -> Rejection.COST_HIGH
                        else -> null
                    }
                    if (reason != null) { count("Giriş: ${reason.title}"); continue }
                    val terms = input.terms[plan.symbol] ?: SymbolTerms()
                    val equity = book.equity(marks)
                    when (val sized = KrSizing.size(q.fill, plan.stop, leg, leg, equity, book.cash, book.openNotional, book.openRisk, book.positions.size, cfg.risk, terms)) {
                        is KrSizing.Result.Rejected -> count("Giriş: ${sized.reason.title}")
                        is KrSizing.Result.Ok -> {
                            count("Giriş yapıldı")
                            book = book.open(
                                KrPosition(
                                    id = "${plan.symbol}-$g", symbol = plan.symbol, setupId = plan.id, entryTime = g, refPrice = bars.getValue(plan.symbol).open,
                                    entryPrice = q.fill, quantity = sized.quantity, stop = plan.stop, target = q.target, level = plan.level,
                                    invalidation = plan.invalidation, entryLeg = leg, exitLeg = leg, entryFee = q.fill * sized.quantity * leg.fee,
                                    plannedNetRewardRisk = q.netRewardRisk, riskAmount = sized.riskAmount, highest = q.fill,
                                ),
                            )
                        }
                    }
                }
                scheduled = ArrayList()
            }

            // 2) Bekleyen kapanış çıkışları (açılıştan) ve 3) gün içi stop / hedef.
            for (p in book.positions.toList()) {
                val bar = bars[p.symbol] ?: continue
                val pending = p.pendingExit
                if (pending != null) {
                    book = book.close(KrExits.close(p, g, bar.open * (1 - p.exitLeg.priceImpact), pending, ExitSource.BACKTEST))
                    continue
                }
                val hit = KrExits.intrabar(p, bar.open, bar.high, bar.low)
                if (hit != null) {
                    if (hit.ambiguous) ambiguous++
                    book = book.close(KrExits.close(p, g, hit.price, hit.reason, ExitSource.BACKTEST, hit.ambiguous, g, g + M15))
                }
            }

            // 4) Kapanış: kurulum izleyiciler, işaret fiyatları, kapanış çıkışları.
            val events = ArrayList<Pair<String, TrackerEvent>>()
            for (s in symbols) {
                val bar = bars[s] ?: continue
                marks[s] = bar.close
                trackers.getValue(s).onBar(bar).forEach { events += s to it }
            }
            for (p in book.positions.toList()) {
                val bar = bars[p.symbol] ?: continue
                val updated = p.copy(barsHeld = p.barsHeld + 1, highest = maxOf(p.highest, bar.high))
                book = book.update(updated.copy(pendingExit = KrExits.onClose(updated, bar.close, cfg)))
            }
            val close = g + M15
            book = book.rollDay(close, marks)
            if (trading) curve += close to book.equity(marks)

            // 5) Retest onayları → süzgeçler → bir sonraki açılış.
            if (trading) {
                val btc = trackers[KrFilters.BTC]
                val btc4 = btc?.takeIf { bars.containsKey(KrFilters.BTC) }?.returnPercent(cfg.btcShockBars) ?: Double.NaN
                val btc24 = btc?.takeIf { bars.containsKey(KrFilters.BTC) }?.returnPercent(96) ?: Double.NaN
                val dailyBlocked = book.dailyLossHit(marks, cfg.risk)
                for ((s, e) in events) {
                    if (s !in members) continue
                    when (e) {
                        is TrackerEvent.BreakoutAccepted -> count("Kırılım: kabul")
                        is TrackerEvent.BreakoutRejected -> count("Kırılım: ${e.reason.title}")
                        is TrackerEvent.SetupEnded -> count("Kurulum bitti: ${e.reason.title}")
                        is TrackerEvent.RetestConfirmed -> {}
                    }
                    if (e !is TrackerEvent.RetestConfirmed) continue
                    val plan = e.plan
                    count("Retest onaylandı (evrende)")
                    val reason = KrFilters.market(plan, trackers.getValue(plan.symbol).barCount, btc4, btc24, cfg, btc?.trendAt(close))
                        ?: if (book.has(plan.symbol)) Rejection.ALREADY_OPEN else null
                        ?: if (dailyBlocked) Rejection.DAILY_LOSS else null
                    if (reason != null) { count("Süzgeç: ${reason.title}"); continue }
                    scheduled += Scheduled(plan)
                }
            }
            g += M15
        }
        return KrResult(input.label, costs, start, end, cfg.risk.startEquity, book, marks, curve, funnel, scanned, ambiguous)
    }
}
