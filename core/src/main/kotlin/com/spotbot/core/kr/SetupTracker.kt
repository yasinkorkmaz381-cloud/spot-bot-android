package com.spotbot.core.kr

import com.spotbot.core.kr.KrConfig.Companion.H1
import com.spotbot.core.kr.KrConfig.Companion.H4
import com.spotbot.core.kr.KrConfig.Companion.M15
import com.spotbot.core.model.Candle
import com.spotbot.core.ta.Indicators
import kotlinx.serialization.Serializable

/**
 * Y1: 4H yön. Yalnız kapanmış 4H mumlar kullanılır; [at] verilen anda kapanmış son 4H muma bakar.
 * `yükseliş = kapanış > EMA50 ve EMA50 > 6 mum önceki EMA50`.
 */
class Trend4h(candles: List<Candle>, private val cfg: KrConfig) {
    private val bars = candles.sortedBy { it.openTime }.distinctBy { it.openTime }
    private val closeTimes = LongArray(bars.size) { bars[it].openTime + H4 }
    private val ema = Indicators.ema(DoubleArray(bars.size) { bars[it].close }, cfg.emaTrend4h)

    /** [time] anında kapanmış son 4H muma göre yön; veri yetersizse null. */
    fun at(time: Long): Boolean? {
        val i = lastClosedIndex(time)
        if (i < cfg.emaSlopeBars) return null
        val now = ema[i]
        val before = ema[i - cfg.emaSlopeBars]
        if (now.isNaN() || before.isNaN()) return null
        return bars[i].close > now && now > before
    }

    fun emaAt(time: Long): Double = lastClosedIndex(time).let { if (it < 0) Double.NaN else ema[it] }

    /** [time] tam bir 4H kapanışıysa true (o anda yeni bir 4H mum kapandı). */
    fun closesAt(time: Long): Boolean = time % H4 == 0L && lastClosedIndex(time).let { it >= 0 && closeTimes[it] == time }

    val lastCloseTime: Long get() = closeTimes.lastOrNull() ?: 0L

    private fun lastClosedIndex(time: Long): Int {
        var lo = 0
        var hi = closeTimes.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (closeTimes[mid] <= time) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        /** 15M mumlardan UTC hizalı 4H mumlar (16 mumun hepsi yoksa o 4H mum üretilmez). */
        fun aggregate(m15: List<Candle>): List<Candle> = Bars.aggregate(m15, H4)
    }
}

object Bars {
    /** 15M mumları [period] süreli, UTC hizalı mumlara birleştirir. Grubu eksik olan mum üretilmez. */
    fun aggregate(m15: List<Candle>, period: Long): List<Candle> {
        val need = (period / M15).toInt()
        val out = ArrayList<Candle>(m15.size / need + 1)
        var i = 0
        while (i < m15.size) {
            val start = m15[i].openTime - Math.floorMod(m15[i].openTime, period)
            var j = i
            while (j < m15.size && m15[j].openTime < start + period) j++
            val group = m15.subList(i, j)
            if (group.size == need && group.first().openTime == start) {
                out += Candle(
                    start, group.first().open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close,
                    group.sumOf { it.volume }, start + period, group.sumOf { it.quoteVolume },
                )
            }
            i = j
        }
        return out
    }
}

/** Önceden belirlenmiş 1H direnç: [price] = fraktal tepenin yükseği, [knownAt] = sağdaki son mumun kapanışı. */
@Serializable
data class Level(val price: Double, val swingTime: Long, val knownAt: Long)

/** Retest onaylandığında üretilen giriş planı (dolum öncesi). Hedef ve net R:R dolum fiyatıyla yeniden hesaplanır. */
@Serializable
data class SetupPlan(
    val id: String,
    val symbol: String,
    /** Retest mumunun kapanışı (karar anı). */
    val decisionTime: Long,
    val breakoutTime: Long,
    val level: Double,
    val atr15: Double,
    val atr1h: Double,
    val stop: Double,
    val invalidation: Double,
    val maxEntry: Double,
    val retestClose: Double,
    /** Karar anında bilinen, seviyenin üstündeki kırılmamış 1H dirençler (küçükten büyüğe). */
    val levelsAbove: List<Double>,
    val quoteVolume24h: Double,
    val return24hPercent: Double,
    val cleanSince: Long,
)

