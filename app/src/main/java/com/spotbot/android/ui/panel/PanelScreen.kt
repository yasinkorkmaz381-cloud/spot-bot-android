package com.spotbot.android.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.data.SettingsRepository
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.BotController
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.BackgroundNotice
import com.spotbot.android.ui.common.BtcFilterView
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.MetricGrid
import com.spotbot.android.ui.common.PositionView
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.SectionLabel
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.TradeRow
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.rs1.BotStatus
import com.spotbot.core.rs1.PaperState
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Scanner
import com.spotbot.core.rs1.ScanState

class PanelViewModel(
    private val bot: com.spotbot.core.rs1.Rs1Bot,
    private val settings: SettingsRepository,
    private val controller: BotController,
    val config: Rs1Config,
) : ViewModel() {
    val paper = bot.paper.state
    val scan = bot.scanner.state
    val status = bot.status
    val botEnabled = settings.settings

    fun start() = controller.start()
    fun stop() = controller.stop()
}

@Composable
fun PanelScreen(modifier: Modifier = Modifier, viewModel: PanelViewModel = viewModel(factory = AppViewModels.Factory)) {
    val paper by viewModel.paper.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val settings by viewModel.botEnabled.collectAsStateWithLifecycle()
    PanelContent(paper, scan, status, settings.botEnabled, viewModel.config, viewModel::start, viewModel::stop, modifier)
}

@Composable
fun PanelContent(
    paper: PaperState?,
    scan: ScanState,
    status: BotStatus,
    botEnabled: Boolean,
    config: Rs1Config,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val colors = SpotBotTheme.tradingColors
    val r = paper?.result
    val equity = r?.endEquity ?: config.capital
    val total = equity - config.capital
    val lastClose = r?.equity?.lastOrNull()?.equity ?: config.capital
    val daily = equity - lastClose
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            AppCard {
                Text("Toplam sermaye (sanal)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatMoney(equity)} USDT", style = MaterialTheme.typography.headlineMedium)
                MetricGrid(
                    listOf(
                        Triple("Bugün", "${formatSignedMoney(daily)} USDT", gainLossColor(daily.compareTo(0.0))),
                        Triple("Toplam", "${formatSignedMoney(total)} USDT", gainLossColor(total.compareTo(0.0))),
                        Triple("Açık pozisyon", "${r?.open?.size ?: 0} / ${config.maxPositions}", Color.Unspecified),
                        Triple("Kapanan işlem", "${r?.trades?.size ?: 0}", Color.Unspecified),
                    ),
                )
                paper?.let { Text("Momentum RS-1 · paper trading başlangıcı ${formatDateTime(it.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            SectionCard("Bot") {
                val software = status.lastError ?: paper?.lastError ?: scan.lastError
                StatusPill(
                    when {
                        !botEnabled -> "Durduruldu"
                        software != null -> "Yazılım / veri sorunu"
                        else -> "Çalışıyor"
                    },
                    if (!botEnabled) MaterialTheme.colorScheme.onSurfaceVariant else if (software != null) colors.loss else colors.gain,
                )
                software?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.loss) }
                KeyValueRow("Son döngü", status.lastCycleAt?.let { formatDateTime(it) } ?: "—")
                KeyValueRow("Sonraki haftalık değerlendirme", formatDateTime(Rs1Scanner.nextWeekly(now, config)))
                KeyValueRow("Bu haftanın canlı değerlendirmesi", if (status.weeklyRecorded) "Yapıldı" else "Yapılmadı / bekleniyor")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (botEnabled) OutlinedButton(onClick = onStop) { Text("Botu durdur") } else Button(onClick = onStart) { Text("Botu başlat") }
                }
            }
        }
        item {
            SectionCard("BTC piyasa filtresi") { BtcFilterView(scan.result?.btc) }
        }
        item {
            SectionCard("Tarama") {
                val s = scan.result
                if (s == null) Text("Henüz tarama yok.", style = MaterialTheme.typography.bodyMedium)
                else {
                    KeyValueRow("Taranan USDT paritesi", "${s.totalPairs}")
                    KeyValueRow("Bütün şartları geçen", "${s.eligible}")
                    KeyValueRow("Aday", if (s.candidates.isEmpty()) "yok" else s.candidates.joinToString { it.removeSuffix("USDT") }, if (s.candidates.isNotEmpty()) colors.gain else Color.Unspecified)
                    s.noTradeReason?.let { Text("İşlem açılmama nedeni: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { SectionLabel("Açık pozisyonlar") }
        if (r?.open.isNullOrEmpty()) item { EmptyText("Açık sanal pozisyon yok.") }
        items(r?.open.orEmpty(), key = { "p-" + it.key }) { PositionView(it) }
        item { SectionLabel("Son işlemler") }
        if (r?.trades.isNullOrEmpty()) item { EmptyText("Henüz kapanmış işlem yok.") }
        items(r?.trades.orEmpty().sortedByDescending { it.exitTime }.take(5), key = { "t-" + it.key }) { TradeRow(it, paper?.firstSeen?.get("T:" + it.key)) }
        item { BackgroundNotice(status.longestGapMinutes) }
    }
}

