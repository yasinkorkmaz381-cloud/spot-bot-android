package com.spotbot.android.domain

import com.spotbot.android.assertDecimalEquals
import com.spotbot.android.domain.model.PaperAccount
import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.TradingPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PaperAccountTest {

    private val pair = TradingPair("BTC", "USDT")
    private val account = PaperAccount(
        initialBalance = BigDecimal("1000"),
        quoteBalance = BigDecimal("950"),
        position = Position(pair, BigDecimal("0.001"), BigDecimal("50000"), BigDecimal("50")),
    )

    @Test
    fun `values the open position at the current price`() {
        val snapshot = account.valuate { BigDecimal("60000") }
        assertDecimalEquals("60", snapshot.positionValue)
        assertDecimalEquals("1010", snapshot.totalValue)
        assertDecimalEquals("10", snapshot.pnl)
        assertEquals(1.0, snapshot.pnlPercent, 1e-9)
    }

    @Test
    fun `falls back to cost when the price is unknown`() {
        val snapshot = account.valuate { null }
        assertDecimalEquals("1000", snapshot.totalValue)
        assertDecimalEquals("0", snapshot.pnl)
    }

    @Test
    fun `flat account is worth its cash`() {
        val snapshot = PaperAccount.create(BigDecimal("1000")).valuate { BigDecimal.ONE }
        assertNull(snapshot.positionValue)
        assertDecimalEquals("1000", snapshot.totalValue)
    }
}
