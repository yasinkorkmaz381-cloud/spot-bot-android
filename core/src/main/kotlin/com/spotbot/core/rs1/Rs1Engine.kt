package com.spotbot.core.rs1

import com.spotbot.core.model.Candle
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Çıkış nedeni (öncelik sırası docs/RS1-ONKAYIT.md'de). */
enum class ExitReason(val title: String) {
    STOP_GAP("Stop (açılışta boşluk)"),
    STOP("Koruyucu stop"),
    TRAIL_STOP("İz süren stop"),
    BTC_FILTER("BTC piyasa filtresi kapandı"),
    EMA50("İki gün EMA50 altında kapanış"),
    WEEKLY("Haftalık değerlendirmede uygunluk kaybı"),
    PERIOD_END("Dönem sonu"),
}

/** Paper trading'de gerçekleşen giriş: canlı emir defterinden (VWAP) fiyat ve borsa lot kuralları. */
@Serializable
data class EntryFill(
    /** Alış fiyatı (spread ve emir büyüklüğüne göre kayma dahil, ücret hariç). */
    val price: Double,
    /** Stop mesafesinin ölçüldüğü referans fiyat (orta fiyat). */
    val refPrice: Double,
    val time: Long,
    /** Miktar adımı (0: yuvarlama yok) ve asgari emir tutarı (USDT). */
    val quantityStep: Double = 0.0,
    val minNotional: Double = 0.0,
)

/**
 * Canlı şartlar (spread, emir defteri derinliği) ve paper dolumu. Geçmişte ölçülemedikleri için backtest'te
 * [BacktestGate] kullanılır; paper trading'de değerlendirme anında ölçülen değerler kaydedilir ([SnapshotGate]).
 */
interface LiveGate {
    /** Bu değerlendirme günü için canlı ölçüm yapıldı mı? false ise o gün yeni giriş yapılmaz (erişim nedeni). */
    fun evaluated(day: Long): Boolean

    /** Giriş için canlı şartlar; null ise geçti (ölçülmüyor), değilse başarısız şartlar. */
    fun entryFailures(symbol: String, day: Long, requiredDepth: Double): List<Rs1Condition>?

    /** Açık pozisyonun canlı şartları hâlâ sağlanıyor mu? null: ölçülmedi (yalnız veri şartlarına bakılır). */
    fun stillEligible(symbol: String, day: Long, requiredDepth: Double): Boolean?

    /** Paper: değerlendirme anındaki gerçek dolum; null ise günün açılışından (backtest modeli). */
    fun entryFill(symbol: String, day: Long): EntryFill? = null
}

/** Backtest: canlı şartlar ölçülemez, uygulanmaz (raporda belirtilir); giriş günün açılışından. */
object BacktestGate : LiveGate {
    override fun evaluated(day: Long) = true
    override fun entryFailures(symbol: String, day: Long, requiredDepth: Double): List<Rs1Condition>? = null
    override fun stillEligible(symbol: String, day: Long, requiredDepth: Double): Boolean? = null
}

@Serializable
data class StopChange(val time: Long, val stop: Double)

@Serializable
data class Rs1Trade(
    val symbol: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val initialStop: Double,
    val netPnl: Double,
    val r: Double,
    val fees: Double,
    /** Spread + kayma maliyeti (USDT). */
    val friction: Double,
    val reason: ExitReason,
    val regime: String,
    val highVolatility: Boolean,
    /** Stopun yükseldiği anlar (iz süren stop). */
    val stopChanges: List<StopChange> = emptyList(),
    /** Stop gün içi mumla mı (4H/1H) yoksa günlük mumla mı çözüldü. */
    val intradayResolved: Boolean = false,
) {
    val holdingDays: Double get() = (exitTime - entryTime).toDouble() / DAY_MS
    val key: String get() = "$symbol-$entryTime"
}

@Serializable
data class Rs1Position(
    val symbol: String,
    val entryTime: Long,
    val entryPrice: Double,
    val quantity: Double,
    val initialStop: Double,
    val stop: Double,
    val highestClose: Double,
    val riskAmount: Double,
    val entryFee: Double,
    val entryFriction: Double,
    val regime: String,
    val highVolatility: Boolean,
    /** Son değerleme fiyatı ve satış maliyeti düşülmüş gerçekleşmemiş net kâr/zarar (çıkış ücreti dahil). */
    val lastPrice: Double = entryPrice,
    val unrealizedPnl: Double = 0.0,
    val stopChanges: List<StopChange> = emptyList(),
) {
    val key: String get() = "$symbol-$entryTime"
}

/** Bir giriş değerlendirmesinin kaydı: neden işlem açıldı ya da açılmadı, adaylar neden elendi. */
@Serializable
data class WeeklyDecision(
    val time: Long,
    val btc: BtcState,
    val eligible: Int,
    /** 30 günlük getiriye göre en güçlü uygun adaylar (en fazla 10). */
    val ranked: List<CoinCheck>,
    val entered: List<String>,
    val exited: List<String>,
    /** Yeni giriş yapılmadıysa neden (kural, zamanlama, bakiye ya da erişim). */
    val noEntryReason: String?,
    /** Adayın canlı şartlarda reddedilme nedenleri. */
    val liveRejections: Map<String, List<Rs1Condition>> = emptyMap(),
    /** Veri şartlarında elenen paritelerin ilk başarısız şarta göre sayısı. */
    val rejections: Map<String, Int> = emptyMap(),
)

@Serializable
data class EquityPoint(val time: Long, val equity: Double)

@Serializable
data class RegimeStats(val regime: String, val trades: Int, val averageR: Double, val netPnl: Double)

@Serializable
data class Rs1Result(
    val start: Long,
    val end: Long,
    val costs: String,
    val startCapital: Double,
    val endEquity: Double,
    val trades: List<Rs1Trade>,
    val open: List<Rs1Position>,
    val equity: List<EquityPoint>,
    val weekly: List<WeeklyDecision>,
    val btcBuyHoldPercent: Double,
    /** Kullanılan veri: kaç parite. */
    val symbols: Int,
    /** Günlerin yüzde kaçında en az bir açık pozisyon vardı. */
    val exposurePercent: Double = 0.0,
    /** Gün içi stop çözümünde günlük mumla (ihtiyatlı varsayım) çözülen stop sayısı. */
    val dailyResolvedStops: Int = 0,
    /** Ortalama sermaye kullanımı: açık pozisyon piyasa değeri / özsermaye, günlük ortalama (%). */
    val capitalUsePercent: Double = 0.0,
) {
    val netPnl: Double get() = endEquity - startCapital
    val realizedPnl: Double get() = trades.sumOf { it.netPnl }
    val unrealizedPnl: Double get() = open.sumOf { it.unrealizedPnl }
    val netReturnPercent: Double get() = netPnl / startCapital * 100
    val wins: Int get() = trades.count { it.netPnl > 0 }
    val winRatePercent: Double get() = if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size
    val profitFactor: Double get() {
        val gp = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val gl = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        return if (gl > 0) gp / gl else if (gp > 0) Double.POSITIVE_INFINITY else 0.0
    }
    val averageR: Double get() = if (trades.isEmpty()) 0.0 else trades.map { it.r }.average()
    val tStat: Double get() {
        if (trades.size < 2) return 0.0
        val m = averageR
        val sd = sqrt(trades.sumOf { (it.r - m) * (it.r - m) } / (trades.size - 1))
        return if (sd > 0) m / sd * sqrt(trades.size.toDouble()) else 0.0
    }
    /** İşlem başına net beklenen değer (USDT, gerçekleşmiş işlemler). */
    val expectancy: Double get() = if (trades.isEmpty()) 0.0 else trades.sumOf { it.netPnl } / trades.size
    val fees: Double get() = trades.sumOf { it.fees }
    val friction: Double get() = trades.sumOf { it.friction }
    val averageHoldingDays: Double get() = if (trades.isEmpty()) 0.0 else trades.map { it.holdingDays }.average()
    val maxDrawdownPercent: Double get() {
        var peak = startCapital
        var dd = 0.0
        for (p in equity) { peak = max(peak, p.equity); dd = max(dd, (peak - p.equity) / peak * 100) }
        return dd
    }
    fun byRegime(): List<RegimeStats> {
        val groups = trades.groupBy { it.regime } + trades.filter { it.highVolatility }.let { if (it.isEmpty()) emptyMap() else mapOf("yüksek volatilite" to it) }
        return groups.map { (r, l) -> RegimeStats(r, l.size, l.map { it.r }.average(), l.sumOf { it.netPnl }) }.sortedBy { it.regime }
    }
}

/**
 * Momentum RS-1'in tek karar motoru. Backtest ve paper trading aynı kodu çalıştırır; aynı girdilerle aynı kararları
 * üretir. Zaman adımı UTC gündür; karar sırası docs/RS1-ONKAYIT.md ve docs/RS1-V6-ONKAYIT.md'deki gibidir.
 *
 * @param fees parite bazında ücret oranı (MEXC exchangeInfo takerCommission); yoksa senaryodaki ücret.
 * @param intraday gün içi mumlar (4H backtest, 1H paper): yalnız stopun gün içinde ne zaman ve hangi fiyattan
 *   tetiklendiğini çözmek için. Yoksa günlük mum: açılış ≤ stop → açılış, değilse düşük ≤ stop → stop (ihtiyatlı
 *   değil, en yaygın varsayım; raporda sayılır).
 */
class Rs1Engine(
    private val series: Map<String, DailySeries>,
    private val cfg: Rs1Config = Rs1Config(),
    private val fees: Map<String, Double> = emptyMap(),
    private val intraday: Map<String, List<Candle>> = emptyMap(),
) {
    private val btc = series[BTC]
    private val btcVolRank: Map<Long, Boolean> = highVolatilityFlags()
    private val intradaySorted = intraday.mapValues { (_, l) -> l.sortedBy { it.openTime }.distinctBy { it.openTime } }

    private fun feeOf(symbol: String) = cfg.costs.feeFor(symbol, fees)

    /** [from, to) aralığındaki gün içi mumlar. */
    private fun intradayBars(symbol: String, from: Long, to: Long): List<Candle> {
        val l = intradaySorted[symbol] ?: return emptyList()
        var lo = 0
        var hi = l.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (l[m].openTime < from) lo = m + 1 else hi = m }
        val out = ArrayList<Candle>()
        var k = lo
        while (k < l.size && l[k].openTime < to) { out += l[k]; k++ }
        return out
    }

    /**
     * [start] ve [end] gün başlarıdır (UTC 00:00). [end] son tamamlanmış günün kapanışıdır; o gün için [partial] verilirse
     * (paper trading, devam eden gün) kapanış adımları ve gün içi stop kontrolü yapılır.
     */
    fun run(start: Long, end: Long, gate: LiveGate = BacktestGate, partial: Map<String, Candle> = emptyMap(), closeAtEnd: Boolean = true): Rs1Result {
        val c = cfg.costs
        var cash = cfg.capital
        val positions = LinkedHashMap<String, Pos>()
        val trades = ArrayList<Rs1Trade>()
        val equity = ArrayList<EquityPoint>()
        val decisions = ArrayList<WeeklyDecision>()
        val pendingExits = LinkedHashMap<String, ExitReason>()
        val lastPrice = HashMap<String, Double>()
        var exposedDays = 0
        var days = 0
        var capitalUse = 0.0
        var dailyResolved = 0
        fun equityAt(time: Long): Double = equity.lastOrNull { it.time == time }?.equity ?: cfg.capital

        fun bar(symbol: String, dayStart: Long): Candle? {
            val s = series[symbol] ?: return null
            val i = s.at(dayStart)
            if (i >= 0) return s.bars[i]
            return if (dayStart == end) partial[symbol] else null
        }

        fun close(p: Pos, price: Double, time: Long, reason: ExitReason, intradayResolved: Boolean = false) {
            val fill = price * (1 - c.markup)
            val fee = fill * p.quantity * feeOf(p.symbol)
            cash += fill * p.quantity - fee
            val net = (fill - p.entryPrice) * p.quantity - fee - p.entryFee
            trades += Rs1Trade(
                p.symbol, p.entryTime, time, p.entryPrice, fill, p.quantity, p.initialStop, net, net / p.riskAmount,
                fee + p.entryFee, (price - fill) * p.quantity + p.entryFriction, reason, p.regime, p.highVol, p.stops.toList(), intradayResolved,
            )
            positions.remove(p.symbol)
        }

        fun markedEquity(): Double = cash + positions.values.sumOf { p -> (lastPrice[p.symbol] ?: p.entryPrice) * (1 - c.markup) * p.quantity }

        var t = Math.floorDiv(start, DAY_MS) * DAY_MS
        while (t <= end) {
            // t'de başlayan günün mumu var mı? (Son kapanışta yalnız paper trading'in kısmi günü vardır.)
            val dayAvailable = t + DAY_MS <= end || (t == end && partial.isNotEmpty())
            if (!dayAvailable) break
            // ---- 1) Kapanış adımları (t anında, t'ye kadar tamamlanmış mumlarla); dolumlar t gününde.
            val btcState = Rs1Rules.btc(btc, t, cfg)
            // İz süren stop: girişten beri en yüksek tamamlanmış kapanış − k × ATR. Yalnız yukarı; t gününden itibaren geçerli.
            if (cfg.exitTrailing) for (p in positions.values) {
                val s = series[p.symbol] ?: continue
                val i = s.lastCompleted(t)
                if (i < 0 || s.openTime[i] + DAY_MS != t || s.openTime[i] + DAY_MS <= p.entryTime) continue
                p.highestClose = max(p.highestClose, s.close[i])
                val next = p.highestClose - cfg.trailAtr * s.atr[i]
                if (next > p.stop) { p.stop = next; p.stops += StopChange(t, next) }
            }
            val weeklyDay = isWeekday(t, cfg.entryWeekday)
            val entryDay = cfg.entryEveryDay || weeklyDay
            val requiredDepth = cfg.depthMultiple * cfg.maxPositionPercent / 100 * markedEquity()
            val checks: Map<String, CoinCheck> = if (entryDay || (weeklyDay && cfg.exitIneligible)) {
                val btcR30 = if (btcState.dataMissing) Double.NaN else btcState.return30Percent / 100
                series.values.associate { it.symbol to Rs1Rules.coin(it, t, btcR30, cfg) }
            } else emptyMap()
            val exitedNow = ArrayList<String>()
            for (p in positions.values) {
                if (p.symbol in pendingExits) continue
                val s = series[p.symbol]
                val reason = when {
                    cfg.exitBtc && !btcState.dataMissing && !Rs1Rules.btcRegimeOk(btcState) -> ExitReason.BTC_FILTER
                    cfg.exitEma && s != null && Rs1Rules.belowExitEma(s, t, cfg) -> ExitReason.EMA50
                    cfg.exitIneligible && weeklyDay && (checks[p.symbol]?.ok == false || gate.stillEligible(p.symbol, t, requiredDepth) == false) -> ExitReason.WEEKLY
                    else -> null
                }
                if (reason != null) pendingExits[p.symbol] = reason
            }
            // Kapanış sinyali çıkışları t gününün açılışında.
            for ((symbol, reason) in pendingExits.toList()) {
                val p = positions[symbol]
                if (p == null) { pendingExits.remove(symbol); continue }
                val b = bar(symbol, t) ?: continue
                close(p, b.open, t, reason)
                pendingExits.remove(symbol)
                exitedNow += symbol
            }
            // Giriş değerlendirmesi (A: haftalık gün; B: her gün).
            if (entryDay) {
                val eq = markedEquity()
                val dailyLoss = equityAt(t) <= equityAt(t - DAY_MS) * (1 - cfg.dailyLossPercent / 100)
                val ranked = Rs1Rules.rank(checks.values)
                val rejections = checks.values.filter { !it.ok }.groupingBy { it.failed.first().title }.eachCount()
                val entered = ArrayList<String>()
                val liveRejections = LinkedHashMap<String, List<Rs1Condition>>()
                val slots = cfg.maxPositions - positions.size
                var noEntry: String? = when {
                    btcState.dataMissing -> btcState.reason
                    !btcState.open -> btcState.reason
                    !gate.evaluated(t) -> "Canlı değerlendirme yapılamadı (uygulama 00:15–0${cfg.weeklyWindowHours}:00 UTC arasında çalışmıyordu); sinyalin süresi doldu"
                    dailyLoss -> "Günlük kayıp sınırı (%${cfg.dailyLossPercent}) tetiklendi"
                    slots <= 0 -> "Pozisyon sınırı dolu (${cfg.maxPositions})"
                    ranked.isEmpty() -> "Uygunluk şartlarını geçen coin yok"
                    else -> null
                }
                var balanceLimited = false
                if (noEntry == null) {
                    var free = slots
                    for (cand in ranked) {
                        if (free <= 0) break
                        if (cand.symbol in positions || cand.symbol in exitedNow) continue
                        val live = gate.entryFailures(cand.symbol, t, requiredDepth)
                        if (live != null && live.isNotEmpty()) { liveRejections[cand.symbol] = live; continue }
                        free--
                        val s = series.getValue(cand.symbol)
                        val i = s.lastCompleted(t)
                        val liveFill = gate.entryFill(cand.symbol, t)
                        val (ref, fill, time) = if (liveFill != null) Triple(liveFill.refPrice, liveFill.price, liveFill.time) else {
                            val b = bar(cand.symbol, t) ?: continue
                            Triple(b.open, b.open * (1 + c.markup), t)
                        }
                        val feeRate = feeOf(cand.symbol)
                        val stop = ref - cfg.initialStopAtr * s.atr[i]
                        if (stop <= 0) continue
                        val stopFill = stop * (1 - c.markup)
                        // Risk bütçesi stopta hedeflenen kayıptır (ücretler dahil); boşlukta gerçekleşen kayıp daha büyük olabilir.
                        val perUnit = fill - stopFill + fill * feeRate + stopFill * feeRate
                        val openValue = positions.values.sumOf { (lastPrice[it.symbol] ?: it.entryPrice) * it.quantity }
                        var qty = eq * cfg.riskPercent / 100 / perUnit
                        qty = min(qty, eq * cfg.maxPositionPercent / 100 / fill)
                        qty = min(qty, max(0.0, eq * cfg.maxTotalPercent / 100 - openValue) / fill)
                        val byCash = cash / (fill * (1 + feeRate))
                        if (byCash < qty) balanceLimited = true
                        qty = min(qty, byCash)
                        qty = min(qty, cfg.participation * s.quoteVolume[i] / fill)
                        val step = liveFill?.quantityStep ?: 0.0
                        if (step > 0) qty = Math.floor(qty / step + 1e-9) * step
                        if (qty * fill < max(cfg.minNotional, liveFill?.minNotional ?: 0.0)) continue
                        val fee = fill * qty * feeRate
                        cash -= fill * qty + fee
                        positions[cand.symbol] = Pos(
                            cand.symbol, time, fill, qty, stop, stop, Double.NEGATIVE_INFINITY, qty * perUnit, fee, (fill - ref) * qty,
                            regime(t), btcVolRank[t] == true,
                        ).also { it.stops += StopChange(time, stop) }
                        entered += cand.symbol
                    }
                    if (entered.isEmpty()) noEntry = when {
                        liveRejections.isNotEmpty() -> "Adaylar canlı şartları (spread/derinlik) geçemedi"
                        ranked.all { it.symbol in positions || it.symbol in exitedNow } -> "Uygun adaylar zaten portföyde ya da bu değerlendirmede çıkıldı"
                        balanceLimited -> "Bakiye yetersiz (nakit / toplam pozisyon sınırı)"
                        else -> "Uygun aday için pozisyon açılamadı (boyut / asgari emir sınırı)"
                    }
                }
                decisions += WeeklyDecision(t, btcState, ranked.size, ranked.take(10), entered, exitedNow, noEntry, liveRejections, rejections)
            }
            // ---- 2) Gün içi koruyucu stop (giriş günü dahil; girişten sonraki fiyatlarla).
            for (p in positions.values.toList()) {
                val b = bar(p.symbol, t) ?: continue
                // Girişten sonra açılan gün içi mumlar (girişten önceki fiyatlar stopu tetikleyemez).
                val bars = intradayBars(p.symbol, max(t, p.entryTime), t + DAY_MS)
                if (bars.isNotEmpty()) {
                    val hit = bars.firstOrNull { it.open <= p.stop || it.low <= p.stop }
                    if (hit != null) {
                        val gap = hit.open <= p.stop
                        close(p, if (gap) hit.open else p.stop, t, if (gap) ExitReason.STOP_GAP else if (p.stop > p.initialStop) ExitReason.TRAIL_STOP else ExitReason.STOP, true)
                    }
                } else {
                    if (p.entryTime > t) continue // girişten önceki fiyat bilinmiyor; gün içi veri yoksa giriş günü stop kontrolü yapılmaz
                    when {
                        b.open <= p.stop -> { dailyResolved++; close(p, b.open, t, ExitReason.STOP_GAP) }
                        b.low <= p.stop -> { dailyResolved++; close(p, p.stop, t, if (p.stop > p.initialStop) ExitReason.TRAIL_STOP else ExitReason.STOP) }
                    }
                }
            }
            // ---- 3) Gün sonu değerleme.
            for (sym in positions.keys) bar(sym, t)?.let { lastPrice[sym] = it.close }
            if (t + DAY_MS <= end) {
                val eqNow = markedEquity()
                equity += EquityPoint(t + DAY_MS, eqNow)
                days++
                if (positions.isNotEmpty()) exposedDays++
                if (eqNow > 0) capitalUse += positions.values.sumOf { (lastPrice[it.symbol] ?: it.entryPrice) * it.quantity } / eqNow
            }
            t += DAY_MS
        }
        val open: List<Rs1Position>
        if (closeAtEnd) {
            for (p in positions.values.toList()) close(p, lastPrice[p.symbol] ?: p.entryPrice, end, ExitReason.PERIOD_END)
            open = emptyList()
        } else {
            open = positions.values.map { p ->
                val last = lastPrice[p.symbol] ?: p.entryPrice
                val fill = last * (1 - c.markup)
                Rs1Position(
                    p.symbol, p.entryTime, p.entryPrice, p.quantity, p.initialStop, p.stop, p.highestClose, p.riskAmount, p.entryFee,
                    p.entryFriction, p.regime, p.highVol, last, (fill - p.entryPrice) * p.quantity - fill * p.quantity * feeOf(p.symbol) - p.entryFee,
                    p.stops.toList(),
                )
            }
        }
        val endEquity = if (closeAtEnd) cash else cash + open.sumOf { it.lastPrice * (1 - c.markup) * it.quantity * (1 - feeOf(it.symbol)) }
        return Rs1Result(
            start, end, c.name, cfg.capital, endEquity, trades, open, equity, decisions, buyHold(start, end), series.size,
            if (days == 0) 0.0 else exposedDays * 100.0 / days, dailyResolved,
            if (days == 0) 0.0 else capitalUse * 100 / days,
        )
    }

    private class Pos(
        val symbol: String, val entryTime: Long, val entryPrice: Double, val quantity: Double,
        var stop: Double, val initialStop: Double, var highestClose: Double, val riskAmount: Double,
        val entryFee: Double, val entryFriction: Double, val regime: String, val highVol: Boolean,
    ) {
        val stops = ArrayList<StopChange>()
    }

    /** BTC rejimi (yalnız raporlama): SMA200 üstü ve 20 günde yükselen → yükseliş; altı ve düşen → düşüş; diğer → yatay. */
    fun regime(t: Long): String {
        val b = btc ?: return "veri yok"
        val i = b.lastCompleted(t)
        if (i < 220 || !b.sma200[i].isFinite() || !b.sma200[i - 20].isFinite()) return "veri yok"
        val up = b.close[i] > b.sma200[i]
        val rising = b.sma200[i] > b.sma200[i - 20]
        return when {
            up && rising -> "yükseliş"
            !up && !rising -> "düşüş"
            else -> "yatay"
        }
    }

    /** Yüksek volatilite: BTC ATR14/kapanış, son 365 günün %75'lik diliminin üstünde (geleceğe bakmaz). */
    private fun highVolatilityFlags(): Map<Long, Boolean> {
        val b = btc ?: return emptyMap()
        val ratio = DoubleArray(b.n) { b.atr[it] / b.close[it] }
        val out = HashMap<Long, Boolean>()
        for (i in 365 until b.n) {
            val window = (i - 364..i).map { ratio[it] }.filter { it.isFinite() }.sorted()
            if (window.size < 200) continue
            out[b.openTime[i] + DAY_MS] = ratio[i] > window[(window.size * 3) / 4]
        }
        return out
    }

    private fun buyHold(start: Long, end: Long): Double {
        val b = btc ?: return 0.0
        // Dönem içindeki ilk mevcut BTC günü (veri dönem başından sonra başlıyorsa oradan).
        val a = (0 until b.n).firstOrNull { b.openTime[it] >= Math.floorDiv(start, DAY_MS) * DAY_MS } ?: -1
        val z = b.lastCompleted(end)
        if (a < 0 || z <= a) return 0.0
        val c = cfg.costs
        return ((b.close[z] * (1 - c.markup - c.fee)) / (b.open[a] * (1 + c.markup + c.fee)) - 1) * 100
    }
}
