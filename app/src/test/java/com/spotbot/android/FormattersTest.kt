package com.spotbot.android

import com.spotbot.android.ui.common.formatCompact
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatRatio
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.parseDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormattersTest {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val en = Locale.US

    @Test
    fun `prices use magnitude based precision and Turkish separators`() {
        assertEquals("75.765,64", formatPrice(75765.6412, tr))
        assertEquals("5,1719", formatPrice(5.17188, tr))
        assertEquals("0,201235", formatPrice(0.2012345, tr))
        assertEquals("—", formatPrice(Double.NaN, tr))
    }

    @Test
    fun `money and signed money`() {
        assertEquals("1.012,54", formatMoney(1012.5432, tr))
        assertEquals("+9,89", formatSignedMoney(9.889, tr))
        assertEquals("-5,05", formatSignedMoney(-5.049, tr))
        assertEquals("0.00", formatSignedMoney(-0.001, en))
    }

    @Test
    fun `percent places the sign and symbol per locale`() {
        assertEquals("+%1,13", formatPercent(1.1349, tr, signed = true, minFractionDigits = 2))
        assertEquals("-2.70%", formatPercent(-2.7, en, signed = true, minFractionDigits = 2))
        assertEquals("—", formatPercent(Double.POSITIVE_INFINITY, tr))
    }

    @Test
    fun `compact volumes and ratios`() {
        assertEquals("1,25 M", formatCompact(1_250_000.0, tr))
        assertEquals("300 B", formatCompact(300_000.0, tr))
        assertEquals("∞", formatRatio(Double.POSITIVE_INFINITY, tr))
    }

    @Test
    fun `decimal input accepts comma or dot`() {
        assertEquals(0.5, parseDecimal(" 0,5 ")!!, 0.0)
        assertEquals(1.5, parseDecimal("1.5")!!, 0.0)
        assertNull(parseDecimal("yarım"))
    }
}
