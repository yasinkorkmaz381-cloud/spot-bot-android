package com.spotbot.android.data

import com.spotbot.core.engine.EngineConfig
import com.spotbot.core.engine.StateStore
import com.spotbot.core.trading.DecisionLogStore
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.ScanLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

internal val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

/** JSON dosyasını önce geçici dosyaya yazıp yeniden adlandırarak atomik kaydeder. */
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    fun read(): T? = file.takeIf { it.exists() }?.let { runCatching { storageJson.decodeFromString(serializer, it.readText()) }.getOrNull() }

    @Synchronized
    fun write(value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(storageJson.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Sanal portföyün uygulama dosyalarında kalıcı saklanması (yeniden başlatmada kaldığı yerden devam). */
class FileStateStore(dir: File) : StateStore {
    private val file = JsonFile(File(dir, "portfolio.json"), PortfolioState.serializer())
    override fun load(): PortfolioState? = file.read()
    override fun save(state: PortfolioState) = file.write(state)
}

@Serializable
data class AppSettings(
    val engine: EngineConfig = EngineConfig(),
    /** Kullanıcı botu çalışır bıraktıysa yeniden başlatmada (ör. cihaz açılışı) otomatik başlatılır. */
    val botEnabled: Boolean = false,
    /** Ayar biçimi sürümü; eski kayıtlar açılışta taşınır (bkz. [SettingsRepository.migrate]). */
    val schemaVersion: Int = 0,
    /**
     * S1 ileri testinin evreni: false = dar 28 parite (varsayılan; V4 ön kayıt kararı), true = geniş MEXC evreni.
     * Değiştirmek ileri testi o andan yeniden başlatır.
     */
    val forwardWideUniverse: Boolean = false,
)

/** Gölge portföyler: shadows.json (strateji adı → sanal hesap). */
class FileShadowStore(dir: File) : com.spotbot.core.engine.ShadowStore {
    private val file = JsonFile(File(dir, "shadows.json"), kotlinx.serialization.builtins.MapSerializer(String.serializer(), PortfolioState.serializer()))
    override fun load(): Map<String, PortfolioState> = file.read().orEmpty()
    override fun save(shadows: Map<String, PortfolioState>) = file.write(shadows)
}

/** V3 ileri testi (S1) kalıcı durumu: başlangıç zamanı yeniden başlatmalarda korunur. */
class FileForwardStore(dir: File) : com.spotbot.core.research.ForwardStore {
    private val file = JsonFile(File(dir, "forward_s1.json"), com.spotbot.core.research.ForwardState.serializer())
    override fun load() = file.read()
    override fun save(state: com.spotbot.core.research.ForwardState) = file.write(state)
}

/** Geniş evren taraması: bilinen pariteler (yeni listeleme / çıkarılma tespiti) ve son sonuç. */
class FileWideScanStore(dir: File) : com.spotbot.core.scanner.WideScanStore {
    private val file = JsonFile(File(dir, "wide_scan.json"), com.spotbot.core.scanner.WideScanState.serializer())
    override fun load() = file.read()
    override fun save(state: com.spotbot.core.scanner.WideScanState) = file.write(state)
}

class SettingsRepository(dir: File) {
    private val file = JsonFile(File(dir, "settings.json"), AppSettings.serializer())
    private val _settings = MutableStateFlow(migrate(file.read() ?: AppSettings()).also { file.write(it) })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    companion object {
        const val SCHEMA = 2

        /**
         * Sürüm 2: Strateji A aktif kâğıt işlemden çıkarılır ve gölge teste alınır (B aktif kalır). İşlem geçmişi ve
         * risk ayarları değişmez. Tekrar uygulanması sonucu değiştirmez.
         */
        fun migrate(s: AppSettings): AppSettings {
            if (s.schemaVersion >= SCHEMA) return s
            val a = com.spotbot.core.strategy.StrategyId.BREAKOUT_RETEST
            val active = (s.engine.enabledStrategies - a).ifEmpty { setOf(com.spotbot.core.strategy.StrategyId.TREND_PULLBACK) }
            return s.copy(engine = s.engine.copy(enabledStrategies = active, shadowStrategies = s.engine.shadowStrategies + a), schemaVersion = SCHEMA)
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        file.write(updated)
    }
}

/**
 * Coin bazında karar günlüğü: her tarama bir satır (JSON Lines), gün başına bir dosya; son [keepDays] gün tutulur.
 * Bir tarama ~15 KB; günde 96 tarama ≈ 1,5 MB.
 */
class FileDecisionLogStore(private val dir: File, private val keepDays: Int = 3) : DecisionLogStore {
    private val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    init { dir.mkdirs() }

    @Synchronized
    override fun append(log: ScanLog) {
        File(dir, "${day.format(java.util.Date(log.time))}.jsonl").appendText(storageJson.encodeToString(ScanLog.serializer(), log) + "\n")
        dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }?.dropLast(keepDays)?.forEach { it.delete() }
    }

    @Synchronized
    override fun recent(limit: Int): List<ScanLog> {
        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        val result = ArrayDeque<ScanLog>()
        for (file in files.reversed()) {
            val lines = file.readLines().filter { it.isNotBlank() }
            for (line in lines.reversed()) {
                runCatching { storageJson.decodeFromString(ScanLog.serializer(), line) }.getOrNull()?.let { result.addFirst(it) }
                if (result.size >= limit) return result.toList()
            }
        }
        return result.toList()
    }
}
