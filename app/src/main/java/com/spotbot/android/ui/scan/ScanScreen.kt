package com.spotbot.android.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.ui.AppViewModels
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.NavRow
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.SectionLabel
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.StatusPill
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.engine.EngineHealth
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.engine.TradingEngine
import com.spotbot.core.trading.ScanDiagnostics
import com.spotbot.core.trading.SignalInfo
import com.spotbot.core.scanner.CoinScan
import com.spotbot.core.scanner.MarketBlockKind
import com.spotbot.core.scanner.MarketExplanation
import com.spotbot.core.scanner.MarketState
import com.spotbot.core.scanner.CoinStatus
import com.spotbot.core.scanner.ScanReport
import com.spotbot.core.trading.CoinDecision
import com.spotbot.core.trading.CoinOutcome
import com.spotbot.core.trading.DataStamp
import com.spotbot.core.trading.ScanLog

class ScanViewModel(engine: TradingEngine) : ViewModel() {
    val report = engine.lastScan
    val scanLogs = engine.scanLogs
    val health = engine.health
}

/** Teşhis panelinin verisi: hepsi motorun gerçek kayıtlarından gelir. */
data class DiagnosticsView(
    val health: EngineHealth,
    val diagnostics: ScanDiagnostics?,
    val lastSignal: SignalInfo?,
    val market: MarketState?,
)

/** Analiz durumu: taramanın coin hakkındaki sonucu. */
@Composable
fun analysisLabel(status: CoinStatus): Pair<String, Color> = when (status) {
    CoinStatus.CANDIDATE -> "Aday" to MaterialTheme.colorScheme.primary
    CoinStatus.PASSED -> "Filtreyi geçti" to MaterialTheme.colorScheme.onSurfaceVariant
    CoinStatus.FLAGGED -> "Riskli" to MaterialTheme.colorScheme.tertiary
    CoinStatus.ELIMINATED -> "Elendi" to MaterialTheme.colorScheme.onSurfaceVariant
}

/** İşlem uygunluğu: son taramadaki kararın kısa hali. Yeşil yalnız kuralların geçtiği durumda. */
@Composable
fun eligibilityLabel(outcome: CoinOutcome?): Pair<String, Color> {
    val gain = SpotBotTheme.tradingColors.gain
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    return when (outcome) {
        CoinOutcome.OPENED, CoinOutcome.ORDER_PLACED -> "İşlemde" to gain
        CoinOutcome.OBSERVE_ONLY, CoinOutcome.PAUSED, CoinOutcome.ENTRY_FAILED -> "Uygun" to gain
        CoinOutcome.REJECTED, CoinOutcome.STALE_DATA, CoinOutcome.ELIMINATED -> "Uygun değil" to neutral
        CoinOutcome.FLAGGED -> "Riskli" to MaterialTheme.colorScheme.tertiary
        CoinOutcome.NO_SETUP -> "Kurulum yok" to neutral
        CoinOutcome.NOT_SELECTED -> "İzleniyor" to neutral
        null -> "—" to neutral
    }
}

@Composable
fun ScanScreen(onOpenSymbol: (String) -> Unit, onOpenDecisionLog: () -> Unit, modifier: Modifier = Modifier, viewModel: ScanViewModel = viewModel(factory = AppViewModels.Factory)) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val logs by viewModel.scanLogs.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val view = DiagnosticsView(health, logs.lastOrNull()?.let { ScanDiagnostics.of(it, report?.market) }, ScanDiagnostics.lastSignal(logs), report?.market)
    ScanContent(report, logs.lastOrNull()?.coins?.associateBy { it.symbol }.orEmpty(), onOpenSymbol, modifier, onOpenDecisionLog, view)
}

