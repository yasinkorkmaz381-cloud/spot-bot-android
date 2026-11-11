package com.spotbot.android.data

import com.spotbot.core.rs1.PaperState
import com.spotbot.core.rs1.PaperStore
import com.spotbot.core.rs1.ScanState
import com.spotbot.core.rs1.ScanStore
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

/** Momentum RS-1 paper trading durumu (paper.json): yeniden başlatmada kaldığı yerden devam eder. */
class FilePaperStore(dir: File) : PaperStore {
    private val file = JsonFile(File(dir, "paper.json"), PaperState.serializer())
    override fun load() = file.read()
    override fun save(state: PaperState) = file.write(state)
}

/** Son geniş evren taraması ve bilinen pariteler (yeni listeleme / çıkarılma tespiti). */
class FileScanStore(dir: File) : ScanStore {
    private val file = JsonFile(File(dir, "scan.json"), ScanState.serializer())
    override fun load() = file.read()
    override fun save(state: ScanState) = file.write(state)
}

@Serializable
data class AppSettings(
    /** Kullanıcı botu çalışır bıraktıysa yeniden başlatmada (ör. cihaz açılışı) otomatik başlatılır. */
    val botEnabled: Boolean = false,
    /** Ayar biçimi sürümü; eski kayıtlar açılışta taşınır (bkz. [SettingsRepository.migrate]). */
    val schemaVersion: Int = 0,
)

/**
 * Ayarlar ve sürüm geçişi. Sürüm 3 (V5, tek strateji): eski A/B/C motorunun bütün dosyaları silinmeden
 * `archive-v4/` klasörüne taşınır; uygulama yalnız Momentum RS-1 ile devam eder.
 */
class SettingsRepository(private val dir: File) {
    private val file = JsonFile(File(dir, "settings.json"), AppSettings.serializer())
    private val _settings = MutableStateFlow(migrate(dir, file.read() ?: AppSettings()).also { file.write(it) })
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        file.write(next)
    }

    companion object {
        const val SCHEMA = 3
        const val ARCHIVE = "archive-v4"
        /** Eski motorun dosyaları (A/B/C sanal portföyü, gölge portföyler, S1 ileri testi, V4 taraması, karar günlüğü). */
        val LEGACY = listOf("portfolio.json", "shadows.json", "forward_s1.json", "wide_scan.json", "decisions")

        /**
         * Sürüm 3'e geçiş: eski dosyalar ve eski ayar dosyasının bir kopyası `archive-v4/` altına taşınır (silinmez),
         * taşınanların listesi `archive-v4/BILGI.txt`'ye yazılır. "Bot çalışıyor" tercihi korunur. Tekrar uygulanması
         * bir şey değiştirmez.
         */
        fun migrate(dir: File, s: AppSettings): AppSettings {
            if (s.schemaVersion >= SCHEMA) return s
            val archive = File(dir, ARCHIVE)
            val moved = ArrayList<String>()
            for (name in LEGACY) {
                val f = File(dir, name)
                if (!f.exists()) continue
                archive.mkdirs()
                val target = File(archive, name)
                if (!target.exists() && f.renameTo(target)) moved += name
            }
            File(dir, "settings.json").takeIf { it.exists() }?.let { old ->
                archive.mkdirs()
                File(archive, "settings-eski.json").takeIf { !it.exists() }?.let { old.copyTo(it); moved += "settings.json (kopya)" }
            }
            if (moved.isNotEmpty()) {
                File(archive, "BILGI.txt").appendText(
                    "Sürüm 3'e geçiş (Momentum RS-1, tek strateji): eski A/B/C motorunun kayıtları buraya taşındı; silinmedi.\n" +
                        moved.joinToString("\n") { "- $it" } + "\n",
                )
            }
            return AppSettings(botEnabled = s.botEnabled, schemaVersion = SCHEMA)
        }
    }
}
