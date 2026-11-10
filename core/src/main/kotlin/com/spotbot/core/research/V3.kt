package com.spotbot.core.research

import com.spotbot.core.model.Candle
import com.spotbot.core.ta.Indicators
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Strateji araştırması V3 (docs/STRATEJI-V3-ONKAYIT.md). 4H mumlarla olay güdümlü, çok pariteli portföy
 * simülasyonu. Her karar yalnız o ana kadar kapanmış 4H ve 1D mumlarını kullanır; giriş ve strateji çıkışları bir
 * sonraki 4H açılışında yapılır.
 */
@Serializable
data class H4Dataset(val symbol: String, val h4: List<Candle>)

const val H4_MS = 4 * 3_600_000L
const val DAY_MS = 24 * 3_600_000L

/** Bir paritenin 4H ve (4H'den üretilen) 1D verisi ile göstergeleri. */
class SymbolData(val symbol: String, h4: List<Candle>) {
    val bars: List<Candle> = h4.sortedBy { it.openTime }.distinctBy { it.openTime }
    val n = bars.size
    val open = DoubleArray(n) { bars[it].open }
    val high = DoubleArray(n) { bars[it].high }
    val low = DoubleArray(n) { bars[it].low }
    val close = DoubleArray(n) { bars[it].close }
    val volume = DoubleArray(n) { bars[it].volume }
    val quoteVolume = DoubleArray(n) { bars[it].quoteVolume }
    private val indexByClose = HashMap<Long, Int>(n * 2).also { m -> bars.forEachIndexed { i, c -> m[c.openTime + H4_MS] = i } }

    /** Kapanış zamanı [t] olan 4H mumunun indeksi (yoksa -1). */
    fun at(t: Long): Int = indexByClose[t] ?: -1

    val atr4 = Indicators.atr(high, low, close, 14)
    val rsi4 = Indicators.rsi(close, 14)
    val macdHist4 = Indicators.macd(close).histogram
    val volSma4 = Indicators.sma(volume, 20)
    /** Bollinger(20, 2) genişliği / orta bant. */
    val bbWidth4: DoubleArray = run {
        val mid = Indicators.sma(close, 20)
        DoubleArray(n) { i ->
            if (i < 19 || mid[i].isNaN() || mid[i] <= 0) Double.NaN else {
                var s = 0.0
                for (j in i - 19..i) s += (close[j] - mid[i]) * (close[j] - mid[i])
                4 * sqrt(s / 20) / mid[i]
            }
        }
    }

    /** UTC günlük mumlar: yalnız 6 adet 4H mumu tamamlanmış günler. */
    val days: List<Candle> = bars.groupBy { it.openTime / DAY_MS }.filterValues { it.size == 6 }.toSortedMap().map { (day, g) ->
        Candle(day * DAY_MS, g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.sumOf { it.volume }, (day + 1) * DAY_MS, g.sumOf { it.quoteVolume })
    }
    val dClose = DoubleArray(days.size) { days[it].close }
    private val dHigh = DoubleArray(days.size) { days[it].high }
    private val dLow = DoubleArray(days.size) { days[it].low }
    val dQuoteVolume = DoubleArray(days.size) { days[it].quoteVolume }
    private val dCloseTime = LongArray(days.size) { days[it].closeTime }
    val ema20d = Indicators.ema(dClose, 20)
    val ema50d = Indicators.ema(dClose, 50)
    val sma200d = Indicators.sma(dClose, 200)
    val atr14d = Indicators.atr(dHigh, dLow, dClose, 14)
    val adx14d = Indicators.adx(dHigh, dLow, dClose, 14)

