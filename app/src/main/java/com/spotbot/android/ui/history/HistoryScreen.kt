package com.spotbot.android.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spotbot.android.bot.HistoryRequest
import com.spotbot.android.bot.HistoryState
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.MetricTile
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.parseDecimal
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.kr.HistoryOutcome
import com.spotbot.core.kr.KrConfig
import com.spotbot.core.kr.KrTrade
import com.spotbot.core.kr.UniverseSource
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.sign

enum class HistoryPeriod(val label: String, val days: Int) { D30("30 gün", 30), D90("90 gün", 90), D180("180 gün", 180), D365("365 gün", 365), CUSTOM("Özel", 0) }

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** "GG.AA.YYYY" → o UTC gününün başlangıcı. */
fun parseDay(text: String): Long? = runCatching { LocalDate.parse(text.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

/** Seçimden istek üretir; geçersizse hata metni döndürür. */
fun buildRequest(period: HistoryPeriod, fromText: String, toText: String, equityText: String, now: Long): Pair<HistoryRequest?, String?> {
    val equity = parseDecimal(equityText)
    if (equity == null || equity < 50 || equity > 10_000_000) return null to "Başlangıç bakiyesi 50 ile 10.000.000 USDT arasında olmalı"
    val today = now - Math.floorMod(now, KrConfig.DAY)
    return if (period == HistoryPeriod.CUSTOM) {
        val from = parseDay(fromText) ?: return null to "Başlangıç tarihi GG.AA.YYYY biçiminde olmalı"
        val to = parseDay(toText) ?: return null to "Bitiş tarihi GG.AA.YYYY biçiminde olmalı"
        if (to < from) return null to "Bitiş başlangıçtan önce olamaz"
        if (from >= today) return null to "Başlangıç bugünden önce olmalı"
        HistoryRequest(from, to + KrConfig.DAY, equity, "${fromText.trim()} – ${toText.trim()}") to null
    } else {
        HistoryRequest(today - period.days * KrConfig.DAY, now + 1, equity, "Son ${period.label}") to null
    }
}

/** Geçmiş test: dönem ve bakiye seçimi, ilerleme/iptal, özet ölçüler, işlem listesi ve CSV dışa aktarma. */
@Composable
fun HistoryContent(
    state: HistoryState,
    keyPresent: Boolean,
    now: Long,
    onStart: (HistoryRequest) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onExport: (List<KrTrade>) -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.D30) }
    var fromText by rememberSaveable { mutableStateOf("") }
    var toText by rememberSaveable { mutableStateOf("") }
    var equityText by rememberSaveable { mutableStateOf("1000") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val running = state is HistoryState.Running
    val done = state as? HistoryState.Done
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        if (done == null && !running) item {
            AppCard {
                Text("Dönem", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryPeriod.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
                }
                if (period == HistoryPeriod.CUSTOM) {
                    OutlinedTextField(fromText, { fromText = it }, singleLine = true, label = { Text("Başlangıç (GG.AA.YYYY)") }, modifier = Modifier.fillMaxWidth().testTag("from"))
                    OutlinedTextField(toText, { toText = it }, singleLine = true, label = { Text("Bitiş (GG.AA.YYYY)") }, modifier = Modifier.fillMaxWidth().testTag("to"))
                }
                OutlinedTextField(
                    equityText, { equityText = it }, singleLine = true, label = { Text("Başlangıç sanal bakiyesi (USDT)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("equity"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (!keyPresent) Text("Coin listesi için CMC API anahtarı gerekli (Ayarlar).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        val (req, err) = buildRequest(period, fromText, toText, equityText, now)
                        error = err
                        req?.let(onStart)
                    },
                    enabled = keyPresent, modifier = Modifier.fillMaxWidth().testTag("start"),
                ) { Text("Testi başlat") }
                Text(
                    "Uygulamadaki tek Kırılım–Retest stratejisi ve ortak sanal bakiye kullanılır; komisyon, spread ve kayma dahildir. " +
                        "İlk çalıştırmada veri indirilir (365 gün için 15–30 dk ve birkaç yüz MB); sonra önbellekten. " +
                        "CMC geçmiş listesi gün başına 2 kredi harcar ve saklanır. MEXC 15 dakikalık geçmişi yaklaşık 360 gündür.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (state) {
            is HistoryState.Running -> item {
                AppCard(Modifier.testTag("progress")) {
                    Text("Test sürüyor · ${state.request.label}", style = MaterialTheme.typography.titleMedium)
                    Text("${state.progress.phase} (${state.progress.done}/${state.progress.total})", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { state.progress.fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel")) { Text("İptal") }
                }
            }
            is HistoryState.Failed -> item {
                InfoBanner("Test tamamlanamadı: ${state.message}", containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            }
            is HistoryState.Cancelled -> item { InfoBanner("Test iptal edildi. İndirilen veriler önbellekte kaldı; tekrar başlatınca devam eder.") }
            else -> {}
        }
        if (done != null) {
            val o = done.outcome
            if (o.source == UniverseSource.TODAY) item {
                InfoBanner(
                    "Bugünkü ilk 300 listesiyle geçmiş test. Geçmiş CMC sıralaması kullanılamadı; sonuç tarihsel ilk 300 sonucu değildir ve yanlı olabilir.",
                    containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag("warning-today"),
                )
            }
            o.warnings.filterNot { it.startsWith("Bugünkü ilk 300") }.forEach { w -> item { InfoBanner(w) } }
            item { Summary(done.request, o) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onExport(o.result.trades) }, enabled = o.result.trades.isNotEmpty(), modifier = Modifier.weight(1f).testTag("export")) { Text("CSV dışa aktar") }
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("Yeni test") }
                }
            }
            item { Text("İşlemler (${o.result.trades.size})", style = MaterialTheme.typography.titleMedium) }
            if (o.result.trades.isEmpty()) item { EmptyText("Bu aralıkta işlem yok.") }
            items(o.result.trades.sortedByDescending { it.entryTime }, key = { it.id }) { TradeRow(it) }
        } else if (state is HistoryState.Failed || state is HistoryState.Cancelled) item {
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Yeni test") }
        }
    }
}

@Composable
private fun Summary(req: HistoryRequest, o: HistoryOutcome) {
    val m = o.metrics
    AppCard(Modifier.testTag("summary")) {
        Text("Sonuç · ${req.label}", style = MaterialTheme.typography.titleMedium)
        Text("${formatDateTime(o.start)} → ${formatDateTime(o.end)} TSİ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Net kâr/zarar", "${formatSignedMoney(m.netPnl)} USDT", Modifier.weight(1f), gainLossColor(m.netPnl.sign.toInt()))
            MetricTile("Son bakiye", "${formatMoney(o.result.endEquity)} USDT", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("İşlem sayısı", "${m.trades}", Modifier.weight(1f))
            MetricTile("Kazanma oranı", if (m.trades == 0) "—" else "%" + "%.1f".format(m.winRate), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Profit factor", when { m.trades == 0 || m.profitFactor.isNaN() -> "—"; m.profitFactor.isInfinite() -> "∞"; else -> "%.2f".format(m.profitFactor) }, Modifier.weight(1f))
            MetricTile("Maks. düşüş", "%" + "%.2f".format(m.maxDrawdownPercent), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Toplam maliyet", "${formatMoney(m.totalCosts)} USDT", Modifier.weight(1f))
            MetricTile("Ort. süre", "${m.avgHoldMinutes.toInt()} dk", Modifier.weight(1f))
        }
        KeyValueRow("Evren (gün başına ort.)", "İlk ${o.avgTop.toInt()} → MEXC'de ${o.avgOnMexc.toInt()} → taranan ${o.avgScanned.toInt()}")
        KeyValueRow("Liste kaynağı", o.source.title)
        if (o.result.book.positions.isNotEmpty()) KeyValueRow("Dönem sonunda açık", "${o.result.book.positions.size} pozisyon (gerçekleşmemiş)")
        Text("Maliyet: MEXC taker komisyonu, bugünkü spread ve %0,05 kayma (her bacakta). Geçmiş ücret/spread verisi yoktur.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TradeRow(t: KrTrade) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium)
                Text(t.reason.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${formatSignedMoney(t.netPnl)} USDT", color = gainLossColor(t.netPnl.sign.toInt()), style = MaterialTheme.typography.titleMedium)
        }
        KeyValueRow("Giriş", "${formatDateTime(t.entryTime)} · ${formatPrice(t.entryPrice)}")
        KeyValueRow("Çıkış", "${formatDateTime(t.exitTime)} · ${formatPrice(t.exitPrice)}")
    }
}
