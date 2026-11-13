package com.spotbot.android.data

import com.spotbot.core.kr.BotMode
import com.spotbot.core.kr.KrState
import com.spotbot.core.kr.KrStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

internal val storageJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

/** JSON dosyasını önce geçici dosyaya yazıp yeniden adlandırarak atomik kaydeder (yarım yazılmış kayıt kalmaz). */
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

/** Kırılım–Retest paper hesabı (kr-paper.json): pozisyonlar, işlemler, işlenmiş kurulumlar, izleme boşlukları. */
class FileKrStore(dir: File) : KrStore {
    private val file = JsonFile(File(dir, "kr-paper.json"), KrState.serializer())
    override fun load() = file.read()
    override fun save(state: KrState) = file.write(state)
}

@Serializable
data class AppSettings(
    /** Kullanıcının seçtiği çalışma biçimi; cihaz yeniden açıldığında OFF değilse servis yeniden başlar. */
    val botMode: BotMode = BotMode.OFF,
    /** Ayar biçimi sürümü; eski kayıtlar açılışta taşınır (bkz. [SettingsRepository.migrate]). */
    val schemaVersion: Int = 0,
)

/**
 * Ayarlar ve sürüm geçişleri. Hiçbir kayıt silinmez:
 * - Sürüm 3 (V5): eski A/B/C motorunun dosyaları `archive-v4/` altına taşındı.
 * - Sürüm 4 (Kırılım–Retest): Momentum RS-1'in paper kaydı ve taraması `archive-rs1/` altına taşınır. Yeni stratejinin
 *   performansına karışmaz; yalnız arşiv ekranında okunur.
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
        const val SCHEMA = 4
        const val ARCHIVE = "archive-v4"
        const val ARCHIVE_RS1 = "archive-rs1"
        /** Eski A/B/C motorunun dosyaları. */
        val LEGACY = listOf("portfolio.json", "shadows.json", "forward_s1.json", "wide_scan.json", "decisions")
        /** Momentum RS-1'in dosyaları. */
        val LEGACY_RS1 = listOf("paper.json", "scan.json")

        /**
         * Geçiş tekrar uygulanırsa bir şey değiştirmez. Yeni strateji kendiliğinden başlamaz: eski sürümde bot açık olsa
         * bile geçişten sonra kapalı açılır, kullanıcı başlatır (eski açık pozisyonlar arşivdedir, izlenecek yeni pozisyon yok).
         */
        fun migrate(dir: File, s: AppSettings): AppSettings {
            if (s.schemaVersion >= SCHEMA) return s
            if (s.schemaVersion < 3) moveAll(dir, ARCHIVE, LEGACY, "Sürüm 3'e geçiş (Momentum RS-1, tek strateji): eski A/B/C motorunun kayıtları buraya taşındı; silinmedi.", copySettings = true)
            moveAll(dir, ARCHIVE_RS1, LEGACY_RS1, "Sürüm 4'e geçiş (Kırılım–Retest, tek strateji): Momentum RS-1 paper kayıtları buraya taşındı; silinmedi ve yeni stratejinin sonuçlarına karıştırılmaz.", copySettings = false)
            return AppSettings(botMode = BotMode.OFF, schemaVersion = SCHEMA)
        }

        private fun moveAll(dir: File, archiveName: String, names: List<String>, note: String, copySettings: Boolean) {
            val archive = File(dir, archiveName)
            val moved = ArrayList<String>()
            for (name in names) {
                val f = File(dir, name)
                if (!f.exists()) continue
                archive.mkdirs()
                val target = File(archive, name)
                if (!target.exists() && f.renameTo(target)) moved += name
            }
            if (copySettings) {
                File(dir, "settings.json").takeIf { it.exists() }?.let { old ->
                    archive.mkdirs()
                    File(archive, "settings-eski.json").takeIf { !it.exists() }?.let { old.copyTo(it); moved += "settings.json (kopya)" }
                }
            }
            if (moved.isNotEmpty()) File(archive, "BILGI.txt").appendText(note + "\n" + moved.joinToString("\n") { "- $it" } + "\n")
        }
    }
}
