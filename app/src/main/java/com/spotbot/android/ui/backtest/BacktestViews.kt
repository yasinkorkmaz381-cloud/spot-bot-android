package com.spotbot.android.ui.backtest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotbot.android.ui.common.CandleChart
import com.spotbot.android.ui.common.ChartLine
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.LineChart
import com.spotbot.android.ui.common.PriceMarker
import com.spotbot.android.ui.common.SectionCard
import com.spotbot.android.ui.common.Tag
import com.spotbot.android.ui.common.TimeMarker
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatQuantity
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import com.spotbot.core.analysis.Series
import com.spotbot.core.backtest.BacktestReport
import com.spotbot.core.backtest.Excursion
import com.spotbot.core.backtest.HistoricalDataset
import com.spotbot.core.backtest.IsolatedRun
import com.spotbot.core.backtest.PerformanceStats
import com.spotbot.core.backtest.TradeFeatures
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.trading.ClosedTrade
import java.time.Instant
import java.time.ZoneId

/** İşlem ayrıntı grafiği için veriler. Göstergeler ve seviyeler yalnız o ana kadar kapanmış mumlardan hesaplanır. */
data class TradeChartData(
    val candles: List<Candle>,
    val ema21: List<Double>,
    val ema55: List<Double>,
    /** Giriş anında (sinyal mumunun kapanışında) bilinen 1H destek/direnç seviyeleri. */
    val supports: List<Double>,
    val resistances: List<Double>,
    val structure: String,
)

