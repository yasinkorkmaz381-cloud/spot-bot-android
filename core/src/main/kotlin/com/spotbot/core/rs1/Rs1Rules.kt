package com.spotbot.core.rs1

import kotlinx.serialization.Serializable

/** RS-1 uygunluk şartları (ön kayıttaki numaralarla). İlk başarısız şart değil, başarısız olanların hepsi raporlanır. */
enum class Rs1Condition(val title: String) {
    DATA("Güvenilir fiyat geçmişi yok (yeni listeleme, eksik gün ya da bayat veri)"),
    TREND("Kapanış ≤ EMA100"),
    RETURN_7("7 günlük getiri ≤ 0"),
    RETURN_30("30 günlük getiri ≤ 0"),
    RELATIVE_STRENGTH("BTC'ye göre 30 günlük avantaj < 3 puan"),
    VOLUME("30 günlük medyan hacim < 5M USDT"),
    RANGE("Son günün aralığı > 2,5 ATR"),
    SPREAD("Spread > %0,15"),
    DEPTH("Emir defteri derinliği yetersiz"),
}

@Serializable
data class BtcState(
    /** Yeni alıma izin var mı (iki koşul birlikte). */
    val open: Boolean,
    /** Veri eksik ya da bayat (filtre güvenli biçimde kapalı). */
    val dataMissing: Boolean,
    val close: Double = Double.NaN,
    val ema100: Double = Double.NaN,
    val return30Percent: Double = Double.NaN,
) {
    val reason: String? get() = when {
        dataMissing -> "BTC verisi eksik ya da güncel değil"
        !(close > ema100) && !(return30Percent > 0) -> "BTC EMA100 altında ve 30 günlük getirisi negatif"
        !(close > ema100) -> "BTC günlük kapanışı EMA100 altında"
        !(return30Percent > 0) -> "BTC 30 günlük getirisi pozitif değil"
        else -> null
    }
}

/** Bir coinin tamamlanmış gün t'deki veri şartları (1–4, 7, 8). Canlı şartlar (5, 6) ayrıca eklenir. */
@Serializable
data class CoinCheck(
    val symbol: String,
    val failed: List<Rs1Condition>,
    val close: Double = Double.NaN,
    val return7Percent: Double = Double.NaN,
    val return30Percent: Double = Double.NaN,
    /** 30 günlük getiri − BTC 30 günlük getiri (yüzde puan); BTC için 0. */
    val relativeStrength: Double = Double.NaN,
    val medianVolume: Double = 0.0,
    val rangeAtr: Double = Double.NaN,
    val atr: Double = Double.NaN,
    val ema100: Double = Double.NaN,
    val historyDays: Int = 0,
) {
    val ok: Boolean get() = failed.isEmpty()
}

object Rs1Rules {
    fun btc(btc: DailySeries?, t: Long, cfg: Rs1Config): BtcState {
        val i = btc?.lastCompleted(t) ?: -1
        if (btc == null || i < 0 || btc.openTime[i] + DAY_MS != t || !btc.reliable(i, cfg)) return BtcState(open = false, dataMissing = true)
        val r30 = btc.ret(i, cfg.returnDays)
        val ok = btc.close[i] > btc.emaTrend[i] && r30 > 0
        return BtcState(ok, false, btc.close[i], btc.emaTrend[i], r30 * 100)
    }

    /** Veri şartları. [btcReturn30] oran olarak (NaN ise göreceli güç şartı geçemez; BTC'nin kendisi muaf). */
    fun coin(s: DailySeries, t: Long, btcReturn30: Double, cfg: Rs1Config): CoinCheck {
        val i = s.lastCompleted(t)
        if (i < 0 || s.openTime[i] + DAY_MS != t || !s.reliable(i, cfg)) {
            return CoinCheck(s.symbol, listOf(Rs1Condition.DATA), historyDays = i + 1, medianVolume = if (i >= 0) s.medianVolume(i, cfg.volumeDays) else 0.0)
        }
        val r7 = s.ret(i, cfg.shortReturnDays)
        val r30 = s.ret(i, cfg.returnDays)
        val rs = if (s.symbol == BTC) 0.0 else (r30 - btcReturn30) * 100
        val vol = s.medianVolume(i, cfg.volumeDays)
        val rangeAtr = (s.high[i] - s.low[i]) / s.atr[i]
        val failed = buildList {
            if (!(s.close[i] > s.emaTrend[i])) add(Rs1Condition.TREND)
            if (!(r7 > 0)) add(Rs1Condition.RETURN_7)
            if (!(r30 > 0)) add(Rs1Condition.RETURN_30)
            if (s.symbol != BTC && !(rs >= cfg.minRelativeStrengthPoints)) add(Rs1Condition.RELATIVE_STRENGTH)
            if (!(vol >= cfg.minMedianVolume)) add(Rs1Condition.VOLUME)
            if (!(rangeAtr <= cfg.maxRangeAtr)) add(Rs1Condition.RANGE)
        }
        return CoinCheck(s.symbol, failed, s.close[i], r7 * 100, r30 * 100, rs, vol, rangeAtr, s.atr[i], s.emaTrend[i], i + 1)
    }

    /** Son [Rs1Config.emaExitDays] tamamlanmış günün hepsinde kapanış EMA50 altında mı? */
    fun belowExitEma(s: DailySeries, t: Long, cfg: Rs1Config): Boolean {
        val i = s.lastCompleted(t)
        if (i - cfg.emaExitDays + 1 < 0 || s.openTime[i] + DAY_MS != t) return false
        return (i - cfg.emaExitDays + 1..i).all { s.close[it] < s.emaExit[it] }
    }

    /** Uygun coinler 30 günlük getiriye göre (yüksekten düşüğe); eşitlikte sembol adına göre (belirlenimci). */
    fun rank(checks: Collection<CoinCheck>): List<CoinCheck> =
        checks.filter { it.ok }.sortedWith(compareByDescending<CoinCheck> { it.return30Percent }.thenBy { it.symbol })
}