sealed interface TrackerEvent {
    val time: Long

    data class BreakoutAccepted(override val time: Long, val level: Double, val volumeRatio: Double) : TrackerEvent
    data class BreakoutRejected(override val time: Long, val level: Double, val reason: Reason) : TrackerEvent
    data class RetestConfirmed(override val time: Long, val plan: SetupPlan) : TrackerEvent
    data class SetupEnded(override val time: Long, val level: Double, val reason: Reason) : TrackerEvent

    enum class Reason(val title: String) {
        LOW_VOLUME("Kırılım hacmi düşük"),
        TREND_DOWN("4H yön yükselişte değil"),
        NOT_READY("Gösterge geçmişi yetersiz"),
        CLOSED_BELOW("Retestten önce seviye altında kapanış"),
        EXPIRED("Retest süresinde gelmedi"),
        TREND_LOST("Beklerken 4H yön bozuldu"),
        REPLACED("Daha yeni bir kırılım geldi"),
        DATA_GAP("Veri boşluğu"),
    }
}

enum class SetupStage(val title: String) {
    WATCHING("İzleniyor"),
    AWAITING_RETEST("Retest bekleniyor"),
    ENTRY_READY("Giriş hazır"),
    NOT_SUITABLE("Uygun değil"),
}

/**
 * Tek coin için Kırılım–Retest durum makinesi. Yalnız **kapanmış** 15M mumlarla, sırayla beslenir; her şeyi o ana
 * kadar bilinen verilerden üretir (gelecek sızıntısı yok). Paper trading aynı sınıfı son ~500 mumu yeniden oynatarak
 * kullanır; backtest bütün geçmişi bir kez oynatır. Kurallar: docs/KR-ONKAYIT.md (S1–S3, K1–K4, R1–R3).
 */
class SetupTracker(val symbol: String, private val cfg: KrConfig, private val trend: Trend4h?) {
    private val recent = ArrayDeque<Candle>()
    private val hours = ArrayList<Candle>()
    private val hourBuffer = ArrayList<Candle>()
    private val levels = ArrayList<Level>()
    private var pending: Pending? = null

    private var atr15 = Double.NaN
    private var atr1h = Double.NaN
    private var trSum15 = 0.0
    private var trCount15 = 0
    private var trSum1h = 0.0
    private var trCount1h = 0

    var barCount = 0; private set
    var lastBar: Candle? = null; private set
    /** Son veri boşluğundan sonraki ilk mumun açılışı (boşluk yoksa ilk mumun açılışı). */
    var cleanSince = 0L; private set
    var lastEvents: List<TrackerEvent> = emptyList(); private set

    private class Pending(val level: Level, val breakout: Candle, val atr15: Double, var barsSeen: Int = 0, var minLow: Double = Double.MAX_VALUE)

    val currentAtr15: Double get() = atr15
    val currentAtr1h: Double get() = atr1h
    val knownLevels: List<Level> get() = levels.toList()
    val pendingLevel: Double? get() = pending?.level?.price
    val pendingBarsSeen: Int get() = pending?.barsSeen ?: 0
    fun trendAt(time: Long): Boolean? = trend?.at(time)

    /** Son 24 saatlik (96 mum) MEXC işlem hacmi, USDT. */
    val quoteVolume24h: Double get() = recent.takeLast(96).sumOf { it.quoteVolume }

    /** [bars] mum önceki kapanışa göre getiri (yüzde); geçmiş yetmezse NaN. */
    fun returnPercent(bars: Int): Double {
        if (recent.size <= bars) return Double.NaN
        val now = recent.last().close
        val then = recent[recent.size - 1 - bars].close
        return (now / then - 1) * 100
    }

