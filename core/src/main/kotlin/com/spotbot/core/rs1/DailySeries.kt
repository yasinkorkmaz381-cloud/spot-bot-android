package com.spotbot.core.rs1

import com.spotbot.core.model.Candle
import com.spotbot.core.ta.Indicators

/**
 * Bir paritenin UTC günlük mumları ve göstergeleri. Yalnız 24 saatlik (tam gün) mumlar alınır; göstergeler
 * tamamlanmış mumlardan hesaplanır, bir indeksteki değer yalnız o güne kadarki veriyi kullanır.
 */
class DailySeries(val symbol: String, candles: List<Candle>, cfg: Rs1Config = Rs1Config()) {
    val bars: List<Candle> = candles.filter { it.openTime % DAY_MS == 0L }.sortedBy { it.openTime }.distinctBy { it.openTime }
    val n = bars.size
    val openTime = LongArray(n) { bars[it].openTime }
    val open = DoubleArray(n) { bars[it].open }
    val high = DoubleArray(n) { bars[it].high }
    val low = DoubleArray(n) { bars[it].low }
    val close = DoubleArray(n) { bars[it].close }
    val quoteVolume = DoubleArray(n) { bars[it].quoteVolume }
    val emaTrend = Indicators.ema(close, cfg.emaTrend)
    val emaExit = Indicators.ema(close, cfg.emaExit)
    val atr = Indicators.atr(high, low, close, cfg.atrPeriod)
    val sma200 = Indicators.sma(close, 200)
    private val indexByDay = HashMap<Long, Int>(n * 2).also { m -> bars.forEachIndexed { i, c -> m[c.openTime / DAY_MS] = i } }

    /** Açılışı [dayStart] olan günün indeksi (yoksa -1). */
    fun at(dayStart: Long): Int = indexByDay[Math.floorDiv(dayStart, DAY_MS)] ?: -1

    /** Kapanışı ≤ [t] olan son günün indeksi (yoksa -1). */
    fun lastCompleted(t: Long): Int {
        var lo = 0
        var hi = n - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (openTime[mid] + DAY_MS <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun ret(i: Int, days: Int): Double = if (i - days < 0 || close[i - days] <= 0) Double.NaN else close[i] / close[i - days] - 1

    fun medianVolume(i: Int, days: Int): Double {
        if (i - days + 1 < 0) return 0.0
        val v = (i - days + 1..i).map { quoteVolume[it] }.sorted()
        return if (days % 2 == 0) (v[days / 2 - 1] + v[days / 2]) / 2 else v[days / 2]
    }

    /** Güvenilir geçmiş: yeterli gün, son [RsConfig.gapCheckDays] günde eksik gün yok, fiyatlar geçerli. */
    fun reliable(i: Int, cfg: Rs1Config): Boolean {
        if (i < 0 || i + 1 < cfg.minHistoryDays) return false
        val from = i - cfg.gapCheckDays + 1
        if (from < 0 || openTime[i] - openTime[from] != (cfg.gapCheckDays - 1) * DAY_MS) return false
        for (k in from..i) if (!(close[k] > 0 && high[k] >= low[k] && open[k] > 0)) return false
        return atr[i].isFinite() && emaTrend[i].isFinite() && emaExit[i].isFinite()
    }

    companion object {
        /** Gün içi kısmi mumdan (yalnız stop kontrolü için) ayrı tutulur: [DailySeries] yalnız tamamlanmış günleri içerir. */
        fun of(symbol: String, candles: List<Candle>, completedBy: Long, cfg: Rs1Config = Rs1Config()) =
            DailySeries(symbol, candles.filter { it.openTime + DAY_MS <= completedBy }, cfg)
    }
}
