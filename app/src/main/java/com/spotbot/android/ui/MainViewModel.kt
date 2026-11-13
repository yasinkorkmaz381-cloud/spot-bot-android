package com.spotbot.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spotbot.android.SpotBotApplication
import com.spotbot.android.bot.BotService
import com.spotbot.android.data.ArchiveSummary
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

    fun saveKey(key: String) = viewModelScope.launch(Dispatchers.IO) { if (key.isNotBlank()) c.cmcKey.set(key) }
    fun clearKey() = viewModelScope.launch(Dispatchers.IO) { c.cmcKey.clear() }

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
