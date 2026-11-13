package com.spotbot.android.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.android.ui.common.AppCard
import com.spotbot.android.ui.common.EmptyText
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.common.Spacing
import com.spotbot.android.ui.common.formatDateTime
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.core.kr.KrConfig
import com.spotbot.core.kr.KrReport
import com.spotbot.core.kr.KrState
import com.spotbot.core.kr.UniverseStatus

/** İsteğe bağlı ayrıntılar: kurallar, evren, huni, istatistik, veri zamanları, izleme boşlukları, günlük. */
@Composable
fun DetailContent(state: KrState, now: Long, cfg: KrConfig = KrConfig()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.cards),
    ) {
        ExpandableCard("Strateji: Kırılım–Retest", subtitle = "Tek düzen; bütün kurallar zorunlu, puanlama yok") {
            RULES.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        ExpandableCard("Risk sınırları", subtitle = "Başlangıç değerleri; optimum değildir") {
            val r = cfg.risk
            KeyValueRow("İşlem başına risk", "%${r.riskPercent}")
            KeyValueRow("Toplam açık stop riski", "≤ %${r.maxOpenRiskPercent}")
            KeyValueRow("En fazla açık pozisyon", "${r.maxPositions}")
            KeyValueRow("Coin başına / toplam sermaye", "%${r.maxPositionPercent.toInt()} / %${r.maxTotalPercent.toInt()}")
            KeyValueRow("Günlük kayıp sınırı (TSİ günü)", "%${r.dailyLossPercent}")
        }
        val u = state.universe
        ExpandableCard("Coin evreni", subtitle = u?.let { "İlk ${it.top} → MEXC'de ${it.onMexc} → taranan ${it.included.size}" } ?: "Henüz alınmadı") {
            if (u == null) EmptyText(state.universeError ?: "CMC listesi bekleniyor.")
            else {
                KeyValueRow("Kaynak", "CoinMarketCap (resmî API)")
                KeyValueRow("CMC veri zamanı", formatDateTime(u.list.cmcTimestamp))
                KeyValueRow("Alındığı an", formatDateTime(u.list.fetchedAt))
                UniverseStatus.entries.filter { it != UniverseStatus.INCLUDED }.forEach { s ->
                    val list = u.entries.filter { it.status == s }
                    if (list.isNotEmpty()) {
                        Text("${s.title} (${list.size})", style = MaterialTheme.typography.titleSmall)
                        Text(list.joinToString { "${it.asset.symbol} #${it.asset.rank}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        ExpandableCard("Kaçan fırsatlar (huni)", subtitle = "Son tarama ve paper başlangıcından beri") {
            val last = state.scan?.funnel.orEmpty()
            if (state.funnel.isEmpty()) EmptyText("Henüz kayıt yok.")
            state.funnel.entries.sortedByDescending { it.value }.forEach { (k, v) -> KeyValueRow(k, "${last[k] ?: 0} / $v") }
        }
        ExpandableCard("İstatistik (paper)", subtitle = "${state.book.trades.size} kapanmış işlem") {
            val t = state.book.trades
            if (t.isEmpty()) EmptyText("Kapanmış işlem yok.")
            else {
                val m = KrReport.metrics(t, cfg.risk.startEquity, emptyList(), state.createdAt, now)
                KeyValueRow("Net", "${formatSignedMoney(m.netPnl)} USDT")
                KeyValueRow("Kâr faktörü", "%.2f".format(m.profitFactor))
                KeyValueRow("Ortalama net R", "%.3f".format(m.meanR))
                KeyValueRow("Kazanan", "%${"%.1f".format(m.winRate)}")
                KeyValueRow("Ortalama elde tutma", "${m.avgHoldMinutes.toInt()} dk")
                KeyValueRow("Günde işlem / aktif gün başına", "%.2f / %.2f".format(m.tradesPerDay, m.tradesPerActiveDay))
                KeyValueRow("Maliyet / brüt kâr", "%${"%.1f".format(m.costToGrossProfit * 100)}")
                KeyValueRow("Geçmişten hesaplanan çıkış", "${t.count { it.source == com.spotbot.core.kr.ExitSource.BACKFILLED }}")
                InfoBanner("Bu kısa paper geçmişi stratejinin işe yaradığını göstermez; backtest raporu docs/KR-RAPOR.md'dedir.")
            }
        }
        ExpandableCard("Veri zamanları", subtitle = "Fiyat, CMC listesi ve haber ayrı tutulur") {
            KeyValueRow("Fiyat (MEXC)", if (state.lastPriceDataAt > 0) formatDateTime(state.lastPriceDataAt) else "Yok")
            KeyValueRow("CMC listesi", u?.let { formatDateTime(it.list.cmcTimestamp) } ?: "Yok")
            KeyValueRow("Ücret tablosu (MEXC)", if (state.termsAt > 0) formatDateTime(state.termsAt) else "Yok")
            KeyValueRow("Haber / makro takvim", "Kaynak yok — kontrol edilmedi")
            KeyValueRow("Elle girilen olay penceresi", "${state.events.size}")
        }
        ExpandableCard("İzleme boşlukları", subtitle = "Bot çalışmadığı ya da bağlantı olmadığı aralıklar (${state.untracked.size})") {
            if (state.untracked.isEmpty()) EmptyText("Boşluk yok.")
            state.untracked.takeLast(30).reversed().forEach { KeyValueRow(formatDateTime(it.from), "${formatTime(it.to)} (${(it.to - it.from) / 60_000} dk)") }
            InfoBanner("Bu aralıklarda olan çıkışlar \"canlı izlenmedi\" diye işaretlenir; geçmişten hesaplanan olay canlı izlenmiş gibi gösterilmez.")
        }
        ExpandableCard("Günlük", subtitle = "Son ${minOf(100, state.log.size)} kayıt") {
            if (state.log.isEmpty()) EmptyText("Kayıt yok.")
            state.log.takeLast(100).reversed().forEach { Text("${formatDateTime(it.time)}  ${it.text}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

val RULES = listOf(
    "4H yön: kapanış > EMA50 ve EMA50 son 6 mumda yükselmiş.",
    "1H direnç: fraktal tepe (3 sol, 3 sağ mum); sağ taraf kapanmadan seviye bilinmez. En fazla 72 saat eski, üstünde 15M kapanış olmamış.",
    "Kırılım: seviye önceden sabit; 15M mum seviyenin üstünde kapanır (fitil sayılmaz), hacim ≥ 1,5 × son 20 mum ortalaması.",
    "Retest: sonraki 8 mum içinde düşük ≤ seviye + 0,25 ATR, kapanış seviyenin üstünde ve yükseliş mumu. Kapanış seviye altına inerse iptal.",
    "Giriş: retest kapanışından sonra uygulanabilir fiyattan; fiyat seviye + 0,75 ATR'den yukarıdaysa kovalanmaz.",
    "Stop: retest yapısının en düşüğü − 0,25 ATR. Hedef: en yakın 1H direnç veya giriş + 1,5 × ATR(1H), hangisi yakınsa.",
    "Net R:R (komisyon + spread + kayma sonrası) ≥ 1,2; toplam maliyet brüt hedefin %40'ını aşmaz.",
    "Çıkış sırası: stop → hedef → yapı bozuldu (kapanış < seviye − 0,25 ATR) → 2 saatte hareket yok → 6 saat doldu.",
    "Giriş öncesi: BTC son 4 saatte %2'den fazla düşmemiş, coin 24 saatte BTC'den güçlü, 24 saatlik hacim ≥ 1 M USDT, spread ≤ %0,20, kayma ≤ %0,15.",
)

/** Arşiv: eski stratejilerin kayıtları; yalnız okunur, yeni stratejinin performansına karışmaz. */
@Composable
fun ArchiveContent(archive: ArchiveSummary?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.screen), verticalArrangement = Arrangement.spacedBy(Spacing.cards)) {
        InfoBanner("Bu kayıtlar eski stratejilere (A/B/C, Momentum RS-1) aittir. Silinmedi; Kırılım–Retest sonuçlarına dahil edilmez.")
        if (archive == null) { EmptyText("Yükleniyor…"); return@Column }
        if (archive.trades.isEmpty()) EmptyText("Arşivde işlem yok.")
        archive.trades.groupBy { it.portfolio }.forEach { (label, list) ->
            AppCard {
                Text(label, style = MaterialTheme.typography.titleMedium)
                KeyValueRow("İşlem / net", "${list.size} / ${formatSignedMoney(list.sumOf { it.netPnl })} USDT")
                list.take(30).forEach { KeyValueRow("${it.symbol} · ${formatDateTime(it.closedAt)}", formatSignedMoney(it.netPnl)) }
            }
        }
        if (archive.files.isNotEmpty()) AppCard {
            Text("Dosyalar", style = MaterialTheme.typography.titleMedium)
            Text(archive.files.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
