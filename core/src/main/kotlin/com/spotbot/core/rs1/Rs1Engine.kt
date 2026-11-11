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

/**
 * Canlı şartlar (spread, emir defteri derinliği). Geçmişte ölçülemedikleri için backtest'te [BacktestGate] kullanılır;
 * paper trading'de haftalık değerlendirmede ölçülen değerler kaydedilir ([SnapshotGate]).
 */
interface LiveGate {
    /** Bu pazartesi için canlı ölçüm yapıldı mı? false ise o hafta yeni giriş yapılmaz (erişim nedeni). */
    fun evaluated(monday: Long): Boolean

    /** Giriş için canlı şartlar; null ise geçti, değilse başarısız şartlar. */
    fun entryFailures(symbol: String, monday: Long, requiredDepth: Double): List<Rs1Condition>?

    /** Açık pozisyonun canlı şartları hâlâ sağlanıyor mu? null: ölçülmedi (yalnız veri şartlarına bakılır). */
    fun stillEligible(symbol: String, monday: Long, requiredDepth: Double): Boolean?
}

/** Backtest: canlı şartlar ölçülemez, uygulanmaz (raporda belirtilir). */
object BacktestGate : LiveGate {
    override fun evaluated(monday: Long) = true
    override fun entryFailures(symbol: String, monday: Long, requiredDepth: Double): List<Rs1Condition>? = null
    override fun stillEligible(symbol: String, monday: Long, requiredDepth: Double): Boolean? = null
}

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
) {
    val key: String get() = "$symbol-$entryTime"
}

