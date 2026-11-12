package com.spotbot.android.ui.backtest

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.bot.BacktestPeriod
import com.spotbot.android.bot.BacktestRunner
import com.spotbot.android.bot.BacktestState
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.MetricGrid
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.TradeRow
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.rs1.Rs1Costs
import com.spotbot.core.rs1.Rs1Result

class BacktestViewModel(private val runner: BacktestRunner) : ViewModel() {
    val state = runner.state
    fun run(period: BacktestPeriod, costs: Rs1Costs, mode: com.spotbot.core.rs1.BacktestMode) = runner.run(period, costs, mode)
}

@Composable
fun BacktestScreen(modifier: Modifier = Modifier, viewModel: BacktestViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BacktestContent(state, viewModel::run, modifier)
}

@Composable
fun BacktestContent(state: BacktestState, onRun: (BacktestPeriod, Rs1Costs, com.spotbot.core.rs1.BacktestMode) -> Unit, modifier: Modifier = Modifier) {
    var modeName by rememberSaveable { mutableStateOf(com.spotbot.core.rs1.BacktestMode.RESEARCH.name) }
    val mode = com.spotbot.core.rs1.BacktestMode.valueOf(modeName)
    var period by rememberSaveable { mutableStateOf(BacktestPeriod.HOLDOUT) }
    var costName by rememberSaveable { mutableStateOf(Rs1Costs.MEDIUM.name) }
    val costs = Rs1Costs.all.first { it.name == costName }
    val running = state is BacktestState.Running
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            SectionCard("Momentum RS-1 backtest") {
                Text("Dönem", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BacktestPeriod.entries.forEach { p -> FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.title) }) }
                }
                Text("Maliyet senaryosu (geçmiş spread verisi yok)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Rs1Costs.all.forEach { c -> FilterChip(selected = c.name == costName, onClick = { costName = c.name }, label = { Text(c.name) }) }
                }
                Text(
                    "Yön başına: ücret %%0,05 + kayma %%0,05 + yarım spread %%%.3f".format(costs.halfSpread * 100).replace('.', ','),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Gerçekleşme modu", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.spotbot.core.rs1.BacktestMode.entries.forEach { m -> FilterChip(selected = m == mode, onClick = { modeName = m.name }, label = { Text(m.title) }) }
                }
                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onRun(period, costs, mode) }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Çalışıyor…" else "Backtest'i çalıştır") }
            }
        }
        when (state) {
            is BacktestState.Running -> item {
                AppCard {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("İlk çalıştırmada bütün MEXC USDT paritelerinin günlük geçmişi indirilir (birkaç dakika); sonra önbellekten okunur.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is BacktestState.Failed -> item { InfoBanner("Backtest çalıştırılamadı (yazılım/veri sorunu): ${state.message}", containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) }
            is BacktestState.Done -> {
                if (state.mode == com.spotbot.core.rs1.BacktestMode.APPROXIMATE) item {
                    InfoBanner("YAKLAŞIK MOD: stoplar yalnız günlük mumla çözüldü; gün içi boşluklar görülmedi. Bu sonuç araştırma testiyle eşdeğer değildir.", containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                resultItems(state.result, state.period, state.failedSymbols, state.evidence, state.doubled)
            }
            BacktestState.Idle -> item {
                InfoBanner("Yalnız Momentum RS-1 hesaplanır. Backtest ve paper trading aynı karar motorunu kullanır. Son test dönemi, araştırmanın dokunulmamış bağımsız test dönemidir.")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(
    r: Rs1Result,
    period: BacktestPeriod,
    failed: Int,
    evidence: com.spotbot.core.rs1.Evidence? = null,
    doubled: Rs1Result? = null,
) {
    item {
        AppCard {
            Text("${period.title} · ${formatDateTime(r.start)} → ${formatDateTime(r.end)} · ${r.costs} maliyet", style = MaterialTheme.typography.titleSmall)
            MetricGrid(
                listOf(
                    Triple("Başlangıç sermayesi", "${formatMoney(r.startCapital)} USDT", Color.Unspecified),
                    Triple("Bitiş sermayesi", "${formatMoney(r.endEquity)} USDT", Color.Unspecified),
                    Triple("Net kâr/zarar", "${formatSignedMoney(r.netPnl)} USDT", gainLossColor(r.netPnl.compareTo(0.0))),
                    Triple("Net getiri", formatPercent(r.netReturnPercent, signed = true, minFractionDigits = 2), gainLossColor(r.netPnl.compareTo(0.0))),
                    Triple("İşlem sayısı", "${r.trades.size}", Color.Unspecified),
                    Triple("Kazanma oranı", formatPercent(r.winRatePercent), Color.Unspecified),
                    Triple("Profit factor", formatRatio(r.profitFactor), Color.Unspecified),
                    Triple("En büyük düşüş", formatPercent(r.maxDrawdownPercent, minFractionDigits = 1), Color.Unspecified),
                    Triple("Ortalama R", "%+.2f".format(r.averageR), gainLossColor(r.averageR.compareTo(0.0))),
                    Triple("İşlem başına beklenen", "${formatSignedMoney(r.expectancy)} USDT", gainLossColor(r.expectancy.compareTo(0.0))),
                    Triple("Toplam işlem maliyeti", "${formatMoney(r.fees + r.friction)} USDT", Color.Unspecified),
                    Triple("Ortalama süre", "%.1f gün".format(r.averageHoldingDays), Color.Unspecified),
                ),
            )
            if (r.equity.size > 1) LineChart(r.equity.map { it.equity })
            if (r.trades.size < 30) Text("30'dan az işlem: sonuç istatistik için yetersiz.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
    item {
        SectionCard("Kanıt durumu (ön kayıtlı ölçütler)") {
            // Kanıt sınıfı verilmediyse sonuçtan hesaplanır (iki kat maliyet çalıştırması yoksa o şart sağlanmamış sayılır).
            com.spotbot.android.ui.common.EvidenceView(
                evidence ?: r.takeIf { it.trades.isNotEmpty() }?.let { com.spotbot.core.rs1.Rs1Evidence.classify(it, doubled) }, "Bu backtest dönemi",
            )
            if (period != BacktestPeriod.TRAIN && period != BacktestPeriod.VALIDATION) {
                Text(
                    "Not: 2020–2026 dönemleri araştırmada görüldü; burada çıkan sonuç bağımsız test değildir. Bağımsız test 2018–2019'da yapıldı (yetersiz kanıt).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item {
        SectionCard("Karşılaştırma") {
            KeyValueRow("Momentum RS-1", formatPercent(r.netReturnPercent, signed = true, minFractionDigits = 2), gainLossColor(r.netPnl.compareTo(0.0)))
            KeyValueRow("BTC al-tut", formatPercent(r.btcBuyHoldPercent, signed = true, minFractionDigits = 2), gainLossColor(r.btcBuyHoldPercent.compareTo(0.0)))
            KeyValueRow("USDT'de bekleme", "%0")
            KeyValueRow("Ücret / spread+kayma", "${formatMoney(r.fees)} / ${formatMoney(r.friction)} USDT")
            KeyValueRow("Gerçekleşmiş / açık pozisyon", "${formatSignedMoney(r.realizedPnl)} / ${formatSignedMoney(r.unrealizedPnl)} USDT")
            doubled?.let { KeyValueRow("İki kat maliyetle", formatPercent(it.netReturnPercent, signed = true, minFractionDigits = 2), gainLossColor(it.netPnl.compareTo(0.0))) }
            KeyValueRow("Piyasada geçen gün", formatPercent(r.exposurePercent))
            KeyValueRow("t istatistiği (ort. R)", "%.2f".format(r.tStat))
        }
    }
    item {
        ExpandableCard("Piyasa rejimine göre", subtitle = "Giriş günündeki BTC durumu") {
            r.byRegime().forEach { g -> KeyValueRow("${g.regime} (${g.trades})", "%+.2fR · %s USDT".format(g.averageR, formatSignedMoney(g.netPnl)), gainLossColor(g.netPnl.compareTo(0.0))) }
        }
    }
    item {
        ExpandableCard("Çıkış ve girişsiz hafta nedenleri") {
            r.trades.groupingBy { it.reason.title }.eachCount().entries.sortedByDescending { it.value }.forEach { KeyValueRow(it.key, "${it.value}") }
            Text("Haftalık değerlendirme ${r.weekly.size} · girişli ${r.weekly.count { it.entered.isNotEmpty() }}", style = MaterialTheme.typography.titleSmall)
            r.weekly.mapNotNull { it.noEntryReason }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.forEach { KeyValueRow(it.key, "${it.value}") }
        }
    }
    item {
        InfoBanner(
            "Evren: bugün MEXC'de bulunan ${r.symbols} USDT paritesi (borsadan kaldırılanlar indirilemez: hayatta kalma yanlılığı). " +
                "Spread ≤ %0,15 ve emir defteri derinliği şartları geçmişte ölçülemediği için backtest'te uygulanmaz; spread maliyet senaryosuyla modellenir. " +
                "Yeni listelenen coinler 250 günlük geçmişten önce işlem görmez (EMA100 ısınması). Ücret: parite bazında MEXC taker ücreti (bugünkü). " +
                "Stop çözümü: gün içi (4H) ${r.trades.count { it.intradayResolved }}, günlük mumla ${r.dailyResolvedStops}. Ortalama sermaye kullanımı %${"%.0f".format(r.capitalUsePercent)}." +
                if (failed > 0) " $failed paritenin verisi alınamadı." else "",
        )
    }
    item { Text("İşlem geçmişi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
    items(r.trades.sortedByDescending { it.exitTime }, key = { "bt-" + it.key }) { TradeRow(it) }
}
