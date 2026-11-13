package com.spotbot.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotbot.android.bot.HistoryRunner
import com.spotbot.android.bot.HistoryState
import com.spotbot.android.ui.history.HistoryContent
import com.spotbot.android.ui.history.HistoryPeriod
import com.spotbot.android.ui.history.buildRequest
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.kr.CandleFileStore
import com.spotbot.core.kr.CmcException
import com.spotbot.core.kr.CmcHistorySource
import com.spotbot.core.kr.CmcList
import com.spotbot.core.kr.CmcListFileCache
import com.spotbot.core.kr.CmcSource
import com.spotbot.core.kr.FakeKrMarket
import com.spotbot.core.kr.KrConfig.Companion.DAY
import com.spotbot.core.kr.KrHistory
import com.spotbot.core.kr.KrReport
import com.spotbot.core.kr.KrTrade
import com.spotbot.core.kr.Scenario
import com.spotbot.core.kr.UniverseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Geçmiş test ekranı uçtan uca: düğmeye basılır, gerçek [KrHistory] hattı ve gerçek backtest motoru sahte MEXC
 * verisiyle çalışır, sonuç ekranda görünür, CSV üretilir. Ekran görüntüleri -Pscreenshots ile kaydedilir.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h1300dp-xhdpi")
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val setup = Scenario.setup()
    private val btc = Scenario.warmup(setup.bars.size, start = 50_000.0, step = 0.0002)
    private val m15 = mapOf("XUSDT" to setup.bars.toList(), "BTCUSDT" to btc.toList())
    private val end = setup.bars.last().closeTime

    private fun list(day: Long): CmcList {
        fun px(sym: String) = m15.getValue(sym).last { it.closeTime <= day }.close
        return CmcList(day, day, listOf(Scenario.asset(1, "BTC", 1, px("BTCUSDT")), Scenario.asset(2, "X", 2, px("XUSDT"))), CmcList.SOURCE_HISTORICAL)
    }

    private fun runner(history: CmcHistorySource): HistoryRunner {
        val api = FakeKrMarket(m15, listOf(Scenario.info("XUSDT"), Scenario.info("BTCUSDT")), end)
        val engine = KrHistory(api, CmcSource { list((end - 1) - Math.floorMod(end - 1, DAY)) }, history,
            CandleFileStore(tmp.root.resolve("c")), CmcListFileCache(tmp.root.resolve("l")), now = { api.clock }, cmcPaceMillis = 0)
        return HistoryRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { req, p -> engine.run(req.from, req.to, req.equity, onProgress = p) }
    }

    private fun show(r: HistoryRunner, exported: MutableList<List<KrTrade>> = ArrayList()) {
        compose.setContent {
            SpotBotTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val st by r.state.collectAsStateWithLifecycle()
                    HistoryContent(st, true, end, onStart = r::start, onCancel = r::cancel, onReset = r::reset, onExport = { exported += it })
                }
            }
        }
    }

    private fun waitDone(r: HistoryRunner) = compose.waitUntil(60_000) { r.state.value !is HistoryState.Running }

    @Test
    fun `start button runs the real engine and shows results, trades and CSV`() {
        val r = runner(CmcHistorySource { list(it) })
        val exported = ArrayList<List<KrTrade>>()
        show(r, exported)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/10-gecmis-test-form.png")
        compose.onNodeWithTag("equity").performTextReplacement("2500")
        compose.onNodeWithTag("start").performClick()
        waitDone(r)
        val done = r.state.value as HistoryState.Done
        assertEquals(UniverseSource.HISTORICAL, done.outcome.source)
        assertEquals(2500.0, done.outcome.result.startEquity, 0.0)
        assertEquals(1, done.outcome.result.trades.size)
        compose.waitForIdle()
        compose.onNodeWithTag("summary").assertIsDisplayed()
        compose.onNodeWithText("İşlem sayısı").assertIsDisplayed()
        compose.onNodeWithText("Liste kaynağı").assertIsDisplayed()
        compose.onNodeWithText(done.outcome.source.title).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/11-gecmis-test-sonuc.png")
        compose.onNodeWithTag("export").performScrollTo().performClick()
        val csv = KrReport.csv(exported.single())
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("coin,giris_tsi,cikis_tsi,giris_fiyat,cikis_fiyat"))
        assertTrue(lines[1].startsWith("XUSDT,"))
        compose.onNodeWithText("XUSDT".removeSuffix("USDT")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `without historical CMC access the result is labelled as a today-list test`() {
        val r = runner(CmcHistorySource { throw CmcException("CMC planınız bu veriye izin vermiyor", 403, 1006) })
        show(r)
        compose.onNodeWithTag("start").performClick()
        waitDone(r)
        compose.waitForIdle()
        compose.onNodeWithTag("warning-today").assertIsDisplayed()
        compose.onNodeWithText(UniverseSource.TODAY.title).performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/12-gecmis-test-bugunku-liste-uyarisi.png")
    }

    @Test
    fun `progress is shown and cancel stops the test`() {
        val r = HistoryRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, p -> p(com.spotbot.core.kr.HistoryProgress("Mum verisi (15M + 4H)", 40, 186)); awaitCancellation() }
        show(r)
        compose.onNodeWithTag("start").performClick()
        compose.waitUntil(5_000) { (r.state.value as? HistoryState.Running)?.progress?.done == 40 }
        compose.waitForIdle()
        compose.onNodeWithTag("progress").assertIsDisplayed()
        compose.onNodeWithText("Mum verisi (15M + 4H) (40/186)").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/13-gecmis-test-ilerleme.png")
        compose.onNodeWithTag("cancel").performClick()
        compose.waitUntil(5_000) { r.state.value is HistoryState.Cancelled }
        compose.onNodeWithText("Test iptal edildi. İndirilen veriler önbellekte kaldı; tekrar başlatınca devam eder.").assertIsDisplayed()
    }

    @Test
    fun `period and custom dates build the right request`() {
        val now = 1_794_666_900_000L
        val today = now - Math.floorMod(now, DAY)
        val (r30, e30) = buildRequest(HistoryPeriod.D30, "", "", "1000", now)
        assertNull(e30)
        assertEquals(today - 30 * DAY, r30!!.from)
        val (custom, _) = buildRequest(HistoryPeriod.CUSTOM, "01.10.2026", "31.10.2026", "750,5", now)
        assertEquals(1_790_812_800_000L, custom!!.from)
        assertEquals(1_793_491_200_000L, custom.to)
        assertEquals(750.5, custom.equity, 0.0)
        assertNotNull(buildRequest(HistoryPeriod.CUSTOM, "31.10.2026", "01.10.2026", "1000", now).second)
        assertNotNull(buildRequest(HistoryPeriod.D90, "", "", "10", now).second)
    }
}
