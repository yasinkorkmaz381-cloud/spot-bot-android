package com.spotbot.android

import com.spotbot.core.kr.CmcAsset
import com.spotbot.core.kr.CmcList
import com.spotbot.core.kr.ExitReason
import com.spotbot.core.kr.ExitSource
import com.spotbot.core.kr.Interval
import com.spotbot.core.kr.KrBook
import com.spotbot.core.kr.KrPosition
import com.spotbot.core.kr.KrState
import com.spotbot.core.kr.KrTrade
import com.spotbot.core.kr.Leg
import com.spotbot.core.kr.LogEntry
import com.spotbot.core.kr.Opportunity
import com.spotbot.core.kr.ScanSummary
import com.spotbot.core.kr.SetupStage
import com.spotbot.core.kr.Universe
import com.spotbot.core.kr.UniverseEntry
import com.spotbot.core.kr.UniverseStatus

/** Ekran görüntüleri için örnek paper durumu (sentetik; gerçek sonuç değildir). */
object SampleData {
    /** 2026-11-14 14:35 UTC (17:35 TSİ). */
    const val NOW = 1_794_666_900_000L
    private const val MIN = 60_000L
    private val leg = Leg(0.0008, 0.0004, 0.0005)

    private fun pos(sym: String, entry: Double, stop: Double, target: Double, qty: Double, minutesAgo: Long) = KrPosition(
        id = "$sym-1", symbol = sym, setupId = "$sym@1", entryTime = NOW - minutesAgo * MIN, refPrice = entry, entryPrice = entry, quantity = qty,
        stop = stop, target = target, level = (entry + stop) / 2, invalidation = stop, entryLeg = leg, exitLeg = leg, entryFee = entry * qty * leg.fee,
        plannedNetRewardRisk = 1.38, riskAmount = 2.5, highest = entry,
    )

    private fun trade(sym: String, entry: Double, exit: Double, qty: Double, reason: ExitReason, hoursAgo: Long, source: ExitSource = ExitSource.LIVE, ambiguous: Boolean = false): KrTrade {
        val fees = (entry + exit) * qty * leg.fee
        return KrTrade(
            id = "$sym-$hoursAgo", symbol = sym, setupId = "s", entryTime = NOW - hoursAgo * 60 * MIN, entryPrice = entry, quantity = qty,
            stop = entry * 0.988, target = entry * 1.016, level = entry * 0.996, exitTime = NOW - hoursAgo * 60 * MIN + 95 * MIN, exitPrice = exit,
            reason = reason, grossPnl = (exit - entry) * qty, fees = fees, netPnl = (exit - entry) * qty - fees, riskAmount = 2.5,
            plannedNetRewardRisk = 1.3, ambiguous = ambiguous, source = source, entryLeg = leg, exitLeg = leg,
        )
    }

    fun state(open: Boolean = true): KrState {
        val positions = if (open) listOf(pos("SUIUSDT", 1.8432, 1.8214, 1.8751, 108.0, 40), pos("AVAXUSDT", 21.37, 21.08, 21.79, 9.3, 12)) else emptyList()
        val trades = listOf(
            trade("LINKUSDT", 14.82, 15.06, 13.4, ExitReason.TARGET, 5),
            trade("ARBUSDT", 0.3412, 0.3368, 580.0, ExitReason.STOP, 9),
            trade("NEARUSDT", 2.214, 2.219, 88.0, ExitReason.NO_PROGRESS, 26, ExitSource.BACKFILLED),
            trade("APTUSDT", 4.871, 4.812, 41.0, ExitReason.STOP, 30, ambiguous = true),
        )
        val cash = 1000.0 + trades.sumOf { it.netPnl } - positions.sumOf { it.notional + it.entryFee }
        val assets = listOf(
            CmcAsset(1, "Bitcoin", "BTC", "bitcoin", 1), CmcAsset(825, "Tether USDt", "USDT", "tether", 3, listOf("stablecoin")),
            CmcAsset(20396, "Sui", "SUI", "sui", 18), CmcAsset(5805, "Avalanche", "AVAX", "avalanche", 20), CmcAsset(3155, "UNUS SED LEO", "LEO", "leo", 13),
        )
        val universe = Universe(
            CmcList(NOW - 80 * MIN, NOW - 80 * MIN, assets),
            listOf(
                UniverseEntry(assets[0], UniverseStatus.INCLUDED, "BTCUSDT"), UniverseEntry(assets[1], UniverseStatus.EXCLUDED_CATEGORY, note = "CMC etiketi: stablecoin"),
                UniverseEntry(assets[2], UniverseStatus.INCLUDED, "SUIUSDT"), UniverseEntry(assets[3], UniverseStatus.INCLUDED, "AVAXUSDT"),
                UniverseEntry(assets[4], UniverseStatus.NOT_ON_MEXC),
            ),
            NOW - 80 * MIN,
        )
        val items = listOf(
            Opportunity("INJUSDT", "Injective", 74, SetupStage.ENTRY_READY, "Retest onaylandı"),
            Opportunity("TAOUSDT", "Bittensor", 31, SetupStage.AWAITING_RETEST, "Seviye 412,8 kırıldı; retest bekleniyor (3/8)"),
            Opportunity("RENDERUSDT", "Render", 58, SetupStage.AWAITING_RETEST, "Seviye 2,914 kırıldı; retest bekleniyor (1/8)"),
            Opportunity("BTCUSDT", "Bitcoin", 1, SetupStage.WATCHING, "Direnç 69.212,0 (%0,6 yukarıda)"),
            Opportunity("ETHUSDT", "Ethereum", 2, SetupStage.WATCHING, "Direnç 2.512,3 (%1,1 yukarıda)"),
            Opportunity("SOLUSDT", "Solana", 7, SetupStage.NOT_SUITABLE, "4H yön yükselişte değil"),
            Opportunity("DOGEUSDT", "Dogecoin", 9, SetupStage.NOT_SUITABLE, "Önceden belirlenmiş 1H direnç yok"),
            Opportunity("SUIUSDT", "Sui", 18, SetupStage.NOT_SUITABLE, "Açık pozisyon var"),
        )
        return KrState(
            createdAt = NOW - 3 * 24 * 60 * MIN,
            book = KrBook(cash, positions, trades, "2026-11-14", 998.7),
            lastMonitorAt = NOW - MIN / 2, lastPriceDataAt = NOW - MIN / 2, lastScanBar = NOW - 5 * MIN,
            universe = universe, termsAt = NOW - 3 * 60 * MIN,
            scan = ScanSummary(NOW - 5 * MIN, NOW - 4 * MIN, 300, 215, 211, 211, items, mapOf("Kırılım: kabul" to 3, "Kırılım: Kırılım hacmi düşük" to 7)),
            untracked = listOf(Interval(NOW - 26 * 60 * MIN, NOW - 24 * 60 * MIN)),
            log = listOf(LogEntry(NOW - 40 * MIN, "SUIUSDT alındı (paper): giriş 1,8432, stop 1,8214, hedef 1,8751, net R:R 1,38")),
            funnel = mapOf("Kırılım: kabul" to 41, "Kırılım: Kırılım hacmi düşük" to 133, "Retest onaylandı (evrende)" to 19, "Giriş yapıldı" to 6, "Süzgeç: BTC'ye göre zayıf" to 7),
            marks = mapOf("SUIUSDT" to 1.8511, "AVAXUSDT" to 21.31),
        )
    }
}
