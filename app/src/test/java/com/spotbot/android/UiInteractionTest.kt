package com.spotbot.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.spotbot.android.ui.common.ExpandableCard
import com.spotbot.android.ui.common.KeyValueRow
import com.spotbot.android.ui.panel.PanelActions
import com.spotbot.android.ui.panel.PanelContent
import com.spotbot.android.ui.panel.PanelUiState
import com.spotbot.android.ui.panel.PositionRow
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.trades.TradeDetailContent
import com.spotbot.android.ui.trades.TradesContent
import com.spotbot.core.engine.EngineHealth
import com.spotbot.core.engine.EngineStatus
import com.spotbot.core.risk.CostModel
import com.spotbot.core.strategy.StrategyId
import com.spotbot.core.strategy.TradeSetup
import com.spotbot.core.trading.ExitReason
import com.spotbot.core.trading.PaperAccount
import com.spotbot.core.trading.PortfolioState
import com.spotbot.core.trading.TradeRationale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Düğmelerin gerçekten çalıştığını (doğru eylemi çağırdığını) dokunma benzetimiyle doğrular. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class UiInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val costs = CostModel()
    private val rationale = TradeRationale("coin", "giriş", "trend", "stop", "rr", "iptal")
    private val setup = TradeSetup(
        "ETHUSDT", StrategyId.TREND_PULLBACK, 0, 100.0, 98.0, 105.0, 98.5, listOf("RSI", "MACD"), listOf("EMA bölgesi"),
        "Düzeltme EMA bölgesinde tutundu", "Dibin altı", "Önceki tepe", "1H kapanış dibin altında",
    )

    /** Bir açık pozisyon ve bir kapanmış işlem içeren sanal hesap. */
    private val portfolio: PortfolioState by lazy {
        var s = PaperAccount.open(PortfolioState.create(1_000.0), setup.copy(symbol = "SOLUSDT"), 1.0, 100.0, costs, 5.0, 60.0, rationale, null, 0)
        s = PaperAccount.close(s, s.positions.single().id, 104.0, ExitReason.TAKE_PROFIT, "hedef", costs, 3_600_000)
        PaperAccount.open(s, setup, 1.0, 100.0, costs, 5.0, 60.0, rationale, null, 7_200_000)
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        SpotBotTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
    }

    private class Calls {
        val log = mutableListOf<String>()
        fun actions() = PanelActions(
            start = { log += "start" }, stop = { log += "stop" }, setPaused = { log += "pause:$it" }, closePosition = { log += "close:$it" },
            openSymbol = { log += "symbol:$it" }, cancelPending = { log += "cancel:$it" }, openTrade = { log += "trade:$it" }, openEvents = { log += "events" },
        )
    }

    private fun panelState(status: EngineStatus, paused: Boolean = false): PanelUiState {
        val p = portfolio.positions.single()
        return PanelUiState(
            health = EngineHealth(status = status, entriesPaused = paused), equity = 1_000.0, cash = 900.0, initialCapital = 1_000.0,
            positions = listOf(PositionRow(p, 101.0, 0.8, 0.16)), dayStartEquity = 995.0,
            recentTrades = portfolio.closedTrades.reversed(),
        )
    }

    @Test
    fun `stopped bot can only be started`() {
        val calls = Calls()
        show { PanelContent(panelState(EngineStatus.STOPPED), calls.actions()) }
        compose.onNodeWithText("Durdur").assertIsNotEnabled()
        compose.onNodeWithText("Duraklat").assertIsNotEnabled()
        compose.onNodeWithText("Başlat").assertIsEnabled().performClick()
        assertEquals(listOf("start"), calls.log)
    }

    @Test
    fun `running bot pauses new entries and stops only after confirmation`() {
        val calls = Calls()
        show { PanelContent(panelState(EngineStatus.RUNNING), calls.actions()) }
        compose.onNodeWithText("Başlat").assertIsNotEnabled()
        compose.onNodeWithText("Duraklat").performClick()
        compose.onNodeWithText("Durdur").performClick()
        assertEquals(listOf("pause:true"), calls.log)
        compose.onNodeWithText("Bot durdurulsun mu?").assertExists()
        compose.onAllNodesWithText("Durdur")[1].performClick()
        assertEquals(listOf("pause:true", "stop"), calls.log)
    }

    @Test
    fun `paused bot resumes with start`() {
        val calls = Calls()
        show { PanelContent(panelState(EngineStatus.RUNNING, paused = true), calls.actions()) }
        compose.onNodeWithText("Duraklatıldı").assertExists()
        compose.onNodeWithText("Başlat").performClick()
        assertEquals(listOf("pause:false"), calls.log)
    }

    @Test
    fun `panel rows open the trade detail and event log`() {
        val calls = Calls()
        show { PanelContent(panelState(EngineStatus.RUNNING), calls.actions()) }
        compose.onNodeWithText("ETH").performClick()
        compose.onNodeWithText("SOL · B").performScrollTo().performClick()
        compose.onNodeWithText("Olay günlüğü").performScrollTo().performClick()
        val open = portfolio.positions.single().id
        val closed = portfolio.closedTrades.single().position.id
        assertEquals(listOf("trade:$open", "trade:$closed", "events"), calls.log)
    }

    @Test
    fun `details are hidden until the section is opened`() {
        show { ExpandableCard("Ayrıntılar") { KeyValueRow("Gizli ölçü", "42") } }
        compose.onNodeWithText("Gizli ölçü").assertDoesNotExist()
        compose.onNodeWithText("Ayrıntılar").performClick()
        compose.onNodeWithText("Gizli ölçü").assertExists()
    }

    @Test
    fun `trades screen switches between open and history and opens a trade`() {
        val opened = mutableListOf<String>()
        show { TradesContent(portfolio, mapOf("ETHUSDT" to 101.0), { opened += it }) }
        compose.onNodeWithText("ETH").performClick()
        compose.onNodeWithText("Geçmiş").performClick()
        compose.onNodeWithText("SOL").performClick()
        assertEquals(listOf(portfolio.positions.single().id, portfolio.closedTrades.single().position.id), opened)
    }

    @Test
    fun `manual close asks for confirmation`() {
        var closed = 0
        var symbolOpened = 0
        show { TradeDetailContent(portfolio.positions.single(), null, 101.0, emptyList(), onClose = { closed++ }, onOpenSymbol = { symbolOpened++ }) }
        compose.onNodeWithText("Coin analizi").performScrollTo().performClick()
        compose.onNodeWithText("Manuel kapat").performScrollTo().performClick()
        assertEquals(0, closed)
        compose.onNodeWithText("Kapat").performClick()
        assertEquals(1, closed)
        assertEquals(1, symbolOpened)
        assertTrue(portfolio.positions.single().symbol == "ETHUSDT")
    }

    @Test
    fun `market filter block shows its kind and a plain explanation instead of a code`() {
        val state = panelState(EngineStatus.RUNNING).let {
            it.copy(
                risk = com.spotbot.core.trading.RiskStatus(
                    entriesBlockedReason = "Piyasa riski: BTC 4 saatlik grafikte düşüş trendinde (fiyat ortalamaların altında, trend gücü yüksek). Bu durumda yeni alım yapılmaz; açık pozisyonların takibi sürer.",
                    marketBlockKind = com.spotbot.core.scanner.MarketBlockKind.MARKET,
                ),
            )
        }
        show { PanelContent(state, Calls().actions()) }
        compose.onNodeWithText("Yeni işlem yok · Piyasa riski").assertExists()
        compose.onNodeWithText("BTC 4 saatlik grafikte düşüş trendinde", substring = true).assertExists()
        compose.onNodeWithText("BTC / piyasa filtresi alıma izin vermiyor").assertDoesNotExist()
    }

    @Test
    fun `panel shows the real reason of the last scan when nothing else blocks`() {
        val state = panelState(EngineStatus.RUNNING).copy(scanReason = "15M giriş teyidi bekleniyor: 2 adayda kurulum henüz oluşmadı.")
        show { PanelContent(state, Calls().actions()) }
        compose.onNodeWithText("Son taramada işlem açılmadı").assertExists()
        compose.onNodeWithText("15M giriş teyidi bekleniyor", substring = true).assertExists()
    }
}
