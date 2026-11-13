package com.spotbot.android

import android.app.Application
import com.spotbot.android.data.ArchiveRepository
import com.spotbot.android.data.CmcKeyStore
import com.spotbot.android.data.FileKrStore
import com.spotbot.android.data.KeystoreCmcKeyStore
import com.spotbot.android.data.SettingsRepository
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.android.bot.HistoryRunner
import com.spotbot.core.kr.CandleFileStore
import com.spotbot.core.kr.CmcClient
import com.spotbot.core.kr.CmcListFileCache
import com.spotbot.core.kr.KrHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.spotbot.core.kr.KrConfig
import com.spotbot.core.kr.KrPaper
import com.spotbot.core.util.ServerClock
import java.io.File

/** Kalıcı kayda yazılmayan canlı durum: servis kalp atışı, sonraki tarama, fiyat akışı bağlantısı. */
data class LiveStatus(
    val serviceAliveAt: Long = 0L,
    val nextScanAt: Long = 0L,
    val stream: com.spotbot.core.exchange.mexc.StreamConnectionState = com.spotbot.core.exchange.mexc.StreamConnectionState(),
)

class SpotBotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Uygulama genelinde tek örnek bağımlılıklar. Tek karar zinciri: CMC ilk 300 → MEXC eşleşmesi → 15M tarama
 * (Kırılım–Retest) → süzgeçler → risk → paper emir. Gerçek emir gönderen hiçbir bileşen yoktur.
 */
class AppContainer(private val app: Application) {
    val api = MexcRestClient()
    /** Önce oluşturulur: sürüm geçişinde eski kayıtları arşive taşır. */
    val settings = SettingsRepository(app.filesDir)
    val archive = ArchiveRepository(app.filesDir)
    val cmcKey: CmcKeyStore = KeystoreCmcKeyStore(app.filesDir)
    val config = KrConfig()
    /** MEXC sunucu saatine göre düzeltilmiş saat. */
    val clock = ServerClock()
    val cmc = CmcClient({ cmcKey.get() })
    val paper = KrPaper(api, cmc, FileKrStore(app.filesDir), clock, config, CandleFileStore(File(app.filesDir, "paper-mumlar")))
    /** Servisin canlılığı ve fiyat akışı (süreç içi; süreç ölünce sıfırlanır, arayüz "servis çalışmıyor" gösterir). */
    val live = kotlinx.coroutines.flow.MutableStateFlow(LiveStatus())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Geçmiş test önbelleği: mumlar (bellek eşlemeli) ve günlük CMC listeleri. Ayarlar'dan temizlenebilir. */
    val candleStore = CandleFileStore(File(app.cacheDir, "gecmis-test/mumlar"))
    val listCache = CmcListFileCache(File(app.filesDir, "gecmis-test/cmc"))
    val history = HistoryRunner(scope) { req, progress ->
        KrHistory(api, cmc, cmc, candleStore, listCache, config, now = { clock.now() }).run(req.from, req.to, req.equity, onProgress = progress)
    }

    /** Sanal hesabı sıfırlar: mevcut kayıt silinmez, `archive-kr/kr-paper-<zaman>.json` olarak saklanır. */
    suspend fun resetPaper() {
        val old = File(app.filesDir, "kr-paper.json")
        if (old.exists()) {
            val dir = File(app.filesDir, ArchiveRepository.ARCHIVE_KR).apply { mkdirs() }
            old.copyTo(File(dir, "kr-paper-${System.currentTimeMillis()}.json"))
        }
        paper.reset()
    }

    suspend fun syncClock() {
        runCatching { api.serverTime() }.onSuccess { clock.offsetMillis = it - System.currentTimeMillis() }
    }
}
