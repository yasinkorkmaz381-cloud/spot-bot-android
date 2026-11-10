package com.spotbot.android.ui.backtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.Spacing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spotbot.core.trading.ClosedTrade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.bot.BacktestRunner
import com.spotbot.android.bot.BacktestState
import com.spotbot.android.data.SettingsRepository
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.parseDecimal
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.strategy.StrategyId

class BacktestViewModel(private val runner: BacktestRunner, private val settings: SettingsRepository) : ViewModel() {
    val state = runner.state
    var symbols by mutableStateOf(settings.settings.value.engine.scanner.watchlist.joinToString(", "))
    var days by mutableIntStateOf(90)
    var capital by mutableStateOf("1000")
    var strategies by mutableStateOf(setOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK))

    fun toggle(id: StrategyId) {
        strategies = if (id in strategies) strategies - id else strategies + id
    }

    var mode by mutableStateOf(BacktestMode.SINGLE)

    fun symbolCount() = symbolList().size

    private fun symbolList() = symbols.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        .map { if (it.endsWith("USDT")) it else it + "USDT" }.distinct()

    fun run() {
        val list = symbolList()
        if (list.isEmpty()) return
        val capitalValue = parseDecimal(capital) ?: 1_000.0
        val engine = settings.settings.value.engine
        when (mode) {
            BacktestMode.PROFILES -> runner.compare(list, days, capitalValue, engine)
            BacktestMode.ISOLATED -> runner.isolate(list, days, capitalValue, engine)
            BacktestMode.SINGLE -> if (strategies.isNotEmpty()) runner.run(list, days, capitalValue, strategies, engine)
        }
    }
}

enum class BacktestMode(val title: String) { SINGLE("Tek test"), ISOLATED("A ve B ayrı"), PROFILES("Profiller") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BacktestScreen(modifier: Modifier = Modifier, viewModel: BacktestViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ClosedTrade?>(null) }
    val done = state as? BacktestState.Done
    selected?.let { trade ->
        if (done != null) TradeDetailDialog(trade, done) { selected = null }
    }
    val hasResult = state is BacktestState.Done || state is BacktestState.Compared || state is BacktestState.Isolated
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        // Sonuç varken ayarlar kısa özet olarak kapalı durur; dokununca açılır.
        ExpandableCard(
            "Test ayarları",
            subtitle = "${viewModel.mode.title} · ${viewModel.days} gün · ${viewModel.symbolCount()} coin · ${viewModel.capital} USDT",
            initiallyExpanded = !hasResult,
        ) {
            OutlinedTextField(viewModel.symbols, { viewModel.symbols = it }, label = { Text("Coinler (virgülle)") }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180).forEach { d -> FilterChip(selected = viewModel.days == d, onClick = { viewModel.days = d }, label = { Text("$d gün") }) }
            }
            OutlinedTextField(
                viewModel.capital, { viewModel.capital = it }, label = { Text("Başlangıç sermayesi") }, suffix = { Text("USDT") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Test türü", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BacktestMode.entries.forEach { m -> FilterChip(selected = viewModel.mode == m, onClick = { viewModel.mode = m }, label = { Text(m.title) }) }
            }
            when (viewModel.mode) {
                BacktestMode.PROFILES -> Text(
                    "Adaptif, Trend ve Seçici profilleri aynı veri, dönem, maliyet, risk ve emir modeliyle çalıştırılır; dönem ikiye bölünerek iki yarıdaki tutarlılık gösterilir.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BacktestMode.ISOLATED -> Text(
                    "A+B, yalnız A ve yalnız B aynı veri, ücret, kayma, sermaye ve dönemle ayrı ayrı çalıştırılır (tüm dönem, ilk ve ikinci yarı).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BacktestMode.SINGLE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrategyId.entries.forEach { id ->
                        FilterChip(selected = id in viewModel.strategies, onClick = { viewModel.toggle(id) }, label = { Text("${id.code} ${id.title}") })
                    }
                }
            }
            Text(
                "Canlı motorla aynı tarama, strateji, kural ve risk kodu. Sinyal 15M kapanışında, giriş sonraki mumun açılışında; komisyon, spread ve kayma düşülür.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val running = state is BacktestState.Running
        Button(onClick = viewModel::run, enabled = !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (running) "Çalışıyor…" else "Backtest başlat") }
        when (val s = state) {
            is BacktestState.Running -> {
                Text(s.message)
                LinearProgressIndicator(progress = { s.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
            is BacktestState.Failed -> Text("Backtest başarısız: ${s.message}", color = SpotBotTheme.tradingColors.loss)
            is BacktestState.Done -> BacktestReportContent(s.report) { selected = it }
            is BacktestState.Compared -> ComparisonContent(s.results, s.days, s.symbols)
            is BacktestState.Isolated -> IsolatedContent(s.results, s.days, s.symbols)
            BacktestState.Idle -> Unit
        }
    }
}

@Composable
private fun TradeDetailDialog(trade: ClosedTrade, done: BacktestState.Done, onDismiss: () -> Unit) {
    val chart = remember(trade) { TradeCharts.build(trade, done.data) }
    val features = done.report.entryFeatures.firstOrNull { it.symbol == trade.position.symbol && it.signalTime == trade.position.setup.signalTime }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("İşlem ayrıntısı", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Kapat") }
                }
                TradeDetailContent(trade, features, done.report.excursions[trade.position.id], chart)
            }
        }
    }
}

