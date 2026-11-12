package com.spotbot.android.ui.trades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.data.ArchiveRepository
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.MetricGrid
import com.spotbot.android.ui.common.PositionView
import com.spotbot.android.ui.common.SegmentedTabs
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.TradeRow
import com.spotbot.android.ui.common.WeeklyDecisionView
import com.spotbot.android.ui.common.coin
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.rs1.PaperState
import com.spotbot.core.rs1.Rs1Bot

class TradesViewModel(bot: Rs1Bot, private val archive: ArchiveRepository) : ViewModel() {
    val paper = bot.paper.state
    fun loadArchive(): ArchiveSummary = archive.load()
}

enum class TradesTab(val title: String) { OPEN("Açık"), HISTORY("Geçmiş"), WEEKLY("Haftalık"), ARCHIVE("Arşiv") }

@Composable
fun TradesScreen(modifier: Modifier = Modifier, viewModel: TradesViewModel = viewModel(factory = AppViewModels.Factory)) {
    val paper by viewModel.paper.collectAsStateWithLifecycle()
    val archive = remember { viewModel.loadArchive() }
    TradesContent(paper, archive, modifier)
}

@Composable
fun TradesContent(paper: PaperState?, archive: ArchiveSummary, modifier: Modifier = Modifier, initialTab: TradesTab = TradesTab.OPEN) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val r = paper?.result
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            SegmentedTabs(TradesTab.entries, tab, label = { if (it == TradesTab.OPEN) "${it.title} (${r?.open?.size ?: 0})" else it.title }, onSelect = { tab = it })
        }
        when (tab) {
            TradesTab.OPEN -> {
                if (r?.open.isNullOrEmpty()) item { EmptyText("Açık sanal pozisyon yok.", Modifier.padding(4.dp)) }
                items(r?.open.orEmpty(), key = { it.key }) { PositionView(it, paper?.untrackedAt(it.entryTime) == true) }
            }
            TradesTab.HISTORY -> {
                if (r != null) {
                    item {
                        AppCard {
                            MetricGrid(
                                listOf(
                                    Triple("Net kâr/zarar", "${formatSignedMoney(r.netPnl)} USDT", gainLossColor(r.netPnl.compareTo(0.0))),
                                    Triple("İşlem", "${r.trades.size}", Color.Unspecified),
                                    Triple("Kazanma oranı", formatPercent(r.winRatePercent), Color.Unspecified),
                                    Triple("Profit factor", formatRatio(r.profitFactor), Color.Unspecified),
                                    Triple("Ortalama R", "%+.2f".format(r.averageR), Color.Unspecified),
                                    Triple("En büyük düşüş", formatPercent(r.maxDrawdownPercent, minFractionDigits = 1), Color.Unspecified),
                                ),
                            )
                            if (r.equity.size > 1) LineChart(r.equity.map { it.equity })
                            if (r.trades.size < 30) Text("30'dan az işlem: sonuç henüz istatistik için yetersiz.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                if (r?.trades.isNullOrEmpty()) item { EmptyText("Henüz kapanmış işlem yok.", Modifier.padding(4.dp)) }
                items(r?.trades.orEmpty().sortedByDescending { it.exitTime }, key = { it.key }) { TradeRow(it, paper?.firstSeen?.get("T:" + it.key), paper?.untrackedAt(it.exitTime) == true) }
            }
            TradesTab.WEEKLY -> {
                item { InfoBanner("Her pazartesi 00:15 UTC değerlendirmesinin kaydı: neden işlem açıldı ya da açılmadı.") }
                if (r?.weekly.isNullOrEmpty()) item { EmptyText("Henüz haftalık değerlendirme yok.", Modifier.padding(4.dp)) }
                items(r?.weekly.orEmpty().reversed(), key = { "w-${it.time}" }) { WeeklyDecisionView(it) }
            }
            TradesTab.ARCHIVE -> {
                item {
                    InfoBanner(
                        "Eski A/B/C stratejilerinin kayıtları silinmedi; yalnız okunur olarak burada. Bu stratejiler artık çalışmaz ve işlem açamaz." +
                            if (archive.files.isNotEmpty()) " Arşiv dosyaları: ${archive.files.joinToString()}." else "",
                    )
                }
                if (archive.trades.isEmpty()) item { EmptyText("Arşivde kapanmış işlem yok.", Modifier.padding(4.dp)) }
                else item {
                    val total = archive.trades.sumOf { it.netPnl }
                    Text("${archive.trades.size} eski işlem · net ${formatSignedMoney(total)} USDT", style = MaterialTheme.typography.titleSmall)
                }
                items(archive.trades.take(200)) { t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row {
                            Text("${coin(t.symbol)} · ${t.strategy}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${formatSignedMoney(t.netPnl)} USDT", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = gainLossColor(t.netPnl.compareTo(0.0)))
                        }
                        Text("${t.portfolio} · ${formatDateTime(t.openedAt)} → ${formatDateTime(t.closedAt)} · ${t.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