    fun onBar(c: Candle): List<TrackerEvent> {
        val prev = lastBar
        if (prev != null && c.openTime <= prev.openTime) return emptyList<TrackerEvent>().also { lastEvents = it }
        val events = ArrayList<TrackerEvent>(2)
        if (prev == null || c.openTime != prev.openTime + M15) {
            cleanSince = c.openTime
            hourBuffer.clear()
            pending?.let { events += TrackerEvent.SetupEnded(c.closeTime, it.level.price, TrackerEvent.Reason.DATA_GAP) }
            pending = null
        }
        // Hacim ortalaması: kırılım mumu hariç önceki mumlar.
        val volumeAverage = if (recent.size >= cfg.volumeAverageBars) recent.takeLast(cfg.volumeAverageBars).sumOf { it.volume } / cfg.volumeAverageBars else Double.NaN
        updateAtr15(c, if (prev != null && c.openTime == prev.openTime + M15) prev.close else Double.NaN)
        recent.addLast(c)
        if (recent.size > RECENT_KEEP) recent.removeFirst()
        lastBar = c
        barCount++

        val newLevels = aggregateHour(c)
        val trendOk = trend?.at(c.closeTime)

        // R1–R3: bekleyen kurulum.
        pending?.let { p ->
            p.barsSeen++
            p.minLow = minOf(p.minLow, c.low)
            val level = p.level.price
            when {
                trend != null && trend.closesAt(c.closeTime) && trendOk != true -> {
                    events += TrackerEvent.SetupEnded(c.closeTime, level, TrackerEvent.Reason.TREND_LOST); pending = null
                }
                c.low <= level + cfg.retestToleranceAtr * p.atr15 && c.close > level && c.close >= c.open &&
                    c.close - c.low >= cfg.retestMinCloseFraction * (c.high - c.low) &&
                    c.close >= level + cfg.retestMinCloseAboveAtr * p.atr15 -> {
                    events += TrackerEvent.RetestConfirmed(c.closeTime, plan(p, c)); pending = null
                }
                c.close <= level -> {
                    events += TrackerEvent.SetupEnded(c.closeTime, level, TrackerEvent.Reason.CLOSED_BELOW); pending = null
                }
                p.barsSeen >= cfg.retestBars -> {
                    events += TrackerEvent.SetupEnded(c.closeTime, level, TrackerEvent.Reason.EXPIRED); pending = null
                }
            }
        }

        // K1–K4: kırılım. Listedeki bütün seviyeler bu mum açılmadan önce biliniyordu (S3).
        val crossed = levels.filter { c.close > it.price }
        if (crossed.isNotEmpty()) {
            levels.removeAll(crossed.toSet())
            val level = crossed.maxBy { it.price }
            val ratio = c.volume / volumeAverage
            val reason = when {
                atr15.isNaN() || volumeAverage.isNaN() || trendOk == null -> TrackerEvent.Reason.NOT_READY
                trendOk != true -> TrackerEvent.Reason.TREND_DOWN
                !(ratio >= cfg.breakoutVolumeMultiple) -> TrackerEvent.Reason.LOW_VOLUME
                else -> null
            }
            if (reason == null) {
                pending?.let { events += TrackerEvent.SetupEnded(c.closeTime, it.level.price, TrackerEvent.Reason.REPLACED) }
                pending = Pending(level, c, atr15)
                events += TrackerEvent.BreakoutAccepted(c.closeTime, level.price, ratio)
            } else {
                events += TrackerEvent.BreakoutRejected(c.closeTime, level.price, reason)
            }
        }

        levels += newLevels
        val oldest = c.closeTime - cfg.levelLookbackHours * H1
        levels.removeAll { it.swingTime < oldest }
        lastEvents = events
        return events
    }

    /** Son mumdan sonraki durum (Fırsatlar ekranı). */
    fun stage(): Pair<SetupStage, String> {
        val last = lastBar ?: return SetupStage.NOT_SUITABLE to "Veri yok"
        if (lastEvents.any { it is TrackerEvent.RetestConfirmed }) return SetupStage.ENTRY_READY to "Retest onaylandı"
        pending?.let { return SetupStage.AWAITING_RETEST to "Seviye ${fmt(it.level.price)} kırıldı; retest bekleniyor (${it.barsSeen}/${cfg.retestBars})" }
        val t = trendAt(last.closeTime)
        if (barCount < cfg.minBars15 || atr15.isNaN() || t == null) return SetupStage.NOT_SUITABLE to "Gösterge geçmişi yetersiz"
        if (!t) return SetupStage.NOT_SUITABLE to "4H yön yükselişte değil"
        val nearest = levels.minByOrNull { it.price } ?: return SetupStage.NOT_SUITABLE to "Önceden belirlenmiş 1H direnç yok"
        val distance = (nearest.price / last.close - 1) * 100
        return SetupStage.WATCHING to "Direnç ${fmt(nearest.price)} (%${"%.1f".format(distance)} yukarıda)"
    }