@Composable
fun BacktestReportContent(r: BacktestReport, onOpenTrade: (ClosedTrade) -> Unit = {}) {
    BacktestSummaryCard(r)
    EquityCard(r)
    StrategyCompareCard(r)
    TradeHistoryCard(r, onOpenTrade)
    DetailedMetricsCard(r)
    ExpandableCard("Çıkış nedenleri ve engelleyen kurallar", subtitle = "Uyarılar, gerçekleşmeyen emirler") {
        if (r.limitOrdersNotFilled > 0) KeyValueRow("Dolmayan limit giriş emri", "${r.limitOrdersNotFilled}")
        r.rejectedOrders.forEach { (reason, count) -> KeyValueRow("Gerçekleşmeyen emir: $reason", "$count") }
        r.exitReasons.forEach { (k, v) -> KeyValueRow("Çıkış: $k", "$v") }
        r.blockedByRule.entries.take(6).forEach { (k, v) -> KeyValueRow("Engelleyen: $k", "$v") }
        r.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
    r.shadow?.let { sh ->
        ExpandableCard("Kural katkısı (gölge işlemler)", subtitle = "${sh.trades} kurulumun bağımsız sonucu") {
            Text(
                "Bulunan ${sh.trades} kurulumun her biri (alınsın ya da reddedilsin) tek başına simüle edildi. Pozitif katkı: kural geçtiğinde sonuç daha iyi.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (sh.trades < 300) {
                Text(
                    "⚠ Örneklem küçük (${sh.trades} kurulum): kural katkıları rastlantıya çok açık. Güvenilir ölçüm için daha uzun dönem ve daha çok coin seçin.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                )
            }
            KeyValueRow("Kurulum başına ortalama", "%+.3fR · kazanma %s".format(sh.avgR, formatPercent(sh.winRatePercent)))
            KeyValueRow("Kabul / ret", "${sh.acceptedCount} (${sh.acceptedAvgR?.let { "%+.3fR".format(it) } ?: "—"}) / ${sh.rejectedCount} (${sh.rejectedAvgR?.let { "%+.3fR".format(it) } ?: "—"})")
            if (sh.limitNotFilled > 0) KeyValueRow("Dolmayan limit emir", "${sh.limitNotFilled}")
            sh.rules.forEach { rule ->
                KeyValueRow("${rule.id} ${rule.title}", "%+.3fR".format(rule.lift), gainLossColor(rule.lift.compareTo(0.0)))
            }
            sh.scoreBuckets.forEach { b -> KeyValueRow("Kalite puanı ${b.label}", "${b.count} kurulum · %+.3fR".format(b.avgR)) }
        }
    }
}

@Composable
fun ComparisonContent(results: List<com.spotbot.core.backtest.ProfileComparison>, days: Int, symbols: List<String>) {
    SectionCard("Profil karşılaştırma · $days gün · ${symbols.size} coin") {
        Text(
            "Aynı koşullar: veri, dönem, komisyon/spread/kayma, risk ayarları ve emir modeli ortak; yalnız profil farklı. Bir profilin anlamlı olması için iki yarıda da tutarlı olması gerekir. Kâr garantisi değildir.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    results.forEach { r ->
        SectionCard("${r.profile.title} — ${r.profile.description}") {
            listOf("Tüm dönem" to r.full, "İlk yarı" to r.firstHalf, "İkinci yarı" to r.secondHalf).forEach { (label, s) ->
                KeyValueRow(
                    label,
                    "${s.trades} işlem · %s · PF %s · %+.2fR · DD %s".format(
                        formatPercent(s.netReturnPercent, signed = true, minFractionDigits = 2), formatRatio(s.profitFactor), s.expectancyR,
                        formatPercent(s.maxDrawdownPercent, minFractionDigits = 1),
                    ),
                    gainLossColor(s.netReturnPercent.compareTo(0.0)),
                )
            }
            KeyValueRow("Kısmi gerçekleşme / gerçekleşmeyen emir", "${r.partialFills} / ${r.rejectedOrders}")
            if (r.full.trades < 30) Text("⚠ İşlem sayısı az (${r.full.trades}); sonuç güvenilir değil.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}
