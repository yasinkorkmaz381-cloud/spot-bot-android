package com.spotbot.core.trading

import com.spotbot.core.scanner.MarketBlockKind
import com.spotbot.core.scanner.MarketExplanation
import com.spotbot.core.scanner.MarketState
import kotlinx.serialization.Serializable

/** Son üretilen alım sinyali (strateji kurulumu) ve işleme dönüşüp dönüşmediği. */
@Serializable
data class SignalInfo(
    val time: Long,
    val symbol: String,
    val strategy: String,
    val opened: Boolean,
    /** İşleme dönüşmediyse nedeni (geçmeyen zorunlu kurallar ya da emir hatası). */
    val blockedBy: List<String>,
)

/** Bir taramanın hunisi: kaç coin hangi aşamada kaldı. Sayılar karar günlüğünden hesaplanır (tahmin değildir). */
@Serializable
data class ScanDiagnostics(
    val boundary: Long,
    val scanned: Int,
    /** Likidite, spread, derinlik ya da işlem izni nedeniyle taramada elenen. */
    val eliminated: Int,
    val flagged: Int,
    /** Filtreyi geçti ama ön puanı en iyi adaylar arasına girmedi (trend/güç zayıf). */
    val notSelected: Int,
    /** Ayrıntılı incelendi, strateji kurulumu oluşmadı (giriş koşulları/teyit bekleniyor). */
    val noSetup: Int,
    /** Kurulum bulundu ama zorunlu kurallar reddetti. */
    val rejected: Int,
    val staleData: Int,
    /** Kuralları geçen (işlem açılan ya da açılabilecek) coin sayısı. */
    val eligible: Int,
    val opened: Int,
    val marketAllowsLongs: Boolean,
    val marketBlockKind: MarketBlockKind?,
    /** En sık reddedilme/elenme nedenleri (neden → coin sayısı). */
    val topReasons: List<Pair<String, Int>>,
    /** Bu taramada neden işlem açılmadığının kısa açıklaması (açıldıysa null). */
    val shortReason: String?,
) {
    companion object {
        private fun normalize(reason: String) = reason.substringBefore(" (").substringBefore(":").trim()

        fun of(log: ScanLog, market: MarketState? = null): ScanDiagnostics {
            fun count(vararg o: CoinOutcome) = log.coins.count { it.outcome in o }
            val reasons = HashMap<String, Int>()
            for (coin in log.coins) {
                val own: List<String> = when (coin.outcome) {
                    CoinOutcome.ELIMINATED, CoinOutcome.FLAGGED -> coin.reasons.map(::normalize)
                    CoinOutcome.NOT_SELECTED -> listOf("Ön puan yetersiz (trend ya da göreceli güç zayıf)")
                    CoinOutcome.NO_SETUP -> listOf("Strateji kurulumu oluşmadı (giriş koşulları bekleniyor)")
                    CoinOutcome.REJECTED -> coin.strategies.flatMap { s -> s.rules.filter { it.mandatory && !it.passed }.map { "${it.id} ${it.title}" } }
                        .ifEmpty { coin.reasons.map(::normalize) }
                    CoinOutcome.STALE_DATA -> listOf("Veri güncel değil")
                    CoinOutcome.ENTRY_FAILED -> coin.reasons.map(::normalize).ifEmpty { listOf("Emir gerçekleşmedi") }
                    CoinOutcome.PAUSED -> listOf("Yeni işlemler duraklatılmış")
                    CoinOutcome.OBSERVE_ONLY -> listOf("Başlangıç taraması (yalnız gözlem)")
                    CoinOutcome.OPENED, CoinOutcome.ORDER_PLACED -> emptyList()
                }
                own.distinct().forEach { reasons.merge(it, 1, Int::plus) }
            }
            val opened = count(CoinOutcome.OPENED, CoinOutcome.ORDER_PLACED)
            val eligible = opened + count(CoinOutcome.OBSERVE_ONLY, CoinOutcome.PAUSED, CoinOutcome.ENTRY_FAILED)
            val noSetup = count(CoinOutcome.NO_SETUP)
            val rejected = count(CoinOutcome.REJECTED)
            val stale = count(CoinOutcome.STALE_DATA)
            val top = reasons.entries.sortedByDescending { it.value }.map { it.key to it.value }
            val short = when {
                opened > 0 -> null
                !log.marketAllowsLongs -> MarketExplanation.short(market) ?: "BTC / piyasa filtresi alıma izin vermiyor."
                log.entriesPaused -> "Yeni işlemler duraklatılmış."
                count(CoinOutcome.ENTRY_FAILED) > 0 -> "Sinyal oluştu ama emir gerçekleşmedi: ${top.firstOrNull()?.first ?: ""}"
                count(CoinOutcome.OBSERVE_ONLY) > 0 -> "Başlangıç taraması: işlemler bir sonraki 15 dakikalık kapanıştan itibaren açılır."
                rejected > 0 -> "Kurulum bulundu ama kurallar reddetti: ${top.firstOrNull { it.first.startsWith("Z") || it.first.startsWith("P") }?.first ?: top.firstOrNull()?.first ?: ""}"
                stale > 0 && noSetup == 0 -> "MEXC verileri güncellenemedi."
                noSetup > 0 -> "15M giriş teyidi bekleniyor: $noSetup adayda kurulum henüz oluşmadı."
                log.coins.isEmpty() -> "Taranacak coin yok."
                else -> "Uygun aday yok: ${count(CoinOutcome.ELIMINATED, CoinOutcome.FLAGGED)} coin likidite/spread nedeniyle elendi, ${count(CoinOutcome.NOT_SELECTED)} coinde trend zayıf."
            }
            return ScanDiagnostics(
                boundary = log.boundary, scanned = log.coins.size,
                eliminated = count(CoinOutcome.ELIMINATED), flagged = count(CoinOutcome.FLAGGED), notSelected = count(CoinOutcome.NOT_SELECTED),
                noSetup = noSetup, rejected = rejected, staleData = stale, eligible = eligible, opened = opened,
                marketAllowsLongs = log.marketAllowsLongs, marketBlockKind = if (log.marketAllowsLongs) null else market?.blockKind,
                topReasons = top, shortReason = short,
            )
        }

        /** Kayıtlı taramalardaki en son strateji kurulumu (alım sinyali). */
        fun lastSignal(logs: List<ScanLog>): SignalInfo? {
            for (log in logs.asReversed()) {
                for (coin in log.coins) {
                    val s = coin.strategies.firstOrNull { it.setupFound } ?: continue
                    val opened = coin.outcome == CoinOutcome.OPENED || coin.outcome == CoinOutcome.ORDER_PLACED
                    val blocked = if (opened) emptyList() else
                        (s.rules.filter { it.mandatory && !it.passed }.map { "${it.id} ${it.title} — ${it.detail}" } + if (coin.outcome != CoinOutcome.REJECTED) listOf(coin.outcome.title) + coin.reasons else emptyList())
                    return SignalInfo(log.boundary, coin.symbol, "${s.strategy.code} ${s.strategy.title}", opened, blocked.distinct())
                }
            }
            return null
        }
    }
}

