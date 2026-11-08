package com.spotbot.core.scanner

import com.spotbot.core.analysis.MultiTimeframe
import com.spotbot.core.analysis.Regime
import com.spotbot.core.analysis.Trend
import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Tarama parametreleri. Eşikler test başlangıç değerleridir. */
@Serializable
data class ScannerConfig(
    val watchlist: List<String> = listOf("BTC", "ETH", "SOL", "NEAR", "ZEC", "INJ", "AVAX"),
    val quoteAsset: String = "USDT",
    /** İzleme listesine eklenecek ek coin sayısı (tasarım notu: 10–20). */
    val extraCoins: Int = 15,
    /**
     * Asgari 24s USDT hacmi. Kasım 2026'da MEXC'de yalnız 13 USDT paritesi 2M, 29'u 1M üzerindeydi;
     * 10–20 ek coin hedefi için varsayılan 1M. Spread ve derinlik filtreleri kaliteyi ayrıca denetler.
     */
    val minQuoteVolume24h: Double = 1_000_000.0,
    val maxSpreadPercent: Double = 0.20,
    val depthRangePercent: Double = 1.0,
    val minDepthUsdt: Double = 10_000.0,
    val maxAbsChange24hPercent: Double = 25.0,
    val maxAverageWickRatio: Double = 0.65,
    val maxVolumeSpikeRatio: Double = 8.0,
    /** Ayrıntılı incelemeye alınacak en iyi aday sayısı (tasarım notu: 2–4). */
    val topCandidates: Int = 4,
    val btcCrashPercent4h: Double = 3.0,
    val excludedBases: List<String> = listOf(
        "USDC", "USDT", "FDUSD", "TUSD", "DAI", "USDE", "USD1", "PYUSD", "BUSD", "USDP", "EUR", "EURC", "USDD", "XAUT", "PAXG",
    ),
)

enum class CoinStatus { ELIMINATED, FLAGGED, PASSED, CANDIDATE }

@Serializable
data class CoinScan(
    val symbol: String,
    val price: Double,
    val change24hPercent: Double,
    val quoteVolume24h: Double,
    val spreadPercent: Double?,
    val depthUsdt: Double?,
    val status: CoinStatus,
    val reasons: List<String>,
    val preScore: Int,
    val h4Regime: Regime?,
    val h1Trend: Trend?,
    val relativeStrength7dPercent: Double?,
)

@Serializable
data class MarketState(
    val btcRegime: Regime?,
    val btcChange4hPercent: Double?,
    val allowsLongs: Boolean,
    val reasons: List<String>,
    /** İzlenen coinlerden 4H kapanışı EMA55 üzerinde olanların oranı (%). */
    val breadthPercent: Double?,
)

@Serializable
data class ScanReport(
    val time: Long,
    val market: MarketState,
    val coins: List<CoinScan>,
    val candidates: List<String>,
)

/** Taramaya giren bir paritenin anlık verileri. */
class CoinMarketData(
    val info: SymbolInfo,
    val ticker: Ticker24h?,
    val book: BookTicker?,
    val orderBook: OrderBook?,
    val mtf: MultiTimeframe?,
)

object Scanner {

    private val leveragedToken = Regex("^[A-Z0-9]+(\\d+[LS]|UP|DOWN|BULL|BEAR)$")
    private val plainAsset = Regex("^[A-Z0-9]+$")

    /** API ile işlem yapılabilir, stabil coin ya da kaldıraçlı token olmayan ve risk etiketi taşımayan pariteler. */
    fun isEligible(info: SymbolInfo, config: ScannerConfig): Boolean =
        info.quoteAsset == config.quoteAsset && info.isOnline && info.isSpotTradingAllowed && info.canTradeBothSides &&
            "MARKET" in info.orderTypes && !info.riskFlagged && info.baseAsset !in config.excludedBases &&
            !leveragedToken.matches(info.baseAsset) && plainAsset.matches(info.baseAsset)

    /** İzleme listesi + 24 saatlik USDT hacmine göre en likit [ScannerConfig.extraCoins] uygun parite. */
    fun selectUniverse(symbols: List<SymbolInfo>, tickers: List<Ticker24h>, config: ScannerConfig): List<String> {
        val bySymbol = symbols.associateBy { it.symbol }
        val watch = config.watchlist.map { it + config.quoteAsset }.filter { bySymbol[it]?.let { info -> isEligible(info, config) } == true }
        val extras = tickers
            .filter { it.symbol !in watch && bySymbol[it.symbol]?.let { info -> isEligible(info, config) } == true }
            .filter { it.quoteVolume >= config.minQuoteVolume24h }
            .sortedByDescending { it.quoteVolume }
            .take(config.extraCoins)
            .map { it.symbol }
        return watch + extras
    }

    fun marketState(btc: MultiTimeframe?, coins: List<CoinMarketData>, config: ScannerConfig): MarketState {
        val reasons = mutableListOf<String>()
        val regime = btc?.h4?.regime
        val change4h = btc?.h1?.let { h1 ->
            val s = h1.series
            val i = h1.endIndex
            if (i >= 4) (s.close[i] / s.close[i - 4] - 1) * 100 else null
        }
        var allows = true
        if (btc == null || !btc.isValid) {
            allows = false
            reasons += "BTC verisi yetersiz"
        } else {
            if (regime == Regime.TREND_DOWN) {
                allows = false
                reasons += "BTC 4H düşüş trendinde: yeni alım yok"
            }
            if (change4h != null && change4h <= -config.btcCrashPercent4h) {
                allows = false
                reasons += "BTC son 4 saatte %%%.1f düştü".format(change4h)
            }
            if (allows) reasons += "BTC 4H rejimi: $regime"
        }
        val valid = coins.mapNotNull { it.mtf?.h4?.takeIf { h4 -> h4.isValid } }
        val breadth = if (valid.isEmpty()) null else valid.count { it.indicators.close > it.indicators.emaSlow } * 100.0 / valid.size
        return MarketState(regime, change4h, allows, reasons, breadth)
    }

