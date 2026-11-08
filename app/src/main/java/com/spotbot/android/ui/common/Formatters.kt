package com.spotbot.android.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val HUNDRED = BigDecimal(100)
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Fiyatı büyüklüğüne göre uygun ondalık hassasiyetle biçimlendirir. */
fun formatPrice(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
    val abs = value.abs()
    val digits = when {
        abs >= HUNDRED -> 2
        abs >= BigDecimal.ONE -> 4
        else -> 6
    }
    return numberFormat(locale, digits, digits).format(value)
}

/** Kote tutarı (örn. USDT) iki ondalıkla biçimlendirir. */
fun formatMoney(value: BigDecimal, locale: Locale = Locale.getDefault()): String =
    numberFormat(locale, 2, 2).format(value)

/** Tutarı işaretiyle (+/-) biçimlendirir. */
fun formatSignedMoney(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
    val sign = signPrefix(value.setScale(2, RoundingMode.HALF_UP).signum())
    return sign + formatMoney(value.abs(), locale)
}

/** Kripto miktarını en fazla 8 ondalıkla biçimlendirir. */
fun formatQuantity(value: BigDecimal, locale: Locale = Locale.getDefault()): String =
    numberFormat(locale, 0, 8).format(value)

/**
 * Yüzde değeri yerel kurallara göre biçimlendirir (tr: "%1,5", en: "1.5%").
 * [value] yüzde cinsindendir (1.5 → %1,5).
 */
fun formatPercent(
    value: Double,
    locale: Locale = Locale.getDefault(),
    signed: Boolean = false,
    minFractionDigits: Int = 0,
): String {
    val formatter = NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = minFractionDigits
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }
    val roundedSign = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).signum()
    val body = formatter.format(abs(value) / 100)
    return when {
        signed -> signPrefix(roundedSign) + body
        roundedSign < 0 -> "-$body"
        else -> body
    }
}

fun formatTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMATTER.withZone(zone).format(instant)

/** Form alanlarında gösterilecek sade ondalık metin (örn. tr için "1,5"). */
fun formatEditableDecimal(value: BigDecimal, locale: Locale = Locale.getDefault()): String {
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return value.stripTrailingZeros().toPlainString().replace('.', separator)
}

/** Kullanıcının girdiği ondalık metni ayrıştırır; virgül ve nokta ayırıcıyı kabul eder. */
fun parseDecimal(text: String): BigDecimal? = text.trim().replace(',', '.').toBigDecimalOrNull()

private fun signPrefix(signum: Int): String = when {
    signum > 0 -> "+"
    signum < 0 -> "-"
    else -> ""
}

private fun numberFormat(locale: Locale, minDigits: Int, maxDigits: Int): NumberFormat =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = minDigits
        maximumFractionDigits = maxDigits
        roundingMode = RoundingMode.HALF_UP
    }