    private fun plan(p: Pending, c: Candle): SetupPlan {
        val level = p.level.price
        return SetupPlan(
            id = "$symbol@${p.breakout.openTime}@${p.level.swingTime}",
            symbol = symbol,
            decisionTime = c.closeTime,
            breakoutTime = p.breakout.openTime,
            level = level,
            atr15 = p.atr15,
            atr1h = atr1h,
            stop = p.minLow - cfg.stopBufferAtr * p.atr15,
            invalidation = level - cfg.invalidationAtr * p.atr15,
            maxEntry = level + cfg.maxChaseAtr * p.atr15,
            retestClose = c.close,
            levelsAbove = levels.map { it.price }.filter { it > level }.sorted(),
            quoteVolume24h = quoteVolume24h,
            return24hPercent = returnPercent(96),
            cleanSince = cleanSince,
        )
    }

    private fun updateAtr15(c: Candle, prevClose: Double) {
        val tr = trueRange(c, prevClose)
        if (atr15.isNaN()) {
            trSum15 += tr; trCount15++
            if (trCount15 == cfg.atrPeriod) atr15 = trSum15 / cfg.atrPeriod
        } else {
            atr15 = (atr15 * (cfg.atrPeriod - 1) + tr) / cfg.atrPeriod
        }
    }

    /** 15M → 1H birleştirme; yeni 1H mum kapandıysa fraktal tepeyi denetler ve yeni seviyeleri döndürür (S1). */
    private fun aggregateHour(c: Candle): List<Level> {
        if (c.openTime % H1 == 0L) hourBuffer.clear()
        hourBuffer += c
        if (c.closeTime % H1 != 0L) return emptyList()
        val group = hourBuffer.toList()
        hourBuffer.clear()
        val start = c.closeTime - H1
        if (group.size != 4 || group.first().openTime != start) return emptyList()
        val h = Candle(start, group.first().open, group.maxOf { it.high }, group.minOf { it.low }, group.last().close, group.sumOf { it.volume }, c.closeTime, group.sumOf { it.quoteVolume })
        val prevHour = hours.lastOrNull()
        hours += h
        if (hours.size > HOURS_KEEP) hours.removeAt(0)
        val tr = trueRange(h, if (prevHour != null && prevHour.closeTime == h.openTime) prevHour.close else Double.NaN)
        if (atr1h.isNaN()) {
            trSum1h += tr; trCount1h++
            if (trCount1h == cfg.atrPeriod) atr1h = trSum1h / cfg.atrPeriod
        } else {
            atr1h = (atr1h * (cfg.atrPeriod - 1) + tr) / cfg.atrPeriod
        }
        val s = cfg.swingSide
        val n = hours.size
        val j = n - 1 - s
        if (j - s < 0) return emptyList()
        val window = hours.subList(j - s, n)
        for (k in 1 until window.size) if (window[k].openTime != window[k - 1].openTime + H1) return emptyList()
        val top = hours[j].high
        val isSwing = window.withIndex().all { (k, b) -> k == s || b.high < top }
        return if (isSwing) listOf(Level(top, hours[j].openTime, c.closeTime)) else emptyList()
    }

    private fun trueRange(c: Candle, prevClose: Double): Double =
        if (prevClose.isNaN()) c.high - c.low else maxOf(c.high - c.low, kotlin.math.abs(c.high - prevClose), kotlin.math.abs(c.low - prevClose))

    companion object {
        private const val RECENT_KEEP = 200
        private const val HOURS_KEEP = 120
        fun fmt(p: Double): String = when {
            p >= 1000 -> "%.1f".format(p)
            p >= 1 -> "%.4f".format(p)
            else -> "%.6g".format(p)
        }
    }
}