@Composable
fun ScanContent(
    r: ScanReport?,
    decisions: Map<String, CoinDecision>,
    onOpenSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDecisionLog: () -> Unit = {},
    diagnostics: DiagnosticsView? = null,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        if (r == null) {
            item { InfoBanner("Henüz tarama yok. Botu başlattığınızda ilk tarama hemen, sonrakiler her 15 dakikalık mum kapanışında yapılır.") }
            diagnostics?.let { d -> item { DiagnosticsCard(d) } }
            item { AppCard { NavRow("Karar günlüğü", subtitle = "Önceki taramaların kayıtları", onClick = onOpenDecisionLog) } }
            return@LazyColumn
        }
        item { MarketCard(r) }
        diagnostics?.let { d -> item { DiagnosticsCard(d) } }
        item { SectionLabel("Coinler", trailing = { Text("${r.coins.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }) }
        item { ListHeader() }
        items(r.coins, key = { it.symbol }) { coin ->
            CoinRow(coin, decisions[coin.symbol]?.outcome) { onOpenSymbol(coin.symbol) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item { AppCard { NavRow("Karar günlüğü", subtitle = "Her taramada her coin için karar ve kural sonuçları", onClick = onOpenDecisionLog) } }
    }
}

/** Engel türünün rengi: piyasa riski kırmızı (risk uyarısı), veri sorunu sarı (uyarı). */
@Composable
fun marketBlockColor(kind: MarketBlockKind?): Color = when (kind) {
    MarketBlockKind.MARKET -> SpotBotTheme.tradingColors.loss
    MarketBlockKind.DATA -> MaterialTheme.colorScheme.tertiary
    null -> SpotBotTheme.tradingColors.gain
}

@Composable
private fun MarketCard(r: ScanReport) {
    val market = r.market
    AppCard {
        StatusPill(
            if (market.allowsLongs) "Piyasa alıma uygun" else "Alım yok · ${market.blockKind?.title ?: "Veri sorunu"}",
            marketBlockColor(market.blockKind),
        )
        MarketExplanation.text(market)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            buildString {
                append("BTC 4H ${market.btcRegime?.name ?: "—"}")
                market.btcChange4hPercent?.let { append(" · son 4s ${formatPercent(it, signed = true, minFractionDigits = 2)}") }
                append(" · ${r.candidates.size} aday · ${formatTime(r.time)}")
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (market.conditions.isNotEmpty()) {
        ExpandableCard("Piyasa filtresi ayrıntıları", subtitle = "Şartlar, BTC 4H/1H göstergeleri ve veri zamanları") { MarketFilterDetails(market) }
    }
}

/** Piyasa (BTC) filtresinin her şartı ve kullandığı değerler. Panelde ve Tarama ekranında kullanılır. */
@Composable
fun MarketFilterDetails(market: MarketState) {
    val colors = SpotBotTheme.tradingColors
    market.conditions.forEach { c ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (c.passed) "✓" else "✗", color = if (c.passed) colors.gain else marketBlockColor(c.kind), fontWeight = FontWeight.Bold)
            Column {
                Text(c.title, style = MaterialTheme.typography.bodyMedium)
                Text("${if (c.kind == MarketBlockKind.DATA) "Veri" else "Piyasa"} şartı · ${c.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    listOfNotNull(market.btcH4, market.btcH1).forEach { v ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("BTC ${v.timeframe} · son kapanmış mum ${formatDateTime(v.lastClose)}", style = MaterialTheme.typography.titleSmall)
        KeyValueRow("Kapanış", formatPrice(v.close))
        KeyValueRow("EMA21 / EMA55", "${formatPrice(v.emaMid)} / ${formatPrice(v.emaSlow)}")
        KeyValueRow("SuperTrend", if (v.superTrendUp) "Yukarı" else "Aşağı")
        KeyValueRow("ADX (+DI / −DI)", "%.1f (%.1f / %.1f)".format(v.adx, v.plusDi, v.minusDi))
        KeyValueRow("Yapı / rejim", "${v.structure} / ${v.regime}")
    }
    Text(
        "Trend kararı BTC 4H'ye göre verilir (düşüş trendi: kapanış ve EMA21 EMA55'in altında, SuperTrend aşağı, ADX ≥ 20, yapı yükselişte değil). 1H yalnız son 4 saatteki sert düşüşü (%3) ölçer.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Canlı sinyal teşhisi: bot neden işlem açmıyor sorusunun sayılarla yanıtı. Varsayılan kapalı; başlıkta kısa neden. */
@Composable
fun DiagnosticsCard(v: DiagnosticsView, initiallyExpanded: Boolean = false) {
    val colors = SpotBotTheme.tradingColors
    val d = v.diagnostics
    val h = v.health
    val running = h.status == EngineStatus.RUNNING || h.status == EngineStatus.STARTING
    val subtitle = when {
        !running -> "Bot çalışmıyor: tarama yapılmıyor"
        d == null -> "İlk tarama bekleniyor"
        d.shortReason == null -> "Son taramada işlem açıldı"
        else -> d.shortReason!!
    }
    ExpandableCard("Teşhis: neden işlem yok?", subtitle = subtitle, initiallyExpanded = initiallyExpanded) {
        KeyValueRow(
            "Bot",
            when (h.status) {
                EngineStatus.RUNNING -> if (h.entriesPaused) "Çalışıyor · yeni işlemler duraklatıldı" else "Çalışıyor"
                EngineStatus.STARTING -> "Başlatılıyor"
                EngineStatus.ERROR -> "Hata: ${h.message ?: h.lastError ?: ""}"
                EngineStatus.STOPPED -> "Durduruldu"
            },
            if (h.status == EngineStatus.RUNNING && !h.entriesPaused) colors.gain else MaterialTheme.colorScheme.tertiary,
        )
        KeyValueRow("Son başarılı tarama", h.lastScanAt?.let { formatDateTime(it) } ?: "—")
        KeyValueRow("Son mum güncellemesi", h.lastCandleSyncAt?.let { formatDateTime(it) } ?: "—")
        KeyValueRow("Son fiyat verisi", h.feed.lastUpdateAt?.let { formatTime(it) } ?: "—")
        KeyValueRow("Veri yolu", if (h.feed.restFallbackActive) "REST yedeği" else "WebSocket")
        KeyValueRow("Yeniden bağlanma", "${h.feed.reconnects}")
        v.market?.let { m ->
            KeyValueRow("BTC filtresi", if (m.allowsLongs) "Alıma izin veriyor" else "Alım yok · ${m.blockKind?.title ?: ""}", marketBlockColor(m.blockKind))
        }
        if (d != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Son tarama · ${formatDateTime(d.boundary)}", style = MaterialTheme.typography.titleSmall)
            KeyValueRow("Taranan coin", "${d.scanned}")
            KeyValueRow("Likidite / spread nedeniyle elenen", "${d.eliminated}")
            if (d.flagged > 0) KeyValueRow("Riskli işaretlenen", "${d.flagged}")
            KeyValueRow("Trend / güç zayıf (aday olmadı)", "${d.notSelected}")
            KeyValueRow("Giriş koşulu bekleyen (kurulum yok)", "${d.noSetup}")
            KeyValueRow("Kuralların reddettiği", "${d.rejected}")
            if (d.staleData > 0) KeyValueRow("Verisi güncel olmayan", "${d.staleData}", colors.loss)
            KeyValueRow("İşleme uygun", "${d.eligible}", if (d.eligible > 0) colors.gain else Color.Unspecified)
            KeyValueRow("Açılan işlem", "${d.opened}", if (d.opened > 0) colors.gain else Color.Unspecified)
            if (d.topReasons.isNotEmpty()) {
                Text("En sık nedenler", style = MaterialTheme.typography.titleSmall)
                d.topReasons.take(5).forEach { (reason, count) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("$count coin", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Son alım sinyali", style = MaterialTheme.typography.titleSmall)
        val s = v.lastSignal
        if (s == null) {
            Text("Kayıtlı taramalarda (son 3 gün) strateji kurulumu oluşmadı.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            KeyValueRow("${s.symbol.removeSuffix("USDT")} · ${s.strategy}", formatDateTime(s.time))
            if (s.opened) Text("İşleme dönüştü.", style = MaterialTheme.typography.bodySmall, color = colors.gain)
            else {
                Text("İşleme dönüşmedi:", style = MaterialTheme.typography.bodySmall)
                s.blockedBy.take(4).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun ListHeader() {
    val style = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Coin / analiz", style = style, color = color, modifier = Modifier.weight(1f))
        Text("Fiyat / 24s", style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
        Text("İşlem", style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(100.dp))
    }
}

@Composable
private fun CoinRow(coin: CoinScan, outcome: CoinOutcome?, onClick: () -> Unit) {
    val (status, statusColor) = analysisLabel(coin.status)
    val (eligibility, eligibilityColor) = eligibilityLabel(outcome)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(coin.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 1)
        }
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
            Text(formatPrice(coin.price), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(formatPercent(coin.change24hPercent, signed = true, minFractionDigits = 2), style = MaterialTheme.typography.bodySmall, color = gainLossColor(coin.change24hPercent.compareTo(0.0)), maxLines = 1)
        }
        Row(Modifier.width(100.dp), horizontalArrangement = Arrangement.End) { Tag(eligibility, eligibilityColor) }
    }
}

// ---- Karar günlüğü (alt sayfa) ----

class DecisionLogViewModel(engine: TradingEngine) : ViewModel() {
    val scanLogs = engine.scanLogs
}

@Composable
fun DecisionLogScreen(modifier: Modifier = Modifier, viewModel: DecisionLogViewModel = viewModel(factory = AppViewModels.Factory)) {
    val logs by viewModel.scanLogs.collectAsStateWithLifecycle()
    DecisionLogContent(logs, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecisionLogContent(scanLogs: List<ScanLog>, modifier: Modifier = Modifier, expanded: Boolean = false) {
    var coinFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val symbols = scanLogs.lastOrNull()?.coins?.map { it.symbol }.orEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        item {
            Text(
                "Her 15 dakikalık taramada her coin için karar, gerekçe ve kullanılan verinin zamanı. Strateji ve kural incelemesi yalnız en iyi 2–4 adaya yapılır. Ayrıntı için satıra dokunun.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (symbols.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = coinFilter == null, onClick = { coinFilter = null }, label = { Text("Tümü") })
                    symbols.forEach { s -> FilterChip(selected = coinFilter == s, onClick = { coinFilter = s }, label = { Text(s.removeSuffix("USDT")) }) }
                }
            }
        }
        if (scanLogs.isEmpty()) item { EmptyText("Henüz tarama kaydı yok.") }
        items(scanLogs.reversed(), key = { it.time }) { log -> ScanLogCard(log, coinFilter, expanded) }
    }
}

@Composable
private fun ScanLogCard(log: ScanLog, coinFilter: String?, expanded: Boolean) {
    val coins = log.coins.filter { coinFilter == null || it.symbol == coinFilter }
    if (coins.isEmpty()) return
    SectionCard(
        "Tarama ${formatDateTime(log.boundary)}",
        trailing = { StatusPill(if (log.marketAllowsLongs) "Piyasa uygun" else "Piyasa filtresi", if (log.marketAllowsLongs) SpotBotTheme.tradingColors.gain else SpotBotTheme.tradingColors.loss) },
    ) {
        Text(
            "Profil: ${log.profile.title} · karar anı ${formatTime(log.time)}${if (log.entriesPaused) " · yeni işlemler duraklatılmıştı" else ""}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!log.marketAllowsLongs) Text(log.marketReasons.joinToString(), style = MaterialTheme.typography.bodySmall)
        coins.sortedBy { it.outcome.ordinal }.forEach { DecisionRow(it, expanded || coinFilter != null) }
    }
}

@Composable
private fun DecisionRow(coin: CoinDecision, initiallyExpanded: Boolean) {
    var open by rememberSaveable(coin.symbol, coin.data.m15Close) { mutableStateOf(initiallyExpanded && coin.strategies.isNotEmpty()) }
    val (label, color) = eligibilityLabel(coin.outcome)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(coin.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleSmall)
            Tag(label, color)
            Text(coin.outcome.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(coin.summary, style = MaterialTheme.typography.bodySmall)
        if (open) {
            coin.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            DataStampText(coin.data)
            coin.strategies.forEach { StrategyCheckView(it) }
        }
    }
}

@Composable
fun StrategyCheckView(s: com.spotbot.core.trading.StrategyCheck) {
    val colors = SpotBotTheme.tradingColors
    Text(
        "${s.strategy.code} ${s.strategy.title}: " + when {
            !s.applicable -> "değerlendirilmedi"
            !s.setupFound -> "kurulum yok"
            else -> "giriş ${formatPrice(s.entry ?: 0.0)}, stop ${formatPrice(s.stop ?: 0.0)}, hedef ${formatPrice(s.target ?: 0.0)}, net R:R %.2f, kalite %.0f".format(s.netRiskReward ?: 0.0, s.qualityScore ?: 0.0)
        },
        style = MaterialTheme.typography.labelLarge,
    )
    s.reasons.forEach { Text("   – $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    s.rules.sortedBy { !it.mandatory }.forEach { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (r.passed) "✓" else "✗", color = if (r.passed) colors.gain else colors.loss, fontWeight = FontWeight.Bold)
            Column {
                Text("${r.id} ${r.title}${if (r.mandatory) " (zorunlu)" else ""}", style = MaterialTheme.typography.bodySmall)
                Text(r.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DataStampText(d: DataStamp) {
    fun t(v: Long?) = v?.let { formatDateTime(it) } ?: "—"
    Text(
        "Veri zamanı — 15M: ${t(d.m15Close)} · 1H: ${t(d.h1Close)} · 4H: ${t(d.h4Close)} · fiyat: ${d.priceTime?.let { formatTime(it) } ?: "—"}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