    /** Kapanış zamanı ≤ [t] olan son günlük mumun indeksi (yoksa -1). */
    fun day(t: Long): Int {
        var lo = 0
        var hi = days.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (dCloseTime[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** Geçmişte işlem yapılabilirlik: ≥ 210 gün geçmiş ve son 30 günün medyan günlük hacmi ≥ [minDailyVolume]. */
    fun tradable(t: Long, minDailyVolume: Double): Boolean {
        val d = day(t)
        if (d < 209) return false
        val v = (d - 29..d).map { dQuoteVolume[it] }.sorted()
        return (v[14] + v[15]) / 2 >= minDailyVolume
    }

    fun roc(d: Int, days: Int): Double = if (d - days < 0) Double.NaN else dClose[d] / dClose[d - days] - 1
}

class Universe(val symbols: Map<String, SymbolData>) {
    val btc: SymbolData = symbols.getValue("BTCUSDT")

    /** BTC rejimi (yalnız raporlama): SMA200 üstü ve 20 günde yükselen → yükseliş; altı ve düşen → düşüş; diğer → yatay. */
    fun regime(t: Long): String {
        val d = btc.day(t)
        if (d < 220 || btc.sma200d[d].isNaN() || btc.sma200d[d - 20].isNaN()) return "veri yok"
        val up = btc.dClose[d] > btc.sma200d[d]
        val rising = btc.sma200d[d] > btc.sma200d[d - 20]
        return when {
            up && rising -> "yükseliş"
            !up && !rising -> "düşüş"
            else -> "yatay"
        }
    }

    companion object {
        fun of(data: List<H4Dataset>) = Universe(data.associate { it.symbol to SymbolData(it.symbol, it.h4) })
    }
}

@Serializable
data class CostScenario(val name: String, val halfSpread: Double, val fee: Double = 0.0005, val slippage: Double = 0.0005) {
    val buyMarkup get() = halfSpread + slippage
    val sellMarkdown get() = halfSpread + slippage

    companion object {
        val OPTIMISTIC = CostScenario("İyimser", 0.00025)
        val MEDIUM = CostScenario("Orta", 0.00075)
        val PESSIMISTIC = CostScenario("Kötümser", 0.0015)
        val all = listOf(OPTIMISTIC, MEDIUM, PESSIMISTIC)
    }
}

data class V3Config(
    val start: Long,
    val end: Long,
    val costs: CostScenario = CostScenario.MEDIUM,
    val capital: Double = 1_000.0,
    val riskPercent: Double = 0.5,
    val maxPositions: Int = 3,
    val maxPositionPercent: Double = 30.0,
    val dailyLossPercent: Double = 1.5,
    val participation: Double = 0.1,
    val minDailyVolume: Double = 1_000_000.0,
    val minNotional: Double = 5.0,
)

/** Açık pozisyon (strateji alanları: [peak] iz süren stop için en yüksek kapanış). */
class V3Position(
    val symbol: String,
    val entryTime: Long,
    val entryPrice: Double,
    val quantity: Double,
    var stop: Double,
    val initialStop: Double,
    val target: Double?,
    val maxBars: Int?,
    val riskAmount: Double,
    val entryFee: Double,
    val entryFriction: Double,
    val regime: String,
    var peak: Double,
    var bars: Int = 0,
)

@Serializable
data class V3Trade(
    val symbol: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val netPnl: Double,
    val r: Double,
    val fees: Double,
    val friction: Double,
    val reason: String,
    val regime: String,
)

/** Giriş sinyali: stop ve hedef sinyal kapanışına göre hesaplanır (giriş fiyatı sonraki açılışta belli olur). */
data class EntrySignal(val symbol: String, val stop: Double, val target: Double?, val maxBars: Int?, val priority: Double)

interface V3Strategy {
    val id: String
    val name: String
    val option: String
    /** [t] kapanışında giriş sinyalleri (önceliğe göre). */
    fun entries(u: Universe, t: Long, cfg: V3Config): List<EntrySignal>
    /** [t] kapanışında strateji çıkışı (sonraki açılışta uygulanır); yoksa null. */
    fun exit(u: Universe, p: V3Position, t: Long, cfg: V3Config): String? = null
    /** [t] kapanışında stopu günceller (yalnız yukarı). */
    fun trail(u: Universe, p: V3Position, t: Long) {}
}

@Serializable
data class RegimeStats(val regime: String, val trades: Int, val averageR: Double, val netPnl: Double)

@Serializable
data class V3Result(
    val strategy: String,
    val option: String,
    val costs: String,
    val start: Long,
    val end: Long,
    val trades: Int,
    val wins: Int,
    val winRatePercent: Double,
    val profitFactor: Double,
    val averageR: Double,
    /** Ortalama R'nin t istatistiği (ort / std × √n); ~2'nin altı rastlantıdan ayırt edilemez. */
    val tStat: Double,
    val netReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val fees: Double,
    val friction: Double,
    val exposurePercent: Double,
    val btcBuyHoldPercent: Double,
    val byRegime: List<RegimeStats>,
    val tradeList: List<V3Trade>,
    /** Yalnız `closeAtEnd = false` ile: dönem sonunda hâlâ açık pozisyonlar (piyasa değerinden). */
    val open: List<V3OpenPosition> = emptyList(),
)

@Serializable
data class V3OpenPosition(
    val symbol: String,
    val entryTime: Long,
    val entryPrice: Double,
    val quantity: Double,
    val stop: Double,
    val lastPrice: Double,
    /** Satış maliyeti ve iki yön ücreti düşülmüş, gerçekleşmemiş net kâr/zarar. */
    val unrealizedPnl: Double,
)

class V3Engine(private val u: Universe, private val cfg: V3Config) {

    /**
     * [closeAtEnd] false ise dönem sonunda açık pozisyonlar kapatılmaz; net getiri piyasa değeriyle (satış maliyeti
     * düşülerek) hesaplanır ve pozisyonlar [V3Result.open] içinde döner (ileri test için).
     */
    fun run(strategy: V3Strategy, closeAtEnd: Boolean = true): V3Result {
        val c = cfg.costs
        var cash = cfg.capital
        val positions = LinkedHashMap<String, V3Position>()
        val pendingEntries = ArrayList<EntrySignal>()
        val pendingExits = HashMap<String, String>()
        val trades = ArrayList<V3Trade>()
        var peakEquity = cfg.capital
        var maxDd = 0.0
        var dayStart = Long.MIN_VALUE
        var dayStartEquity = cfg.capital
        var exposed = 0
        var steps = 0
        val lastClose = HashMap<String, Double>()

        fun close(p: V3Position, price: Double, time: Long, reason: String) {
            val fill = price * (1 - c.sellMarkdown)
            val fee = fill * p.quantity * c.fee
            val friction = (price - fill) * p.quantity + p.entryFriction
            cash += fill * p.quantity - fee
            val net = (fill - p.entryPrice) * p.quantity - fee - p.entryFee
            trades += V3Trade(p.symbol, p.entryTime, time, p.entryPrice, fill, p.quantity, net, net / p.riskAmount, fee + p.entryFee, friction, reason, p.regime)
            positions.remove(p.symbol)
        }

        fun equity(): Double = cash + positions.values.sumOf { p -> (lastClose[p.symbol] ?: p.entryPrice) * (1 - c.sellMarkdown) * p.quantity }

        var t = (cfg.start / H4_MS) * H4_MS + H4_MS
        while (t <= cfg.end) {
            val barOpen = t - H4_MS
            // 1) Önceki kapanışta verilen çıkışlar bu mumun açılışında.
            for ((symbol, reason) in pendingExits.toList()) {
                val p = positions[symbol] ?: continue
                val k = u.symbols.getValue(symbol).at(t)
                if (k < 0) continue
                close(p, u.symbols.getValue(symbol).open[k], barOpen, reason)
                pendingExits.remove(symbol)
            }
            // 2) Bekleyen girişler bu mumun açılışında (risk boyutlandırma gerçek dolum fiyatıyla).
            val eq0 = equity()
            for (s in pendingEntries) {
                if (positions.size >= cfg.maxPositions || s.symbol in positions) continue
                val d = u.symbols.getValue(s.symbol)
                val k = d.at(t)
                if (k < 0 || d.open[k] <= s.stop) continue
                val fill = d.open[k] * (1 + c.buyMarkup)
                val stopFill = s.stop * (1 - c.sellMarkdown)
                val perUnit = fill - stopFill + fill * c.fee + stopFill * c.fee
                if (perUnit <= 0) continue
                var qty = eq0 * cfg.riskPercent / 100 / perUnit
                qty = min(qty, eq0 * cfg.maxPositionPercent / 100 / fill)
                qty = min(qty, cash / (fill * (1 + c.fee)))
                qty = min(qty, cfg.participation * d.quoteVolume[k] / fill)
                if (qty * fill < cfg.minNotional) continue
                val fee = fill * qty * c.fee
                cash -= fill * qty + fee
                positions[s.symbol] = V3Position(
                    s.symbol, barOpen, fill, qty, s.stop, s.stop, s.target, s.maxBars, qty * perUnit, fee, (fill - d.open[k]) * qty,
                    u.regime(barOpen), d.close[maxOf(0, k - 1)],
                )
            }
            pendingEntries.clear()
            // 3) Mum içi stop / hedef (ihtiyatlı: stop önce).
            for (p in positions.values.toList()) {
                val d = u.symbols.getValue(p.symbol)
                val k = d.at(t)
                if (k < 0) continue
                when {
                    d.open[k] <= p.stop -> close(p, d.open[k], t, "Stop (boşluk)")
                    d.low[k] <= p.stop -> close(p, p.stop, t, if (p.stop > p.initialStop) "İz süren stop" else "Stop")
                    p.target != null && d.high[k] >= p.target -> close(p, max(p.target, d.open[k]), t, "Hedef")
                }
            }
            // 4) Kapanışta: süre, iz süren stop ve strateji çıkışları (sonraki açılışta).
            for (sym in u.symbols.keys) u.symbols.getValue(sym).at(t).takeIf { it >= 0 }?.let { lastClose[sym] = u.symbols.getValue(sym).close[it] }
            for (p in positions.values) {
                val d = u.symbols.getValue(p.symbol)
                if (d.at(t) < 0) continue
                p.bars++
                strategy.trail(u, p, t)
                if (p.maxBars != null && p.bars >= p.maxBars) pendingExits[p.symbol] = "Süre doldu"
                strategy.exit(u, p, t, cfg)?.let { pendingExits.putIfAbsent(p.symbol, it) }
            }
            // 5) Özsermaye, düşüş, günlük kayıp.
            val eq = equity()
            if (t / DAY_MS != dayStart) { dayStart = t / DAY_MS; dayStartEquity = eq }
            peakEquity = max(peakEquity, eq)
            maxDd = max(maxDd, (peakEquity - eq) / peakEquity * 100)
            steps++
            if (positions.isNotEmpty()) exposed++
            // 6) Yeni giriş sinyalleri.
            val dailyBlocked = eq <= dayStartEquity * (1 - cfg.dailyLossPercent / 100)
            if (!dailyBlocked && positions.size - pendingExits.size < cfg.maxPositions) {
                pendingEntries += strategy.entries(u, t, cfg)
                    .filter { it.symbol !in positions && u.symbols.getValue(it.symbol).tradable(t, cfg.minDailyVolume) }
                    .sortedByDescending { it.priority }
                    .take(cfg.maxPositions - (positions.size - pendingExits.size))
            }
            t += H4_MS
        }
        val open = if (closeAtEnd) {
            for (p in positions.values.toList()) close(p, lastClose[p.symbol] ?: p.entryPrice, cfg.end, "Dönem sonu")
            emptyList()
        } else positions.values.map { p ->
            val last = lastClose[p.symbol] ?: p.entryPrice
            val fill = last * (1 - c.sellMarkdown)
            V3OpenPosition(p.symbol, p.entryTime, p.entryPrice, p.quantity, p.stop, last, (fill - p.entryPrice) * p.quantity - fill * p.quantity * c.fee - p.entryFee)
        }
        val finalValue = if (closeAtEnd) cash else equity() - open.sumOf { it.lastPrice * (1 - c.sellMarkdown) * it.quantity * c.fee }
        val btc = u.btc
        val a = btc.day(cfg.start)
        val b = btc.day(cfg.end)
        val buyHold = if (a >= 0 && b > a) ((btc.dClose[b] * (1 - c.sellMarkdown - c.fee)) / (btc.dClose[a] * (1 + c.buyMarkup + c.fee)) - 1) * 100 else 0.0
        return summarize(strategy, trades, finalValue, maxDd, if (steps == 0) 0.0 else exposed * 100.0 / steps, buyHold).copy(open = open)
    }

    private fun summarize(s: V3Strategy, trades: List<V3Trade>, finalCash: Double, maxDd: Double, exposure: Double, buyHold: Double): V3Result {
        val wins = trades.count { it.netPnl > 0 }
        val gp = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
        val gl = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
        val rs = trades.map { it.r }
        val mean = if (rs.isEmpty()) 0.0 else rs.average()
        val sd = if (rs.size < 2) 0.0 else sqrt(rs.sumOf { (it - mean) * (it - mean) } / (rs.size - 1))
        return V3Result(
            s.name, s.option, cfg.costs.name, cfg.start, cfg.end, trades.size, wins,
            if (trades.isEmpty()) 0.0 else wins * 100.0 / trades.size,
            if (gl > 0) gp / gl else if (gp > 0) Double.POSITIVE_INFINITY else 0.0,
            mean, if (sd > 0) mean / sd * sqrt(rs.size.toDouble()) else 0.0,
            (finalCash / cfg.capital - 1) * 100, maxDd, trades.sumOf { it.fees }, trades.sumOf { it.friction }, exposure, buyHold,
            trades.groupBy { it.regime }.map { (r, l) -> RegimeStats(r, l.size, l.map { it.r }.average(), l.sumOf { it.netPnl }) }.sortedBy { it.regime },
            trades,
        )
    }
}

// ---------------- Stratejiler (ön kayıttaki tanımlar) ----------------

/** S1: BTC'ye göre göreceli güç + trend; günlük kapanışta sıralama, ilk 3'te giriş, ilk 6 dışında çıkış. */
class RelativeStrength(private val lookback: Int) : V3Strategy {
    override val id = "S1"
    override val name = "S1 Göreceli güç (BTC'ye göre)"
    override val option = "L=$lookback gün"

    private fun ranking(u: Universe, t: Long, cfg: V3Config): List<Pair<String, Double>> {
        val btcRoc = u.btc.day(t).let { u.btc.roc(it, lookback) }
        if (btcRoc.isNaN()) return emptyList()
        return u.symbols.values.mapNotNull { d ->
            val i = d.day(t)
            if (i < 60 || d.days[i].closeTime != t || !d.tradable(t, cfg.minDailyVolume)) return@mapNotNull null
            if (d.dClose[i] <= d.ema50d[i] || d.ema50d[i] <= d.ema50d[i - 10]) return@mapNotNull null
            val rs = if (d === u.btc) 0.0 else d.roc(i, lookback) - btcRoc
            if (rs.isNaN() || rs <= 0) null else d.symbol to rs
        }.sortedByDescending { it.second }
    }

    override fun entries(u: Universe, t: Long, cfg: V3Config): List<EntrySignal> {
        if (t % DAY_MS != 0L) return emptyList()
        return ranking(u, t, cfg).take(3).map { (s, rs) ->
            val d = u.symbols.getValue(s)
            val i = d.day(t)
            EntrySignal(s, d.dClose[i] - 3 * d.atr14d[i], null, null, rs)
        }
    }

    override fun exit(u: Universe, p: V3Position, t: Long, cfg: V3Config): String? {
        if (t % DAY_MS != 0L) return null
        val rank = ranking(u, t, cfg).indexOfFirst { it.first == p.symbol }
        return if (rank < 0 || rank >= 6) "Sıralama dışı / trend bozuldu" else null
    }

    override fun trail(u: Universe, p: V3Position, t: Long) {
        if (t % DAY_MS != 0L) return
        val d = u.symbols.getValue(p.symbol)
        val i = d.day(t)
        if (i < 0 || d.days[i].closeTime != t) return
        p.peak = max(p.peak, d.dClose[i])
        p.stop = max(p.stop, p.peak - 3 * d.atr14d[i])
    }
}

/** S2: 1D çift ufuklu momentum + 4H MACD histogramı sıfır üstüne geçiş. */
class DualMomentum(private val n: Int) : V3Strategy {
    override val id = "S2"
    override val name = "S2 1D/4H momentum"
    override val option = "N=$n gün"

    override fun entries(u: Universe, t: Long, cfg: V3Config): List<EntrySignal> = u.symbols.values.mapNotNull { d ->
        val k = d.at(t)
        if (k < 30) return@mapNotNull null
        val i = d.day(t)
        if (i < n) return@mapNotNull null
        val roc = d.roc(i, n)
        if (!(roc > 0 && d.roc(i, n / 2) > 0)) return@mapNotNull null
        if (!(d.macdHist4[k - 1] <= 0 && d.macdHist4[k] > 0) || d.atr4[k].isNaN()) return@mapNotNull null
        EntrySignal(d.symbol, d.close[k] - 2.5 * d.atr4[k], null, null, roc)
    }

    override fun exit(u: Universe, p: V3Position, t: Long, cfg: V3Config): String? {
        if (t % DAY_MS != 0L) return null
        val d = u.symbols.getValue(p.symbol)
        val i = d.day(t)
        return if (d.roc(i, n) <= 0) "Günlük momentum bitti" else null
    }

    override fun trail(u: Universe, p: V3Position, t: Long) {
        val d = u.symbols.getValue(p.symbol)
        val k = d.at(t)
        if (k < 0 || d.atr4[k].isNaN()) return
        p.peak = max(p.peak, d.close[k])
        p.stop = max(p.stop, p.peak - 3 * d.atr4[k])
    }
}

/** S3: Bollinger daralması → hacimli 20 mumluk kırılım → teyit mumu → giriş; ölçülen hareket hedefi. */
class SqueezeBreakout(private val percentile: Int) : V3Strategy {
    override val id = "S3"
    override val name = "S3 Daralma + teyitli kırılım"
    override val option = "P=%$percentile"

    override fun entries(u: Universe, t: Long, cfg: V3Config): List<EntrySignal> = u.symbols.values.mapNotNull { d ->
        val k = d.at(t)
        val b = k - 1
        if (b < 200) return@mapNotNull null
        var level = Double.NEGATIVE_INFINITY
        var rangeLow = Double.POSITIVE_INFINITY
        for (j in b - 20 until b) { level = max(level, d.high[j]); rangeLow = min(rangeLow, d.low[j]) }
        if (!(d.close[b] > level && d.volume[b] >= 1.5 * d.volSma4[b - 1] && d.close[k] > level)) return@mapNotNull null
        val w = d.bbWidth4[b - 1]
        val window = (b - 180 until b).map { d.bbWidth4[it] }.filter { !it.isNaN() }.sorted()
        if (w.isNaN() || window.size < 90 || w > window[(window.size - 1) * percentile / 100]) return@mapNotNull null
        if (d.atr4[k].isNaN() || d.close[k] - rangeLow > 3 * d.atr4[k]) return@mapNotNull null
        EntrySignal(d.symbol, rangeLow, level + (level - rangeLow), 60, d.volume[b] / d.volSma4[b - 1])
    }
}

/** S4: Günlük güçlü trend (ADX ≥ 25, EMA20 > EMA50) içinde 4H RSI geri çekilmesi ve dönüş mumu; 2R hedef. */
class TrendPullbackReversal(private val rsiMax: Int) : V3Strategy {
    override val id = "S4"
    override val name = "S4 Güçlü trendde geri çekilme"
    override val option = "T=$rsiMax"

    override fun entries(u: Universe, t: Long, cfg: V3Config): List<EntrySignal> = u.symbols.values.mapNotNull { d ->
        val k = d.at(t)
        if (k < 30) return@mapNotNull null
        val i = d.day(t)
        if (i < 60) return@mapNotNull null
        val a = d.adx14d
        if (!(a.adx[i] >= 25 && a.plusDi[i] > a.minusDi[i] && d.dClose[i] > d.ema20d[i] && d.ema20d[i] > d.ema50d[i])) return@mapNotNull null
        if ((k - 6 until k).none { d.rsi4[it] <= rsiMax }) return@mapNotNull null
        if (!(d.close[k] > d.high[k - 1] && d.close[k] > d.open[k]) || d.atr4[k].isNaN()) return@mapNotNull null
        var lowest = Double.POSITIVE_INFINITY
        for (j in k - 11..k) lowest = min(lowest, d.low[j])
        val stop = lowest - 0.25 * d.atr4[k]
        EntrySignal(d.symbol, stop, d.close[k] + 2 * (d.close[k] - stop), 30, a.adx[i])
    }

    override fun exit(u: Universe, p: V3Position, t: Long, cfg: V3Config): String? {
        if (t % DAY_MS != 0L) return null
        val d = u.symbols.getValue(p.symbol)
        val i = d.day(t)
        return if (d.dClose[i] < d.ema50d[i]) "Günlük kapanış EMA50 altında" else null
    }
}

object V3Strategies {
    /** Strateji başına önceden kayıtlı üç seçenek. */
    val families: List<Pair<String, List<V3Strategy>>> = listOf(
        "S1" to listOf(30, 60, 90).map { RelativeStrength(it) },
        "S2" to listOf(20, 40, 60).map { DualMomentum(it) },
        "S3" to listOf(10, 20, 30).map { SqueezeBreakout(it) },
        "S4" to listOf(30, 35, 40).map { TrendPullbackReversal(it) },
    )
}
