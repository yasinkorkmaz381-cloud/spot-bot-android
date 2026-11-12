package com.spotbot.android.bot

import com.spotbot.core.exchange.MarketDataApi
import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.DailySeries
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Costs
import com.spotbot.core.rs1.Rs1Engine
import com.spotbot.core.rs1.Rs1History
import com.spotbot.core.rs1.Rs1Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Backtest dönemi. Son test dönemi, araştırmadaki dokunulmamış dönemle aynıdır (docs/RS1-ONKAYIT.md). */
enum class BacktestPeriod(val title: String, val from: String?, val to: String?, val yearsBack: Int?) {
    LAST_YEAR("Son 1 yıl", null, null, 1),
    HOLDOUT("Son test (2025 →)", "2025-01-01", null, null),
    VALIDATION("Doğrulama (2023–2024)", "2023-01-01", "2025-01-01", null),
    TRAIN("Eğitim (2020-08 – 2022)", "2020-08-01", "2023-01-01", null),
    ALL("Tüm dönem (2020-08 →)", "2020-08-01", null, null),
    ;

    fun range(today: Long): Pair<Long, Long> {
        val end = to?.let { day(it) } ?: today
        val start = from?.let { day(it) } ?: (end - (yearsBack ?: 1) * 365L * DAY_MS)
        return start to end
    }

    companion object {
        fun day(s: String) = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}

sealed interface BacktestState {
    data object Idle : BacktestState
    data class Running(val message: String, val progress: Float?) : BacktestState
    data class Done(
        val result: Rs1Result,
        val period: BacktestPeriod,
        val failedSymbols: Int,
        /** Ön kayıtlı kabul ölçütleri (iki kat maliyet çalıştırmasıyla). */
        val evidence: com.spotbot.core.rs1.Evidence? = null,
        val doubled: Rs1Result? = null,
        val mode: com.spotbot.core.rs1.BacktestMode = com.spotbot.core.rs1.BacktestMode.RESEARCH,
    ) : BacktestState
    data class Failed(val message: String) : BacktestState
}

class BacktestRunner(private val api: MarketDataApi, private val scope: CoroutineScope, private val cacheDir: File, private val base: Rs1Config) {
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }
    private val _state = MutableStateFlow<BacktestState>(BacktestState.Idle)
    val state: StateFlow<BacktestState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Araştırma ile aynı yol ([com.spotbot.core.rs1.Rs1Backtest]): aynı veri hazırlığı, aynı motor, aynı gerçekleşme
     * modeli. [mode] = APPROXIMATE ise gün içi veri indirilmez ve sonuç "yaklaşık" olarak etiketlenir.
     */
    fun run(period: BacktestPeriod, costs: Rs1Costs, mode: com.spotbot.core.rs1.BacktestMode = com.spotbot.core.rs1.BacktestMode.RESEARCH) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val today = System.currentTimeMillis() / DAY_MS * DAY_MS
                val (start, end) = period.range(today)
                _state.value = BacktestState.Running("Parite listesi ve ücretler alınıyor…", null)
                val dir = File(cacheDir, "rs1-bt").apply { mkdirs() }
                fun file(key: String) = File(dir, key.replace(':', '_') + ".json")
                val data = com.spotbot.core.rs1.Rs1Backtest.prepare(
                    api, start, end, mode, base,
                    cache = { k -> read(file(k)) },
                    store = { k, c -> file(k).writeText(json.encodeToString(ListSerializer(Candle.serializer()), c)) },
                    onProgress = { what, i, n -> _state.value = BacktestState.Running("$what: $i / $n parite", i.toFloat() / n) },
                )
                _state.value = BacktestState.Running("Momentum RS-1 çalıştırılıyor…", null)
                val cfg = base.copy(costs = costs)
                // Açık pozisyonlar dönem sonunda zorla kapatılmaz: gerçekleşmemiş olarak değerlenir.
                val result = com.spotbot.core.rs1.Rs1Backtest.run(data, cfg, start, end)
                val doubled = com.spotbot.core.rs1.Rs1Backtest.run(data, cfg.copy(costs = costs.doubled()), start, end)
                _state.value = BacktestState.Done(result, period, data.failed.size, com.spotbot.core.rs1.Rs1Evidence.classify(result, doubled), doubled, mode)
            } catch (e: Exception) {
                _state.value = BacktestState.Failed(e.message ?: e::class.simpleName ?: "Hata")
            }
        }
    }

    private fun read(f: File): List<Candle>? =
        f.takeIf { it.exists() }?.let { runCatching { json.decodeFromString(ListSerializer(Candle.serializer()), it.readText()) }.getOrNull() }
}
