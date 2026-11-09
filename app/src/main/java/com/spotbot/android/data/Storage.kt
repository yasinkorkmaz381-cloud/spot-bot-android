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
)

class SettingsRepository(dir: File) {
    private val file = JsonFile(File(dir, "settings.json"), AppSettings.serializer())
    private val _settings = MutableStateFlow(file.read() ?: AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
