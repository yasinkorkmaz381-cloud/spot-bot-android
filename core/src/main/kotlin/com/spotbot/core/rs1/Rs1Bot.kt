package com.spotbot.core.rs1

import com.spotbot.core.util.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
data class BotStatus(
    val running: Boolean = false,
    val lastCycleAt: Long? = null,
    /** Son döngünün yazılım/veri hatası (kural kararı değildir). */
    val lastError: String? = null,
    /** Bu haftanın canlı değerlendirmesi kaydedildi mi (pazartesi 00:15–04:00 UTC)? */
    val weeklyRecorded: Boolean = false,
    /** Döngüler arasında beklenenden uzun ara (Android arka plan kısıtı olabilir). */
    val longestGapMinutes: Long = 0,
)

/**
 * Tek karar zinciri: MEXC verisi → veri doğrulama → BTC filtresi → coin seçimi → Momentum RS-1 → risk → paper emir.
 * Android servisi [cycle]'ı birkaç dakikada bir çağırır. Gerçek emir göndermez.
 */
class Rs1Bot(
    val scanner: Rs1Scanner,
    val paper: Rs1Paper,
    private val clock: TimeSource = TimeSource.System,
    private val cfg: Rs1Config = Rs1Config(),
) {
    private val _status = MutableStateFlow(BotStatus())
    val status: StateFlow<BotStatus> = _status.asStateFlow()

    fun setRunning(running: Boolean) = _status.update { it.copy(running = running) }

    /** Bir döngü: tarama (gerekirse), haftalık canlı ölçümün kaydı (penceredeyse), paper trading güncellemesi. */
    suspend fun cycle() {
        val now = clock.now()
        val before = _status.value.lastCycleAt
        var error: String? = null
        val held = paper.state.value?.result?.open?.map { it.symbol }.orEmpty()
        val equity = paper.state.value?.result?.endEquity ?: cfg.capital
        val scan = scanner.refreshIfDue(held, equity)
        scan.lastError?.let { error = it }
        val today = now / DAY_MS * DAY_MS
        val inWindow = isWeekday(today, cfg.entryWeekday) && now in today + 15 * 60_000L until today + cfg.weeklyWindowHours * HOUR_MS
        val recorded = paper.state.value?.snapshots?.any { it.monday == today } == true
        if (inWindow && !recorded) {
            // Haftalık değerlendirme: taze bir taramayla (aynı günün kapanışı, son 15 dakika) canlı ölçümü kaydet.
            val r = scanner.scan(held, equity)
            r.result?.takeIf { r.lastError == null && it.dailyClose == today }?.let { paper.recordSnapshot(scanner.snapshot(it, now)) }
            r.lastError?.let { error = it }
        }
        val p = paper.refreshIfDue()
        p.lastError?.let { error = error ?: it }
        _status.update {
            it.copy(
                lastCycleAt = now, lastError = error,
                weeklyRecorded = paper.state.value?.snapshots?.any { s -> s.monday == mondayOf(now) } == true,
                longestGapMinutes = maxOf(it.longestGapMinutes, before?.let { b -> (now - b) / 60_000L } ?: 0L),
            )
        }
    }

    private fun mondayOf(t: Long): Long {
        var d = t / DAY_MS * DAY_MS
        while (!isWeekday(d, cfg.entryWeekday)) d -= DAY_MS
        return d
    }
}
