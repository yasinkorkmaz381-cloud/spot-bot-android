package com.spotbot.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spotbot.android.SpotBotApplication
import com.spotbot.android.bot.BotService
import com.spotbot.android.bot.HistoryRequest
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.core.kr.CmcException
import com.spotbot.core.kr.KrReport
import com.spotbot.core.kr.sanitizeCmcKey
import com.spotbot.core.kr.BotMode
import com.spotbot.core.kr.EventWindow
import com.spotbot.core.kr.KrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Tek ViewModel: paper durumu, çalışma biçimi, CMC anahtarı ve arşiv. Karar vermez; yalnız gösterir ve komut iletir. */
class MainViewModel(private val app: SpotBotApplication) : ViewModel() {
    private val c = app.container
    val state: StateFlow<KrState> = c.paper.state
    val settings = c.settings.settings
    val keyPresent = c.cmcKey.present
    private val _archive = MutableStateFlow<ArchiveSummary?>(null)
    val archive: StateFlow<ArchiveSummary?> = _archive.asStateFlow()

    fun setMode(mode: BotMode) {
        c.settings.update { it.copy(botMode = mode) }
        BotService.setMode(app, mode)
    }

    val keyUnreadable = c.cmcKey.unreadable
    private val _keyStatus = MutableStateFlow<KeyStatus?>(null)
    val keyStatus: StateFlow<KeyStatus?> = _keyStatus.asStateFlow()
    val history = c.history.state

    /**
     * Anahtarı temizleyip kaydeder, CMC'de doğrular (kredi harcamaz) ve doğruysa coin listesini bot modundan bağımsız
     * olarak hemen yeniler. Sonuç Ayarlar'da gösterilir.
     */
    fun saveKey(raw: String) = viewModelScope.launch(Dispatchers.IO) {
        val key = sanitizeCmcKey(raw)
        if (key.isBlank()) return@launch
        _keyStatus.value = KeyStatus(KeyStatus.Kind.CHECKING, "Anahtar doğrulanıyor…")
        c.cmcKey.set(key)
        if (c.cmcKey.get() != key) {
            _keyStatus.value = KeyStatus(KeyStatus.Kind.ERROR, "Anahtar cihazda saklanamadı (Android Keystore hatası).")
            return@launch
        }
        _keyStatus.value = try {
            val info = c.cmc.keyInfo()
            val err = c.paper.refreshUniverseNow()
            val u = c.paper.state.value.universe
            if (err == null && u != null) {
                KeyStatus(KeyStatus.Kind.OK, "Anahtar geçerli (kalan aylık kredi ${info.creditsLeftMonth}). Liste alındı: ilk ${u.top} → MEXC'de ${u.onMexc} → taranan ${u.included.size}.")
            } else KeyStatus(KeyStatus.Kind.ERROR, "Anahtar geçerli ama liste alınamadı: $err")
        } catch (e: CmcException) {
            KeyStatus(KeyStatus.Kind.ERROR, e.message ?: "CMC hatası")
        } catch (e: Exception) {
            KeyStatus(KeyStatus.Kind.ERROR, "CMC'ye ulaşılamadı: ${e.message}")
        }
    }

    fun refreshList() = viewModelScope.launch(Dispatchers.IO) {
        _keyStatus.value = KeyStatus(KeyStatus.Kind.CHECKING, "Liste yenileniyor…")
        val err = c.paper.refreshUniverseNow()
        val u = c.paper.state.value.universe
        _keyStatus.value = if (err == null && u != null) KeyStatus(KeyStatus.Kind.OK, "Liste alındı: ilk ${u.top} → MEXC'de ${u.onMexc} → taranan ${u.included.size}.")
        else KeyStatus(KeyStatus.Kind.ERROR, err ?: "Liste alınamadı")
    }

    fun clearKey() = viewModelScope.launch(Dispatchers.IO) { c.cmcKey.clear(); _keyStatus.value = null }

    fun startHistory(req: HistoryRequest) = c.history.start(req)
    fun cancelHistory() = c.history.cancel()
    fun resetHistory() = c.history.reset()
    fun clearHistoryCache() = viewModelScope.launch(Dispatchers.IO) { c.candleStore.clear() }

    /** CSV'yi kullanıcının seçtiği dosyaya yazar. */
    fun exportCsv(uri: android.net.Uri, trades: List<com.spotbot.core.kr.KrTrade>) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(KrReport.csv(trades).toByteArray()) } }
    }

    fun addEvent(time: Long, title: String) = viewModelScope.launch(Dispatchers.IO) {
        c.paper.setEvents(state.value.events + EventWindow(time, title.ifBlank { "Makro olay" }))
    }

    fun removeEvent(e: EventWindow) = viewModelScope.launch(Dispatchers.IO) { c.paper.setEvents(state.value.events - e) }

    fun reset() = viewModelScope.launch(Dispatchers.IO) { c.resetPaper() }

    fun loadArchive() = viewModelScope.launch(Dispatchers.IO) { _archive.value = c.archive.load() }

    fun now(): Long = c.clock.now()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SpotBotApplication) }
        }
    }
}

data class KeyStatus(val kind: Kind, val text: String) {
    enum class Kind { CHECKING, OK, ERROR }
}