    fun scan(coins: List<CoinMarketData>, btc: MultiTimeframe?, config: ScannerConfig, now: Long): ScanReport {
        val market = marketState(btc, coins, config)
        val btcStrength = btc?.let { strength7d(it) }
        val scans = coins.map { coin -> scanCoin(coin, btcStrength, config) }
        val candidates = scans
            .filter { it.status == CoinStatus.PASSED && it.preScore > 0 }
            .sortedByDescending { it.preScore }
            .take(config.topCandidates)
            .map { it.symbol }
            .toSet()
        val final = scans.map { if (it.symbol in candidates) it.copy(status = CoinStatus.CANDIDATE) else it }
        val ordered = final.sortedWith(compareByDescending<CoinScan> { it.status.ordinal }.thenByDescending { it.preScore })
        return ScanReport(now, market, ordered, ordered.filter { it.status == CoinStatus.CANDIDATE }.map { it.symbol })
    }

    private fun scanCoin(coin: CoinMarketData, btcStrength: Double?, config: ScannerConfig): CoinScan {
        val eliminations = mutableListOf<String>()
        val flags = mutableListOf<String>()
        val t = coin.ticker
        if (!isEligible(coin.info, config)) eliminations += "API ile işlem yapılamıyor ya da uygun değil"
        if (t == null || t.quoteVolume < config.minQuoteVolume24h) {
            eliminations += "24s hacim düşük (%s USDT)".format(t?.quoteVolume?.let { "%,.0f".format(it) } ?: "-")
        }
        val spread = coin.book?.spreadPercent
        if (spread == null || spread.isNaN() || spread > config.maxSpreadPercent) {
            eliminations += "Spread yüksek (%s)".format(spread?.let { "%%%.3f".format(it) } ?: "veri yok")
        }
        val depth = coin.orderBook?.depthWithin(config.depthRangePercent)?.let { minOf(it.first, it.second) }
        if (depth != null && depth < config.minDepthUsdt) eliminations += "Emir defteri sığ (±%%%.0f: %,.0f USDT)".format(config.depthRangePercent, depth)
        val mtf = coin.mtf
        if (mtf == null || !mtf.isValid) eliminations += "Analiz için yeterli mum verisi yok"

        if (t != null && abs(t.changePercent) > config.maxAbsChange24hPercent) flags += "24s değişim aşırı (%%%.1f)".format(t.changePercent)
        if (mtf != null && mtf.isValid) {
            val h1 = mtf.h1
            val s = h1.series
            val from = maxOf(0, h1.endIndex - 23)
            val wick = (from..h1.endIndex).map { i -> val r = s.high[i] - s.low[i]; if (r > 0) (r - abs(s.close[i] - s.open[i])) / r else 0.0 }.average()
            if (wick > config.maxAverageWickRatio) flags += "Fitil oranı yüksek (%.2f): fiyat istikrarsız".format(wick)
            val avg = h1.averageVolume(24)
            val recentMax = (maxOf(0, h1.endIndex - 2)..h1.endIndex).maxOf { s.volume[it] }
            if (avg > 0 && recentMax / avg > config.maxVolumeSpikeRatio) flags += "Ani hacim patlaması (%.0fx)".format(recentMax / avg)
        }

        val strength = mtf?.let { strength7d(it) }
        val relative = if (strength != null && btcStrength != null) strength - btcStrength else null
        val status = when {
            eliminations.isNotEmpty() -> CoinStatus.ELIMINATED
            flags.isNotEmpty() -> CoinStatus.FLAGGED
            else -> CoinStatus.PASSED
        }
        val score = if (status == CoinStatus.PASSED) preScore(mtf!!, relative) else 0
        return CoinScan(
            symbol = coin.info.symbol,
            price = t?.lastPrice ?: coin.book?.mid ?: 0.0,
            change24hPercent = t?.changePercent ?: 0.0,
            quoteVolume24h = t?.quoteVolume ?: 0.0,
            spreadPercent = spread,
            depthUsdt = depth,
            status = status,
            reasons = eliminations + flags,
            preScore = score,
            h4Regime = mtf?.takeIf { it.isValid }?.h4?.regime,
            h1Trend = mtf?.takeIf { it.isValid }?.h1?.structure?.trend,
            relativeStrength7dPercent = relative,
        )
    }

    /** Ön puan: ayrıntılı incelemeye hangi coinlerin alınacağını sıralar (işlem kararı değildir). */
    fun preScore(mtf: MultiTimeframe, relativeStrength: Double?): Int {
        var score = when (mtf.h4.regime) {
            Regime.TREND_UP -> 3
            Regime.RANGE -> 1
            Regime.UNCLEAR -> 0
            Regime.TREND_DOWN -> -3
        }
        if (mtf.h4.indicators.adx >= 25) score += 1
        if (mtf.h1.structure.trend == Trend.UP) score += 2
        if (mtf.h1.structure.trend == Trend.DOWN) score -= 2
        if (mtf.h1.indicators.close > mtf.h1.indicators.emaSlow) score += 1
        if (relativeStrength != null && relativeStrength > 0) score += 1
        return score
    }

    /** 4H mumlarla son 7 günlük getiri (%). */
    fun strength7d(mtf: MultiTimeframe): Double? {
        val h4 = mtf.h4
        val back = 42
        if (h4.endIndex < back) return null
        return (h4.series.close[h4.endIndex] / h4.series.close[h4.endIndex - back] - 1) * 100
    }
}