/**
 * Sistemin işlem açabilecek durumda olup olmadığı. [softwareOk] false ise işlem açılmaması bir yazılım/veri sorunudur;
 * true ise yazılım çalışıyor ve işlem açılmaması kuralların (piyasa, strateji, risk) kararıdır.
 */
@Serializable
data class SystemStatus(val running: Boolean, val softwareOk: Boolean, val text: String) {
    companion object {
        private const val MINUTE = 60_000L

        fun of(health: com.spotbot.core.engine.EngineHealth, now: Long, last: ScanLog?): SystemStatus {
            val feed = health.feed
            return when (health.status) {
                com.spotbot.core.engine.EngineStatus.STOPPED -> SystemStatus(false, true, "Bot durdurulmuş: tarama ve işlem yok (yazılım hatası değil).")
                com.spotbot.core.engine.EngineStatus.STARTING -> SystemStatus(true, true, "Başlatılıyor: geçmiş mumlar yükleniyor.")
                com.spotbot.core.engine.EngineStatus.ERROR -> SystemStatus(true, false, "Yazılım/bağlantı sorunu: ${health.message ?: health.lastError ?: "bilinmiyor"}")
                com.spotbot.core.engine.EngineStatus.RUNNING -> {
                    val scanAge = health.lastScanAt?.let { (now - it) / MINUTE }
                    val priceAge = feed.lastUpdateAt?.let { (now - it) / MINUTE }
                    val stale = last?.coins?.count { it.outcome == CoinOutcome.STALE_DATA } ?: 0
                    val failed = last?.coins?.filter { it.outcome == CoinOutcome.ENTRY_FAILED }.orEmpty()
                    when {
                        scanAge == null -> SystemStatus(true, true, "İlk tarama bekleniyor.")
                        scanAge > 20 -> SystemStatus(true, false, "Yazılım/bağlantı sorunu: son tarama $scanAge dk önce (15 dakikada bir olmalı).")
                        priceAge == null || priceAge > 2 -> SystemStatus(true, false, "Veri sorunu: canlı fiyat ${priceAge?.let { "$it dk" } ?: "hiç"} alınamadı.")
                        stale > 0 -> SystemStatus(true, false, "Veri sorunu: $stale coinde mum ya da fiyat güncel değil; bu coinlerde işlem açılmadı.")
                        failed.isNotEmpty() -> SystemStatus(true, false, "Emir simülasyonu başarısız: ${failed.first().symbol} — ${failed.first().reasons.firstOrNull() ?: ""}")
                        else -> SystemStatus(true, true, "Yazılım sağlıklı: tarama zamanında, veri güncel. İşlem açılmaması kuralların kararıdır.")
                    }
                }
            }
        }
    }
}
