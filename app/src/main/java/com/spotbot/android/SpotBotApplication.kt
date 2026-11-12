package com.spotbot.android

import android.app.Application
import com.spotbot.android.bot.BacktestRunner
import com.spotbot.android.data.ArchiveRepository
import com.spotbot.android.data.FilePaperStore
import com.spotbot.android.data.FileScanStore
import com.spotbot.android.data.SettingsRepository
import com.spotbot.core.exchange.mexc.MexcRestClient
import com.spotbot.core.rs1.Rs1Bot
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Paper
import com.spotbot.core.rs1.Rs1Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class SpotBotApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Uygulama genelinde tek örnek bağımlılıklar. Tek karar zinciri: MEXC verisi → veri doğrulama → BTC filtresi → coin
 * seçimi → Momentum RS-1 → risk → paper emir. Gerçek emir gönderen hiçbir bileşen yoktur.
 */
class AppContainer(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = MexcRestClient()
    /** Önce oluşturulur: sürüm geçişinde eski motorun kayıtlarını arşive taşır. */
    val settings = SettingsRepository(app.filesDir)
    val archive = ArchiveRepository(app.filesDir)
    /** Momentum RS-1'in tek parametre kaynağı. */
    val config = Rs1Config()
    /** MEXC sunucu saatine göre düzeltilmiş saat (cihaz saati yanlışsa pencereler kaymasın). */
    val clock = com.spotbot.core.util.ServerClock()
    val scanner = Rs1Scanner(api, FileScanStore(app.filesDir), clock = clock, cfg = config)
    val paper = Rs1Paper(api, FilePaperStore(app.filesDir), clock = clock, cfg = config)
    val bot = Rs1Bot(scanner, paper, clock, config, serverTime = { api.serverTime() })
    val backtest = BacktestRunner(api, scope, app.cacheDir, config)

    /** Sanal hesabı sıfırlar: mevcut kayıt silinmez, `archive-v4/paper-<zaman>.json` olarak saklanır. */
    suspend fun resetPaper() {
        val old = File(app.filesDir, "paper.json")
        if (old.exists()) {
            val dir = File(app.filesDir, SettingsRepository.ARCHIVE).apply { mkdirs() }
            old.copyTo(File(dir, "paper-${System.currentTimeMillis()}.json"))
        }
        paper.reset()
    }
}