/** Haftalık değerlendirmenin kaydı: neden işlem açıldı ya da açılmadı. */
@Serializable
data class WeeklyDecision(
    val time: Long,
    val btc: BtcState,
    val eligible: Int,
    /** 30 günlük getiriye göre en güçlü uygun adaylar (en fazla 10). */
    val ranked: List<CoinCheck>,
    val entered: List<String>,
    val exited: List<String>,
    /** Yeni giriş yapılmadıysa neden (kural ya da erişim). */
    val noEntryReason: String?,
    /** Adayın canlı şartlarda reddedilme nedenleri. */
    val liveRejections: Map<String, List<Rs1Condition>> = emptyMap(),
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
    /** Kullanılan veri: kaç parite, ilk ve son mum. */
    val symbols: Int,
    /** Günlerin yüzde kaçında en az bir açık pozisyon vardı. */
    val exposurePercent: Double = 0.0,
) {
    val netPnl: Double get() = endEquity - startCapital
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
    /** İşlem başına net beklenen değer (USDT). */
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
 * üretir. Zaman adımı UTC gündür; karar sırası docs/RS1-ONKAYIT.md'deki gibidir.
 */
class Rs1Engine(private val series: Map<String, DailySeries>, private val cfg: Rs1Config = Rs1Config()) {
    private val btc = series[BTC]
    private val btcVolRank: Map<Long, Boolean> = highVolatilityFlags()

    /**
     * [start] ve [end] gün başlarıdır (UTC 00:00). [end] son tamamlanmış günün kapanışıdır; o gün için [partial] verilirse
     * (paper trading, devam eden gün) yalnız kapanış adımları ve gün içi stop kontrolü yapılır.
     */
    fun run(start: Long, end: Long, gate: LiveGate = BacktestGate, partial: Map<String, Candle> = emptyMap(), closeAtEnd: Boolean = true): Rs1Result {
        val c = cfg.costs
        var cash = cfg.capital
        val positions = LinkedHashMap<String, Pos>()
        val trades = ArrayList<Rs1Trade>()
        val equity = ArrayList<EquityPoint>()
        val weekly = ArrayList<WeeklyDecision>()
        val pendingExits = LinkedHashMap<String, ExitReason>()
        val lastPrice = HashMap<String, Double>()
        var exposedDays = 0
        var days = 0
        /** [time] kapanışındaki kayıtlı özsermaye (yoksa başlangıç sermayesi). */
        fun equityAt(time: Long): Double = equity.lastOrNull { it.time == time }?.equity ?: cfg.capital

        fun bar(symbol: String, dayStart: Long): Candle? {
            val s = series[symbol] ?: return null
            val i = s.at(dayStart)
            if (i >= 0) return s.bars[i]
            return if (dayStart == end) partial[symbol] else null
        }

        fun close(p: Pos, price: Double, time: Long, reason: ExitReason) {
            val fill = price * (1 - c.markup)
            val fee = fill * p.quantity * c.fee
            cash += fill * p.quantity - fee
            val net = (fill - p.entryPrice) * p.quantity - fee - p.entryFee
            trades += Rs1Trade(
                p.symbol, p.entryTime, time, p.entryPrice, fill, p.quantity, p.initialStop, net, net / p.riskAmount,
                fee + p.entryFee, (price - fill) * p.quantity + p.entryFriction, reason, p.regime, p.highVol,
            )
            positions.remove(p.symbol)
        }

        fun markedEquity(): Double = cash + positions.values.sumOf { p -> (lastPrice[p.symbol] ?: p.entryPrice) * (1 - c.markup) * p.quantity }

        var t = Math.floorDiv(start, DAY_MS) * DAY_MS
        while (t <= end) {
            // t'de başlayan günün mumu var mı? (Son kapanışta yalnız paper trading'in kısmi günü vardır.)
            val dayAvailable = t + DAY_MS <= end || (t == end && partial.isNotEmpty())
            if (!dayAvailable) break
            // ---- 1) Kapanış adımları (t anında, t'ye kadar tamamlanmış mumlarla); dolumlar t gününün açılışında.
            run {
                val btcState = Rs1Rules.btc(btc, t, cfg)
                // İz süren stop: girişten beri en yüksek tamamlanmış kapanış − 3 × ATR.
                for (p in positions.values) {
                    val s = series[p.symbol] ?: continue
                    val i = s.lastCompleted(t)
                    if (i < 0 || s.openTime[i] + DAY_MS != t || s.openTime[i] < p.entryTime) continue
                    p.highestClose = max(p.highestClose, s.close[i])
                    p.stop = max(p.stop, p.highestClose - cfg.trailAtr * s.atr[i])
                }
                val monday = isWeekday(t, cfg.entryWeekday)
                val requiredDepth = cfg.depthMultiple * cfg.maxPositionPercent / 100 * markedEquity()
                val checks: Map<String, CoinCheck> = if (monday) {
                    val btcR30 = if (btcState.dataMissing) Double.NaN else btcState.return30Percent / 100
                    series.values.associate { it.symbol to Rs1Rules.coin(it, t, btcR30, cfg) }
                } else emptyMap()
                val exitedNow = ArrayList<String>()
                for (p in positions.values) {
                    if (p.symbol in pendingExits) continue
                    val s = series[p.symbol]
                    val reason = when {
                        !btcState.dataMissing && !btcState.open -> ExitReason.BTC_FILTER
                        s != null && Rs1Rules.belowExitEma(s, t, cfg) -> ExitReason.EMA50
                        monday && (checks[p.symbol]?.ok == false || gate.stillEligible(p.symbol, t, requiredDepth) == false) -> ExitReason.WEEKLY
                        else -> null
                    }
                    if (reason != null) pendingExits[p.symbol] = reason
                }
                // Çıkışlar t gününün açılışında.
                for ((symbol, reason) in pendingExits.toList()) {
                    val p = positions[symbol]
                    if (p == null) { pendingExits.remove(symbol); continue }
                    val b = bar(symbol, t) ?: continue
                    close(p, b.open, t, reason)
                    pendingExits.remove(symbol)
                    exitedNow += symbol
                }
                // Haftalık giriş.
                if (monday) {
                    val eq = markedEquity()
                    // Günlük kayıp: t kapanışındaki özsermaye, 24 saat önceki kapanışınkinden %1,5+ düşükse yeni giriş yok.
                    val dailyLoss = equityAt(t) <= equityAt(t - DAY_MS) * (1 - cfg.dailyLossPercent / 100)
                    val ranked = Rs1Rules.rank(checks.values)
                    val entered = ArrayList<String>()
                    val liveRejections = LinkedHashMap<String, List<Rs1Condition>>()
                    val slots = cfg.maxPositions - positions.size
                    var noEntry: String? = when {
                        btcState.dataMissing -> btcState.reason
                        !btcState.open -> btcState.reason
                        !gate.evaluated(t) -> "Haftalık canlı değerlendirme yapılamadı (uygulama 00:00–0${cfg.weeklyWindowHours}:00 UTC arasında çalışmıyordu)"
                        dailyLoss -> "Günlük kayıp sınırı (%${cfg.dailyLossPercent}) tetiklendi"
                        slots <= 0 -> "Pozisyon sınırı dolu (${cfg.maxPositions})"
                        ranked.isEmpty() -> "Uygunluk şartlarını geçen coin yok"
                        else -> null
                    }
                    if (noEntry == null) {
                        var free = slots
                        for (cand in ranked) {
                            if (free <= 0) break
                            if (cand.symbol in positions || cand.symbol in exitedNow) continue
                            val live = gate.entryFailures(cand.symbol, t, requiredDepth)
                            if (live != null && live.isNotEmpty()) { liveRejections[cand.symbol] = live; continue }
                            free--
                            val b = bar(cand.symbol, t) ?: continue
                            val s = series.getValue(cand.symbol)
                            val i = s.lastCompleted(t)
                            val fill = b.open * (1 + c.markup)
                            val stop = b.open - cfg.initialStopAtr * s.atr[i]
                            if (stop <= 0) continue
                            val stopFill = stop * (1 - c.markup)
                            val perUnit = fill - stopFill + fill * c.fee + stopFill * c.fee
                            val openValue = positions.values.sumOf { (lastPrice[it.symbol] ?: it.entryPrice) * it.quantity }
                            var qty = eq * cfg.riskPercent / 100 / perUnit
                            qty = min(qty, eq * cfg.maxPositionPercent / 100 / fill)
                            qty = min(qty, max(0.0, eq * cfg.maxTotalPercent / 100 - openValue) / fill)
                            qty = min(qty, cash / (fill * (1 + c.fee)))
                            qty = min(qty, cfg.participation * s.quoteVolume[i] / fill)
                            if (qty * fill < cfg.minNotional) continue
                            val fee = fill * qty * c.fee
                            cash -= fill * qty + fee
                            val regime = regime(t)
                            positions[cand.symbol] = Pos(
                                cand.symbol, t, fill, qty, stop, stop, Double.NEGATIVE_INFINITY, qty * perUnit, fee, (fill - b.open) * qty,
                                regime, btcVolRank[t] == true,
                            )
                            entered += cand.symbol
                        }
                        if (entered.isEmpty()) noEntry = when {
                            liveRejections.isNotEmpty() -> "Adaylar canlı şartları (spread/derinlik) geçemedi"
                            ranked.all { it.symbol in positions || it.symbol in exitedNow } -> "Uygun adaylar zaten portföyde ya da bu değerlendirmede çıkıldı"
                            else -> "Uygun aday için pozisyon açılamadı (boyut/nakit sınırı)"
                        }
                    }
                    weekly += WeeklyDecision(t, btcState, ranked.size, ranked.take(10), entered, exitedNow, noEntry, liveRejections)
                }
            }
            // ---- 2) Gün içi koruyucu stop (giriş günü dahil); çıkış zamanı stopun görüldüğü gün.
            for (p in positions.values.toList()) {
                val b = bar(p.symbol, t) ?: continue
                when {
                    b.open <= p.stop -> close(p, b.open, t, ExitReason.STOP_GAP)
                    b.low <= p.stop -> close(p, p.stop, t, if (p.stop > p.initialStop) ExitReason.TRAIL_STOP else ExitReason.STOP)
                }
            }
            // ---- 3) Gün sonu değerleme.
            for (sym in positions.keys) bar(sym, t)?.let { lastPrice[sym] = it.close }
            if (t + DAY_MS <= end) {
                equity += EquityPoint(t + DAY_MS, markedEquity())
                days++
                if (positions.isNotEmpty()) exposedDays++
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
                    p.entryFriction, p.regime, p.highVol, last, (fill - p.entryPrice) * p.quantity - fill * p.quantity * c.fee - p.entryFee,
                )
            }
        }
        val endEquity = if (closeAtEnd) cash else cash + open.sumOf { it.lastPrice * (1 - c.markup) * it.quantity * (1 - c.fee) }
        return Rs1Result(
            start, end, c.name, cfg.capital, endEquity, trades, open, equity, weekly, buyHold(start, end), series.size,
            if (days == 0) 0.0 else exposedDays * 100.0 / days,
        )
    }

    private class Pos(
        val symbol: String, val entryTime: Long, val entryPrice: Double, val quantity: Double,
        var stop: Double, val initialStop: Double, var highestClose: Double, val riskAmount: Double,
        val entryFee: Double, val entryFriction: Double, val regime: String, val highVol: Boolean,
    )

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
        val a = b.at(Math.floorDiv(start, DAY_MS) * DAY_MS)
        val z = b.lastCompleted(end)
        if (a < 0 || z <= a) return 0.0
        val c = cfg.costs
        return ((b.close[z] * (1 - c.markup - c.fee)) / (b.open[a] * (1 + c.markup + c.fee)) - 1) * 100
    }
}