object TradeCharts {
    /** Girişten önce ~10 saat, çıkıştan sonra ~2 saat (en fazla 200 mum). */
    fun build(trade: ClosedTrade, data: Map<String, HistoricalDataset>): TradeChartData? {
        val d = data[trade.position.symbol] ?: return null
        val m15 = Series(trade.position.symbol, Timeframe.M15, d.m15)
        val signal = trade.position.setup.signalTime
        val signalIndex = m15.indexAtOrBefore(signal)
        if (signalIndex < 0) return null
        val exitIndex = m15.indexAtOrBefore(trade.closedAt).coerceAtLeast(signalIndex)
        val from = (signalIndex - 40).coerceAtLeast(0)
        val to = minOf(m15.size - 1, exitIndex + 8, from + 199)
        val h1 = Series(trade.position.symbol, Timeframe.H1, d.h1)
        val h1Index = h1.indexAtOrBefore(signal)
        val analysis = if (h1Index >= 0) h1.analyze(h1Index) else null
        val entry = trade.position.setup.entryPrice
        return TradeChartData(
            candles = m15.candles.subList(from, to + 1),
            ema21 = m15.emaMid.copyOfRange(from, to + 1).toList(),
            ema55 = m15.emaSlow.copyOfRange(from, to + 1).toList(),
            supports = analysis?.supports?.map { it.price }?.filter { it < entry }?.take(2).orEmpty(),
            resistances = analysis?.resistances?.map { it.price }?.filter { it > entry }?.take(2).orEmpty(),
            structure = analysis?.let { a ->
                "Girişteki 1H yapı: ${a.structure.trend} (son tepe ${a.structure.lastHighLabel ?: "—"}, son dip ${a.structure.lastLowLabel ?: "—"})."
            } ?: "1H veri yok.",
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetricGrid(items: List<Triple<String, String, Color>>) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, value, color) -> Metric(label, value, Modifier.weight(1f), color) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun r(value: Double) = "%+.2fR".format(value)

/** Üst bölüm: sermaye, net sonuç, işlem, kazanma oranı, PF, düşüş, ortalama R, ücretler. */
@Composable
fun BacktestSummaryCard(r: BacktestReport) {
    val o = r.overall
    val net = r.finalEquity - r.initialCapital
    val neutral = Color.Unspecified
    SectionCard("Özet") {
        Text(
            "${formatDateTime(r.startTime)} → ${formatDateTime(r.endTime)} · ${r.symbols.size} coin · profil ${r.profile.title}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricGrid(
            listOf(
                Triple("Başlangıç sermayesi", "${formatMoney(r.initialCapital)} USDT", neutral),
                Triple("Bitiş sermayesi", "${formatMoney(r.finalEquity)} USDT", neutral),
                Triple("Net kâr / zarar", "${formatSignedMoney(net)} USDT", gainLossColor(net.compareTo(0.0))),
                Triple("Net getiri", formatPercent(r.netReturnPercent, signed = true, minFractionDigits = 2), gainLossColor(net.compareTo(0.0))),
                Triple("İşlem sayısı", "${r.trades} (${o.wins} K / ${o.losses} Z)", neutral),
                Triple("Kazanma oranı", formatPercent(r.winRatePercent, minFractionDigits = 1), neutral),
                Triple("Profit factor", formatRatio(r.profitFactor), neutral),
                Triple("Maks. düşüş", formatPercent(r.maxDrawdownPercent, minFractionDigits = 2), SpotBotTheme.tradingColors.loss),
                Triple("Ortalama R", r(r.expectancyR), gainLossColor(r.expectancyR.compareTo(0.0))),
                Triple("Toplam ücret", "${formatMoney(r.totalFees)} USDT", neutral),
            ),
        )
        HorizontalDivider()
        KeyValueRow("Brüt kâr / brüt zarar", "${formatMoney(o.grossProfit)} / ${formatMoney(o.grossLoss)}")
        KeyValueRow("Ort. kazanç / kayıp", "${formatSignedMoney(r.averageWin)} / ${formatSignedMoney(r.averageLoss)}")
        KeyValueRow("İşlem başına beklenen", "${formatSignedMoney(r.expectancy)} USDT")
        KeyValueRow("Spread + kayma", "${formatMoney(r.spreadAndSlippageCost)} USDT")
        KeyValueRow("BTC al-tut", r.btcBuyAndHoldPercent?.let { formatPercent(it, signed = true, minFractionDigits = 2) } ?: "—")
        KeyValueRow("Piyasada kalma", formatPercent(r.exposurePercent, minFractionDigits = 1))
        val ib = r.intrabar
        KeyValueRow(
            "Aynı mumda stop + hedef",
            if (ib.ambiguousBars == 0) "Görülmedi" else "${ib.ambiguousBars} · 5M ile ${ib.resolvedTargetFirst + ib.resolvedStopFirst} · belirsiz ${ib.unresolvedNoData + ib.unresolvedSameSubBar}",
        )
        if (r.entriesSkippedForDrift > 0) KeyValueRow("Fiyat kayması nedeniyle girilmedi", "${r.entriesSkippedForDrift}")
        if (r.partialFills > 0) KeyValueRow("Kısmi gerçekleşen emir (likidite)", "${r.partialFills}")
        if (r.equityCurve.size >= 2) LineChart(r.equityCurve.map { it.second })
    }
}

/** Ölçü tablosu: satırlar ölçüler, sütunlar stratejiler/çalıştırmalar. Değerler kısa tutulur, sağa hizalanır. */
@Composable
fun StatsTable(columns: List<Pair<String, PerformanceStats?>>) {
    val rows: List<Pair<String, (PerformanceStats) -> Pair<String, Color>>> = listOf(
        "İşlem" to { s -> "${s.trades}" to Color.Unspecified },
        "K / Z işlem" to { s -> "${s.wins} / ${s.losses}" to Color.Unspecified },
        "Kazanma oranı" to { s -> formatPercent(s.winRatePercent, minFractionDigits = 1) to Color.Unspecified },
        "Ort. kazanç" to { s -> formatSignedMoney(s.averageWin) to Color.Unspecified },
        "Ort. kayıp" to { s -> formatSignedMoney(s.averageLoss) to Color.Unspecified },
        "Profit factor" to { s -> formatRatio(s.profitFactor) to Color.Unspecified },
        "Beklenen (USDT)" to { s -> formatSignedMoney(s.expectancy) to Color.Unspecified },
        "Ortalama R" to { s -> r(s.averageR) to Color.Unspecified },
        "Maks. düşüş" to { s -> formatPercent(s.maxDrawdownPercent, minFractionDigits = 1) to Color.Unspecified },
        "Ücret" to { s -> formatMoney(s.fees) to Color.Unspecified },
        "Net sonuç" to { s -> formatSignedMoney(s.netPnl) to Color.Unspecified },
        "Net getiri" to { s -> formatPercent(s.netReturnPercent, signed = true, minFractionDigits = 2) to Color.Unspecified },
    )
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1.3f))
        columns.forEach { (title, _) ->
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    HorizontalDivider()
    rows.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f))
            columns.forEach { (_, stats) ->
                val (text, color) = stats?.let(value) ?: ("—" to Color.Unspecified)
                val signColor = when (label) {
                    "Net sonuç", "Net getiri", "Ortalama R", "Beklenen (USDT)" -> stats?.let { gainLossColor(it.netPnl.compareTo(0.0)) } ?: color
                    else -> color
                }
                Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = signColor, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Orta bölüm: aynı çalıştırmada A ve B. */
@Composable
fun StrategyCompareCard(r: BacktestReport) {
    SectionCard("Strateji A ve B") {
        Text(
            "Aynı çalıştırmadan: iki strateji aynı sermayeyi ve pozisyon sınırını paylaşır. Bağımsız karşılaştırma için \"A ve B ayrı\" modunu kullanın.",
            style = MaterialTheme.typography.bodySmall,
        )
        val byId = r.perStrategy.associate { it.strategy to it.stats }
        StatsTable(listOf("A" to byId[StrategyId.BREAKOUT_RETEST], "B" to byId[StrategyId.TREND_PULLBACK]))
        val small = r.perStrategy.filter { it.stats.trades < 30 }
        if (small.isNotEmpty()) {
            Text(
                "⚠ ${small.joinToString { it.strategy.code }}: işlem sayısı 30'un altında; sonuç rastlantıya çok açık.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
fun MonthlyReturnsCard(r: BacktestReport) {
    if (r.monthlyReturns.isEmpty()) return
    SectionCard("Aylık özsermaye değişimi") {
        r.monthlyReturns.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${m.tradesClosed} işlem", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp), textAlign = TextAlign.End)
                Text(
                    formatPercent(m.changePercent, signed = true, minFractionDigits = 2), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    color = gainLossColor(m.changePercent.compareTo(0.0)), textAlign = TextAlign.End, modifier = Modifier.width(88.dp),
                )
            }
        }
        Text("Ay sonu özsermayesi açık pozisyonların o anki değerini içerir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun monthOf(t: Long): String = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).let { "%04d-%02d".format(it.year, it.monthValue) }

/** Alt bölüm: tarih ve stratejiye göre süzülebilen işlem geçmişi; işleme dokununca ayrıntı açılır. */
@Composable
fun TradeHistoryCard(r: BacktestReport, onOpen: (ClosedTrade) -> Unit) {
    var strategy by rememberSaveable { mutableStateOf<String?>(null) }
    var month by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableIntStateOf(30) }
    val months = remember(r) { r.closedTrades.map { monthOf(it.position.openedAt) }.distinct().sorted() }
    val trades = remember(r, strategy, month) {
        r.closedTrades
            .filter { strategy == null || it.position.strategy.code == strategy }
            .filter { month == null || monthOf(it.position.openedAt) == month }
            .sortedByDescending { it.position.openedAt }
    }
    SectionCard("İşlem geçmişi") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = strategy == null, onClick = { strategy = null; shown = 30 }, label = { Text("Tüm stratejiler") })
            listOf(StrategyId.BREAKOUT_RETEST, StrategyId.TREND_PULLBACK).forEach { id ->
                FilterChip(selected = strategy == id.code, onClick = { strategy = id.code; shown = 30 }, label = { Text("${id.code} ${id.title}") })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = month == null, onClick = { month = null; shown = 30 }, label = { Text("Tüm tarihler") })
            months.forEach { m -> FilterChip(selected = month == m, onClick = { month = m; shown = 30 }, label = { Text(m) }) }
        }
        val net = trades.sumOf { it.netPnl }
        Text(
            "${trades.size} işlem · net ${formatSignedMoney(net)} USDT · ${trades.count { it.netPnl > 0 }} kazançlı / ${trades.count { it.netPnl <= 0 }} zararlı",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trades.take(shown).forEach { t -> TradeRow(t) { onOpen(t) } }
        if (trades.size > shown) TextButton(onClick = { shown += 30 }) { Text("Daha fazla göster (${trades.size - shown} kaldı)") }
        if (trades.isEmpty()) Text("Bu süzgeçle işlem yok.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TradeRow(t: ClosedTrade, onClick: () -> Unit) {
    val p = t.position
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.symbol.removeSuffix("USDT"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Tag(p.strategy.code, MaterialTheme.colorScheme.primary)
            if (t.note.contains("BELİRSİZ")) Tag("belirsiz", MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.weight(1f))
            Text("${formatSignedMoney(t.netPnl)} USDT", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = gainLossColor(t.netPnl.compareTo(0.0)))
        }
        Text("Giriş  ${formatDateTime(p.openedAt)} · ${formatPrice(p.entryPrice)}", style = MaterialTheme.typography.bodySmall)
        Text("Çıkış  ${formatDateTime(t.closedAt)} · ${formatPrice(t.averageExitPrice)}", style = MaterialTheme.typography.bodySmall)
        Text("${t.reason.title} · ${r(t.rMultiple)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

/** İşlem ayrıntısı: gerçek mumlar, giriş/stop/hedef/çıkış, EMA'lar, giriş anındaki S/R ve gerekçe. */
@Composable
fun TradeDetailContent(trade: ClosedTrade, features: TradeFeatures?, excursion: Excursion?, chart: TradeChartData?) {
    val p = trade.position
    val colors = SpotBotTheme.tradingColors
    SectionCard("${p.symbol} · ${p.strategy.code} ${p.strategy.title}") {
        KeyValueRow("Giriş", "${formatDateTime(p.openedAt)} @ ${formatPrice(p.entryPrice)}")
        KeyValueRow("Çıkış", "${formatDateTime(trade.closedAt)} @ ${formatPrice(trade.averageExitPrice)}")
        KeyValueRow("Net sonuç", "${formatSignedMoney(trade.netPnl)} USDT (${r(trade.rMultiple)})", gainLossColor(trade.netPnl.compareTo(0.0)))
        KeyValueRow("Çıkış nedeni", trade.reason.title)
        KeyValueRow("Stop / hedef", "${formatPrice(p.initialStopPrice)} / ${formatPrice(p.targetPrice)}")
        KeyValueRow("Miktar · ücret", "${formatQuantity(p.initialQuantity)} · ${formatMoney(trade.fees)} USDT")
        if (trade.note.isNotBlank()) Text(trade.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard("Grafik (15M)") {
        if (chart == null) {
            Text("Bu işlem için mum verisi yok.", style = MaterialTheme.typography.bodyMedium)
        } else {
            CandleChart(
                chart.candles,
                lines = listOf(ChartLine(chart.ema21, MaterialTheme.colorScheme.primary, "EMA21"), ChartLine(chart.ema55, MaterialTheme.colorScheme.tertiary, "EMA55")),
                markers = listOf(
                    PriceMarker(p.entryPrice, MaterialTheme.colorScheme.primary, "Giriş"),
                    PriceMarker(p.initialStopPrice, colors.loss, "Stop"),
                    PriceMarker(p.targetPrice, colors.gain, "Hedef"),
                    PriceMarker(p.invalidationPrice, MaterialTheme.colorScheme.outline, "Geçersizlik"),
                ) + chart.supports.map { PriceMarker(it, colors.gain.copy(alpha = 0.5f), "1H destek") } +
                    chart.resistances.map { PriceMarker(it, colors.loss.copy(alpha = 0.5f), "1H direnç") },
                timeMarkers = listOf(TimeMarker(p.openedAt, MaterialTheme.colorScheme.primary, "Giriş"), TimeMarker(trade.closedAt - Timeframe.M15.millis, MaterialTheme.colorScheme.tertiary, "Çıkış")),
                shadeFrom = p.openedAt,
                shadeLabel = "Giriş sonrası (karar anında bilinmiyordu)",
            )
            Text(
                "Mavi EMA21, turuncu EMA55. Kesikli çizgiler giriş, stop, hedef, geçersizlik ve girişte bilinen 1H destek/direnç seviyeleridir. ${chart.structure}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionCard("Karar anındaki gerekçe") {
        Text("Yalnız sinyal mumunun kapanışına kadar oluşmuş veriler; giriş sonrası hareket gerekçe değildir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeyValueRow("Neden bu coin", p.rationale.whyCoin)
        KeyValueRow("Neden şimdi", p.rationale.whyEntry)
        KeyValueRow("Trend", p.rationale.trendCheck)
        KeyValueRow("Stop", p.rationale.stopReason)
        KeyValueRow("Risk/getiri", p.rationale.riskReward)
        KeyValueRow("Geçersizlik", p.rationale.invalidation)
        KeyValueRow("Teyitler", p.setup.confirmations.joinToString())
        if (p.setup.confluences.isNotEmpty()) KeyValueRow("Yapısal uyum", p.setup.confluences.joinToString())
        KeyValueRow("Kalite puanı", "%.0f / 100".format(p.qualityScore))
        features?.let { f ->
            HorizontalDivider()
            KeyValueRow("Piyasa rejimi (BTC)", f.marketRegime?.title ?: "—")
            KeyValueRow("Coin 4H rejimi", f.coinRegime?.title ?: "—")
            KeyValueRow("4H / 1H trend", "${f.h4Trend} / ${f.h1Trend}")
            KeyValueRow("ADX 1H / 4H", "%.1f / %.1f".format(f.adx1h, f.adx4h))
            KeyValueRow("RSI 1H / 15M", "%.1f / %.1f".format(f.rsi1h, f.rsi15m))
            KeyValueRow("15M hacim / ortalama", "%.2f×".format(f.volumeRatio15m))
            KeyValueRow("Stop mesafesi", "%.2f ATR(1H) · %%%.2f".format(f.stopDistanceAtr1h, f.stopDistancePercent))
            KeyValueRow("1H RSI negatif uyumsuzluk", if (f.bearishRsiDivergence1h) "Var" else "Yok")
        }
    }
    excursion?.let { e ->
        SectionCard("İşlem süresince (sonradan ölçüldü)") {
            KeyValueRow("En iyi hareket (lehte)", r(e.mfeR))
            KeyValueRow("En kötü hareket (aleyhte)", r(e.maeR))
            KeyValueRow("Süre", "${e.barsHeld} mum (15M)")
        }
    }
}

/** "A ve B ayrı" modunun sonucu: aynı koşullarda bağımsız çalıştırmalar, tüm dönem ve iki yarı. */
@Composable
fun IsolatedContent(results: List<IsolatedRun>, days: Int, symbols: List<String>) {
    SectionCard("A ve B ayrı · $days gün · ${symbols.size} coin") {
        Text(
            "Her sütun ayrı bir backtest: aynı veri, dönem, ücret, kayma, başlangıç sermayesi ve risk ayarları; yalnız etkin strateji farklı. Kâr garantisi değildir.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    listOf<Pair<String, (IsolatedRun) -> PerformanceStats>>(
        "Tüm dönem" to { it.full }, "İlk yarı" to { it.firstHalf }, "İkinci yarı" to { it.secondHalf },
    ).forEach { (title, pick) ->
        SectionCard(title) {
            StatsTable(results.map { run -> run.label.substringBefore(" (") to pick(run) })
        }
    }
}
