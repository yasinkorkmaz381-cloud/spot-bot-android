package com.spotbot.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotbot.android.bot.BacktestPeriod
import com.spotbot.android.bot.BacktestState
import com.spotbot.android.data.ArchiveSummary
import com.spotbot.android.data.ArchivedTrade
import com.spotbot.android.ui.backtest.BacktestContent
import com.spotbot.android.ui.panel.PanelContent
import com.spotbot.android.ui.scan.ScanContent
import com.spotbot.android.ui.settings.SettingsContent
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.trades.TradesContent
import com.spotbot.android.ui.trades.TradesTab
import com.spotbot.core.rs1.BotStatus
import com.spotbot.core.rs1.BtcState
import com.spotbot.core.rs1.CoinCheck
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.Rs1Condition
import com.spotbot.core.rs1.Rs1ScanResult
import com.spotbot.core.rs1.ScanRow
import com.spotbot.core.rs1.ScanStage
import com.spotbot.core.rs1.ScanState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ekran görüntüleri (yalnız -Pscreenshots ile kaydedilir). Veriler RS-1 motorunun sentetik veride ürettiği sonuçlardır. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1500dp-xhdpi")
open class ScreenshotTest {
    protected open val prefix: String = ""

    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            SpotBotTheme(darkTheme = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$prefix$name.png")
    }

    private val paper = SampleData.paper()
    private val now = SampleData.end + 3_600_000

    private fun row(s: String, r30: Double, r7: Double, rs: Double, vol: Double, spread: Double, failed: List<Rs1Condition> = emptyList(), candidate: Boolean = false, sectors: List<String> = listOf("Main")) =
        ScanRow(
            s, sectors, 10.0, spread, CoinCheck(s, failed.filter { it != Rs1Condition.SPREAD && it != Rs1Condition.DEPTH }, 10.0, r7, r30, rs, vol, 1.1, 0.4, 9.0, 400),
            bidDepth = 9e5, askDepth = 9e5, askVwap = 10.01, mid = 10.0, estimatedSlippagePercent = 0.1, takerFee = 0.0008, quantityStep = 0.01, minNotional = 1.0,
            eligible = failed.isEmpty(), candidate = candidate, held = false, failed = failed,
        )

    private val scan = ScanState(
        result = Rs1ScanResult(
            time = now, dailyClose = SampleData.end, btc = BtcState(true, false, 74_842.0, 71_120.0, 6.2), totalPairs = 1414, analyzed = 77,
            liquidityPassed = 10, trendPassed = 4, eligible = 2,
            stages = linkedMapOf(
                ScanStage.PRODUCT.title to 210, ScanStage.NOT_TRADING.title to 108, ScanStage.LOW_VOLUME_24H.title to 1019,
                ScanStage.VOLUME.title to 55, ScanStage.TREND.title to 6, ScanStage.RELATIVE_STRENGTH.title to 1, ScanStage.DEPTH.title to 1,
            ),
            top = listOf(
                row("XMRUSDT", 18.4, 3.1, 12.2, 8.1e6, 0.06, candidate = true, sectors = listOf("Main", "Privacy")),
                row("SOLUSDT", 11.0, 1.4, 4.8, 120e6, 0.01, candidate = true, sectors = listOf("Main", "LAYER 1 / LAYER 2")),
                row("PEPEUSDT", 9.0, 2.0, 2.8, 6.5e6, 0.12, listOf(Rs1Condition.RELATIVE_STRENGTH), sectors = listOf("MEME")),
                row("INJUSDT", 7.1, -2.0, 0.9, 5.2e6, 0.16, listOf(Rs1Condition.RETURN_7, Rs1Condition.RELATIVE_STRENGTH, Rs1Condition.SPREAD)),
            ),
            candidates = listOf("XMRUSDT", "SOLUSDT"), noTradeReason = null, nextWeeklyEvaluation = now + 5 * DAY_MS, newListings = listOf("ABCUSDT"),
            requiredDepth = 750.0, requests = 82, durationMs = 18_000,
        ),
        updatedAt = now,
    )

    @Test
    fun panel() = capture("1-panel") {
        PanelContent(paper, scan, BotStatus(running = true, lastCycleAt = now, weeklyRecorded = true), true, SampleData.cfg, {}, {}, now = now)
    }

    @Test
    fun scanScreen() = capture("2-tarama") { ScanContent(scan, paper.result!!.weekly.lastOrNull(), false, {}) }

    @Test
    fun openPositions() = capture("3-islemler-acik") { TradesContent(paper, ArchiveSummary(emptyList(), emptyList(), null)) }

    @Test
    fun history() = capture("4-islemler-gecmis") { TradesContent(paper, ArchiveSummary(emptyList(), emptyList(), null), initialTab = TradesTab.HISTORY) }

    @Test
    fun weekly() = capture("5-haftalik") { TradesContent(paper, ArchiveSummary(emptyList(), emptyList(), null), initialTab = TradesTab.WEEKLY) }

    @Test
    fun archive() = capture("6-arsiv") {
        val trades = listOf(
            ArchivedTrade("Aktif portföy (eski)", "ETHUSDT", "B", now - 40 * DAY_MS, now - 39 * DAY_MS, -4.2, "STOP"),
            ArchivedTrade("Gölge portföy (A)", "SOLUSDT", "A", now - 30 * DAY_MS, now - 29 * DAY_MS, 2.9, "TARGET"),
        )
        TradesContent(paper, ArchiveSummary(trades, listOf("portfolio.json", "shadows.json", "BILGI.txt"), "taşındı"), initialTab = TradesTab.ARCHIVE)
    }

    @Test
    fun backtest() = capture("7-backtest") { BacktestContent(BacktestState.Done(SampleData.result(closeAtEnd = true), BacktestPeriod.HOLDOUT, 0), { _, _, _ -> }) }

    @Test
    fun settings() = capture("8-ayarlar") { SettingsContent(true, SampleData.cfg, {}, {}) }
}

/** Küçük ekran (320 dp): aynı ekranlar "k-" önekiyle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h640dp-xhdpi")
class SmallScreenTest : ScreenshotTest() {
    override val prefix = "k-"
}
