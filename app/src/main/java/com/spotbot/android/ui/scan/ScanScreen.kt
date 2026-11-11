package com.spotbot.android.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.BtcFilterView
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.SectionLabel
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.WeeklyDecisionView
import com.spotbot.android.ui.common.coin
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.rs1.Rs1Bot
import com.spotbot.core.rs1.ScanRow
import com.spotbot.core.rs1.ScanState
import com.spotbot.core.rs1.WeeklyDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ScanViewModel(private val bot: Rs1Bot) : ViewModel() {
    val scan = bot.scanner.state
    val paper = bot.paper.state
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun refresh() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = bot.paper.state.value?.result
                bot.scanner.scan(p?.open?.map { it.symbol }.orEmpty(), p?.endEquity ?: 1_000.0)
            } finally { _busy.value = false }
        }
    }
}

/** Görünen kısa sektör adı (MEXC etiketinden). */
private fun sector(r: ScanRow): String? = r.sectors.firstNotNullOfOrNull {
    when (it) {
        "LAYER 1 / LAYER 2" -> "L1/L2"; "MEME" -> "Meme"; "AI", "AI Agent" -> "YZ"; "DeFi" -> "DeFi"; "DePIN", "Storage", "ZK" -> "Altyapı"
        "RWA" -> "RWA"; "GameFi" -> "Oyun"; "Privacy" -> "Gizlilik"; else -> null
    }
} ?: if ("Main" in r.sectors) "Büyük" else null

@Composable
fun ScanScreen(modifier: Modifier = Modifier, viewModel: ScanViewModel = viewModel(factory = AppViewModels.Factory)) {
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val paper by viewModel.paper.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    ScanContent(scan, paper?.result?.weekly?.lastOrNull(), busy, viewModel::refresh, modifier)
}

@Composable
fun ScanContent(state: ScanState, lastWeekly: WeeklyDecision?, busy: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SpotBotTheme.tradingColors
    val r = state.result
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            SectionCard("Momentum RS-1 taraması", trailing = { TextButton(onClick = onRefresh, enabled = !busy) { Text(if (busy) "Taranıyor…" else "Şimdi tara") } }) {
                state.lastError?.let {
                    StatusPill("Yazılım / veri sorunu", colors.loss)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (r == null) {
                    Text(if (busy) "İlk tarama yapılıyor…" else "Henüz tarama yok. Bot çalışırken 30 dakikada bir taranır.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    KeyValueRow("Son tarama", formatDateTime(r.time))
                    KeyValueRow("Toplam incelenen USDT paritesi", "${r.totalPairs}")
                    KeyValueRow("Ayrıntılı (1D) incelenen", "${r.analyzed}")
                    KeyValueRow("Likidite filtresini geçen", "${r.liquidityPassed}")
                    KeyValueRow("Trend filtresini geçen", "${r.trendPassed}")
                    KeyValueRow("Bütün şartları geçen", "${r.eligible}")
                    KeyValueRow(
                        "İşleme uygun aday", if (r.candidates.isEmpty()) "yok" else r.candidates.joinToString { coin(it) },
                        if (r.candidates.isNotEmpty()) colors.gain else Color.Unspecified,
                    )
                    Text(
                        r.noTradeReason?.let { "İşlem açılmama nedeni: $it" } ?: "Adaylar bir sonraki haftalık değerlendirmede (${formatDateTime(r.nextWeeklyEvaluation)}) değerlendirilir.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (r.newListings.isNotEmpty()) KeyValueRow("Yeni listelenen", r.newListings.take(6).joinToString { coin(it) })
                    if (r.delisted.isNotEmpty()) KeyValueRow("Listeden çıkan", r.delisted.take(6).joinToString { coin(it) }, colors.loss)
                }
            }
        }
        if (r != null) {
            item { SectionCard("BTC piyasa filtresi") { BtcFilterView(r.btc) } }
            item { SectionLabel("Momentum sıralaması · en güçlü ${r.top.size}") }
            if (r.top.isEmpty()) item { EmptyText("Likidite ve veri şartlarını geçen parite yok.") }
            items(r.top, key = { it.symbol }) { row -> ScanRowView(row, r.top.indexOf(row) + 1) }
            item {
                ExpandableCard("Elenme nedenleri", subtitle = "Her parite ilk elendiği aşamada sayılır") {
                    r.stages.entries.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("$v", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                    Text(
                        "Gerekli derinlik: orta fiyatın ±%1 aralığında her iki tarafta en az ${"%.0f".format(r.requiredDepth)} USDT (coin başına en büyük pozisyonun 3 katı).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        lastWeekly?.let { w -> item { SectionCard("Son haftalık değerlendirme") { WeeklyDecisionView(w) } } }
        item {
            InfoBanner("Yeni alımlar yalnız pazartesi 00:15 UTC'deki haftalık değerlendirmede yapılır. Tarama bilgi amaçlıdır; spread ve derinlik canlı ölçülür.")
        }
    }
}

@Composable
private fun ScanRowView(row: ScanRow, rank: Int) {
    val colors = SpotBotTheme.tradingColors
    val c = row.check
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$rank", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
            Text(coin(row.symbol), style = MaterialTheme.typography.titleSmall)
            if (row.candidate) Tag("Aday", colors.gain) else if (row.eligible) Tag("Uygun", colors.gain)
            if (row.held) Tag("Portföyde", MaterialTheme.colorScheme.primary)
            sector(row)?.let { Tag(it, MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "30g %+.1f%%".format(c.return30Percent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f), color = if (c.return30Percent > 0) colors.gain else colors.loss,
            )
        }
        Column(Modifier.padding(start = 28.dp)) {
            Text(
                "7g %+.1f%% · BTC'ye göre %+.1f puan · hacim %.1fM · spread %%%.2f · aralık %.1f ATR".format(
                    c.return7Percent, c.relativeStrength, c.medianVolume / 1e6, row.spreadPercent, c.rangeAtr,
                ),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.failed.isNotEmpty()) Text("✕ " + row.failed.joinToString { it.title }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
