package com.spotbot.core.analysis

import com.spotbot.core.strategy.StrategyId
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/** Piyasa rejimi (5 sınıf). Sınıflandırma kapanmış 4H mumlarla yapılır; gelecekteki veri kullanılmaz. */
@Serializable
enum class MarketRegime(val title: String) {
    STRONG_UP("Güçlü yükseliş"),
    WEAK_UP("Zayıf yükseliş"),
    RANGE("Yatay / belirsiz"),
    STRONG_DOWN("Güçlü düşüş"),
    EXTREME_VOLATILITY("Aşırı volatilite"),
}

/**
 * Rejim filtresinin çalışma biçimi. Varsayılan [OFF]: mevcut davranış değişmez, rejim yalnız bilgi olarak
 * kaydedilir. Diğer seçenekler backtest'te açık/kapalı karşılaştırılmadan etkinleştirilmemelidir.
 */
@Serializable
enum class RegimePolicy(val title: String, val description: String) {
    OFF("Kapalı", "Rejim yalnız bilgi olarak gösterilir; işlem kararını etkilemez."),
    PROTECTIVE("Koruyucu", "Güçlü düşüş ve aşırı volatilitede yeni alım yok (USDT'de beklenir)."),
    FULL("Tam", "Güçlü yükselişte A+B, zayıf yükselişte yalnız B; yatay, düşüş ve aşırı volatilitede yeni alım yok."),
    ;

    /** Bu rejimde stratejiye yeni alım izni var mı. */
    fun allows(regime: MarketRegime?, strategy: StrategyId): Boolean = when (this) {
        OFF -> true
        PROTECTIVE -> regime != MarketRegime.STRONG_DOWN && regime != MarketRegime.EXTREME_VOLATILITY
        FULL -> when (regime) {
            MarketRegime.STRONG_UP -> strategy == StrategyId.BREAKOUT_RETEST || strategy == StrategyId.TREND_PULLBACK
            MarketRegime.WEAK_UP -> strategy == StrategyId.TREND_PULLBACK
            else -> false
        }
    }
}

/**
 * 5 sınıflı rejim motoru. Eşikler ölçümden **önce** belirlendi ve sonuçlara göre ayarlanmadı:
 * - Aşırı volatilite: 4H ATR/kapanış oranı son [VOL_LOOKBACK] mumun medyanının [VOL_MULTIPLE] katı ya da üstü,
 *   veya son 4H mumun gerçek aralığı önceki ATR'nin [SHOCK_ATR] katı ya da üstü.
 * - Güçlü yükseliş: kapanış > EMA21 > EMA55, ADX ≥ [ADX_STRONG] ve +DI > −DI.
 * - Zayıf yükseliş: kapanış > EMA55 ve EMA21 > EMA55 (güçlü koşullar sağlanmıyor).
 * - Güçlü düşüş: kapanış < EMA55, EMA21 < EMA55, ADX ≥ [ADX_STRONG] ve −DI > +DI.
 * - Yatay / belirsiz: geri kalan her durum (zayıf düşüş ve karışık sinyaller dahil).
 */
object RegimeClassifier {
    const val ADX_STRONG = 25.0
    const val VOL_LOOKBACK = 180
    const val VOL_MULTIPLE = 2.0
    const val SHOCK_ATR = 3.0

    fun classify(h4: TimeframeAnalysis?): MarketRegime? {
        if (h4 == null || !h4.isValid) return null
        val s = h4.series
        val i = h4.endIndex
        val atr = s.atr[i]
        val close = s.close[i]
        if (atr.isNaN() || close <= 0 || s.emaSlow[i].isNaN() || s.adx.adx[i].isNaN()) return null
        val atrPercent = atr / close
        val history = (maxOf(0, i - VOL_LOOKBACK) until i).mapNotNull { k -> s.atr[k].takeIf { !it.isNaN() && s.close[k] > 0 }?.div(s.close[k]) }
        if (history.size >= VOL_LOOKBACK / 2) {
            val median = history.sorted()[history.size / 2]
            val previousAtr = s.atr[i - 1]
            val trueRange = max(s.high[i] - s.low[i], max(abs(s.high[i] - s.close[i - 1]), abs(s.low[i] - s.close[i - 1])))
            if (atrPercent >= VOL_MULTIPLE * median || (!previousAtr.isNaN() && trueRange >= SHOCK_ATR * previousAtr)) {
                return MarketRegime.EXTREME_VOLATILITY
            }
        }
        val emaMid = s.emaMid[i]
        val emaSlow = s.emaSlow[i]
        val adx = s.adx.adx[i]
        val plusDi = s.adx.plusDi[i]
        val minusDi = s.adx.minusDi[i]
        return when {
            close > emaMid && emaMid > emaSlow && adx >= ADX_STRONG && plusDi > minusDi -> MarketRegime.STRONG_UP
            close > emaSlow && emaMid > emaSlow -> MarketRegime.WEAK_UP
            close < emaSlow && emaMid < emaSlow && adx >= ADX_STRONG && minusDi > plusDi -> MarketRegime.STRONG_DOWN
            else -> MarketRegime.RANGE
        }
    }
}
