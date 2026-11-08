package com.spotbot.android.ui

import com.spotbot.android.ui.common.formatEditableDecimal
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatQuantity
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.parseDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class FormattersTest {

    private val en = Locale.US
    private val tr = Locale.forLanguageTag("tr-TR")

    @Test
    fun `price precision depends on magnitude`() {
        assertEquals("95,123.46", formatPrice(BigDecimal("95123.456"), en))
        assertEquals("95.123,46", formatPrice(BigDecimal("95123.456"), tr))
        assertEquals("2.4000", formatPrice(BigDecimal("2.4"), en))
        assertEquals("0.201235", formatPrice(BigDecimal("0.2012345"), en))
    }

    @Test
    fun `money is shown with two decimals and an explicit sign when asked`() {
        assertEquals("1,000.00", formatMoney(BigDecimal("1000"), en))
        assertEquals("+1.90", formatSignedMoney(BigDecimal("1.896052"), en))
        assertEquals("-1,10", formatSignedMoney(BigDecimal("-1.097951"), tr))
        assertEquals("0.00", formatSignedMoney(BigDecimal("-0.001"), en))
    }

    @Test
    fun `quantity keeps up to eight decimals`() {
        assertEquals("0.000999", formatQuantity(BigDecimal("0.00099900"), en))
    }

    @Test
    fun `percent follows locale placement`() {
        assertEquals("+1.23%", formatPercent(1.234, en, signed = true, minFractionDigits = 2))
        assertEquals("+%1,23", formatPercent(1.234, tr, signed = true, minFractionDigits = 2))
        assertEquals("-%0,50", formatPercent(-0.5, tr, signed = true, minFractionDigits = 2))
        assertEquals("%1,5", formatPercent(1.5, tr))
        assertEquals("0.00%", formatPercent(-0.001, en, signed = true, minFractionDigits = 2))
    }

    @Test
    fun `editable decimals round trip with comma or dot`() {
        assertEquals("1,5", formatEditableDecimal(BigDecimal("1.50"), tr))
        assertEquals("50", formatEditableDecimal(BigDecimal("50.00"), en))
        assertEquals(0, BigDecimal("1.5").compareTo(parseDecimal(" 1,5 ")))
        assertEquals(0, BigDecimal("1.5").compareTo(parseDecimal("1.5")))
        assertNull(parseDecimal("abc"))
        assertNull(parseDecimal(""))
    }
}
