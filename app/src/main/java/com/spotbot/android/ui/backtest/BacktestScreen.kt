package com.spotbot.android.ui.backtest

import androidx.compose.foundation.layout.Arrangement
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

    var compareMode by mutableStateOf(false)

    private fun symbolList() = symbols.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        .map { if (it.endsWith("USDT")) it else it + "USDT" }.distinct()

    fun run() {
        val list = symbolList()
        if (list.isEmpty()) return
        if (compareMode) {
            runner.compare(list, days, parseDecimal(capital) ?: 1_000.0, settings.settings.value.engine)
        } else {
            if (strategies.isEmpty()) return
            runner.run(list, days, parseDecimal(capital) ?: 1_000.0, strategies, settings.settings.value.engine)
        }
    }
}

@Composable
fun BacktestScreen(modifier: Modifier = Modifier, viewModel: BacktestViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoBanner("Backtest canlı motorla aynı tarama, strateji, kural ve risk kodunu kullanır. Sinyal 15M kapanışında oluşur, giriş sonraki mumun açılışından yapılır; komisyon, spread ve kayma düşülür.")
        SectionCard("Test ayarları") {
            OutlinedTextField(viewModel.symbols, { viewModel.symbols = it }, label = { Text("Coinler (virgülle)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 90, 180).forEach { d -> FilterChip(selected = viewModel.days == d, onClick = { viewModel.days = d }, label = { Text("$d gün") }) }
            }
            OutlinedTextField(
                viewModel.capital, { viewModel.capital = it }, label = { Text("Başlangıç sermayesi") }, suffix = { Text("USDT") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !viewModel.compareMode, onClick = { viewModel.compareMode = false }, label = { Text("Tek test") })
                FilterChip(selected = viewModel.compareMode, onClick = { viewModel.compareMode = true }, label = { Text("Profil karşılaştırma") })
            }
            if (viewModel.compareMode) {
                Text(
                    "Adaptif, Trend ve Seçici profilleri aynı veri, dönem, maliyet, risk ve emir modeliyle çalıştırılır; dönem ikiye bölünerek iki yarıdaki tutarlılık gösterilir.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrategyId.entries.forEach { id ->
                        FilterChip(selected = id in viewModel.strategies, onClick = { viewModel.toggle(id) }, label = { Text("${id.code} ${id.title}") })
                    }
                }
            }
            val running = state is BacktestState.Running
            Button(onClick = viewModel::run, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Çalışıyor…" else "Backtest başlat") }
        }
        when (val s = state) {
            is BacktestState.Running -> {
                Text(s.message)
                LinearProgressIndicator(progress = { s.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
            is BacktestState.Failed -> Text("Backtest başarısız: ${s.message}", color = SpotBotTheme.tradingColors.loss)
            is BacktestState.Done -> BacktestReportContent(s.report)
            is BacktestState.Compared -> ComparisonContent(s.results, s.days, s.symbols)
            BacktestState.Idle -> Unit
        }
    }
}

@Composable
fun BacktestReportContent(r: BacktestReport) {
    SectionCard("Sonuç") {
        KeyValueRow("Dönem", "${formatDateTime(r.startTime)} → ${formatDateTime(r.endTime)}")
        KeyValueRow("Sermaye", "${formatMoney(r.initialCapital)} → ${formatMoney(r.finalEquity)} USDT")
        KeyValueRow("Net getiri", formatPercent(r.netReturnPercent, signed = true, minFractionDigits = 2), gainLossColor(r.netReturnPercent.compareTo(0.0)))
        KeyValueRow("BTC al-tut", r.btcBuyAndHoldPercent?.let { formatPercent(it, signed = true, minFractionDigits = 2) } ?: "—")
        KeyValueRow("Maksimum düşüş", formatPercent(r.maxDrawdownPercent, minFractionDigits = 2), SpotBotTheme.tradingColors.loss)
        KeyValueRow("İşlem sayısı", "${r.trades} (bulunan kurulum ${r.setupsFound})")
        KeyValueRow("Kazanma oranı", formatPercent(r.winRatePercent, minFractionDigits = 1))
        KeyValueRow("Ort. kazanç / kayıp", "${formatSignedMoney(r.averageWin)} / ${formatSignedMoney(r.averageLoss)}")
        KeyValueRow("Profit factor", formatRatio(r.profitFactor))
        KeyValueRow("İşlem başına beklenen değer", "${formatSignedMoney(r.expectancy)} USDT (${"%+.2f".format(r.expectancyR)}R)")
        KeyValueRow("Komisyon", "${formatMoney(r.totalFees)} USDT")
        KeyValueRow("Spread + kayma", "${formatMoney(r.spreadAndSlippageCost)} USDT")
        KeyValueRow("Piyasada kalma süresi", formatPercent(r.exposurePercent, minFractionDigits = 1))
        if (r.limitOrdersNotFilled > 0) KeyValueRow("Dolmayan limit giriş emri", "${r.limitOrdersNotFilled}")
        KeyValueRow("Profil", r.profile.title)
        if (r.partialFills > 0) KeyValueRow("Kısmi gerçekleşen emir (likidite)", "${r.partialFills}")
        r.rejectedOrders.forEach { (reason, count) -> KeyValueRow("Gerçekleşmeyen emir: $reason", "$count") }
        if (r.equityCurve.size >= 2) LineChart(r.equityCurve.map { it.second })
    }
    if (r.perStrategy.isNotEmpty()) {
        SectionCard("Strateji bazında") {
            r.perStrategy.forEach { s ->
                KeyValueRow(
                    "${s.strategy.code} ${s.strategy.title}",
                    "${s.trades} işlem · %s · PF %s · %s".format(formatPercent(s.winRatePercent), formatRatio(s.profitFactor), formatSignedMoney(s.netPnl)),
                    gainLossColor(s.netPnl.compareTo(0.0)),
                )
            }
        }
    }
    SectionCard("Ayrıntı") {
        r.exitReasons.forEach { (k, v) -> KeyValueRow("Çıkış: $k", "$v") }
        r.blockedByRule.entries.take(6).forEach { (k, v) -> KeyValueRow("Engelleyen: $k", "$v") }
        r.warnings.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
    }
    r.shadow?.let { sh ->
        SectionCard("Kural katkısı (gölge işlemler)") {
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
    SectionCard("Son işlemler") {
        r.closedTrades.takeLast(15).reversed().forEach { t ->
            KeyValueRow(
                "${formatDateTime(t.position.openedAt)} ${t.position.symbol.removeSuffix("USDT")} ${t.position.strategy.code}",
                "${formatPrice(t.position.entryPrice)} → ${formatPrice(t.exitPrice)} ${formatSignedMoney(t.netPnl)} (${t.reason.title})",
                gainLossColor(t.netPnl.compareTo(0.0)),
            )
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
