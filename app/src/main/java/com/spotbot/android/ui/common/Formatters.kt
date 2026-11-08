package com.spotbot.android.ui.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** Fiyatı büyüklüğüne göre uygun ondalıkla biçimlendirir (BTC 2, 1–1000 arası 4, altı 6 ondalık). */
fun formatPrice(value: Double, locale: Locale = Locale.getDefault()): String {
    if (value.isNaN()) return "—"
    val a = abs(value)
    val digits = when {
        a >= 1_000 -> 2
        a >= 1 -> 4
        else -> 6
    }
    return number(locale, digits, digits).format(BigDecimal.valueOf(value))
}

fun formatMoney(value: Double, locale: Locale = Locale.getDefault()): String =
    if (value.isNaN()) "—" else number(locale, 2, 2).format(BigDecimal.valueOf(value))

fun formatSignedMoney(value: Double, locale: Locale = Locale.getDefault()): String {
    if (value.isNaN()) return "—"
    val sign = sign(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).signum())
    return sign + formatMoney(abs(value), locale)
}

fun formatQuantity(value: Double, locale: Locale = Locale.getDefault()): String =
    number(locale, 0, 8).format(BigDecimal.valueOf(value))

/** [value] yüzde cinsindendir (1.5 → tr: "%1,5"). */
fun formatPercent(value: Double, locale: Locale = Locale.getDefault(), signed: Boolean = false, minFractionDigits: Int = 0): String {
    if (value.isNaN() || value.isInfinite()) return "—"
    val formatter = NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = minFractionDigits
        maximumFractionDigits = 2
        roundingMode = RoundingMode.HALF_UP
    }
    val rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).signum()
    val body = formatter.format(abs(value) / 100)
    return when {
        signed -> sign(rounded) + body
        rounded < 0 -> "-$body"
        else -> body
    }
}

/** Büyük tutarları kısaltır: 1.250.000 → "1,25 M". */
fun formatCompact(value: Double, locale: Locale = Locale.getDefault()): String = when {
    abs(value) >= 1e9 -> number(locale, 0, 2).format(value / 1e9) + " Mr"
    abs(value) >= 1e6 -> number(locale, 0, 2).format(value / 1e6) + " M"
    abs(value) >= 1e3 -> number(locale, 0, 1).format(value / 1e3) + " B"
    else -> number(locale, 0, 2).format(value)
}

fun formatRatio(value: Double, locale: Locale = Locale.getDefault()): String = when {
    value.isNaN() -> "—"
    value.isInfinite() -> "∞"
    else -> number(locale, 2, 2).format(value)
}

fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

fun formatDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(epochMillis))

/** Kullanıcının girdiği ondalık metni ayrıştırır; virgül ve nokta kabul edilir. */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

fun formatEditable(value: Double, locale: Locale = Locale.getDefault()): String {
    val separator = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', separator)
}

private fun sign(signum: Int) = when {
    signum > 0 -> "+"
    signum < 0 -> "-"
    else -> ""
}

private fun number(locale: Locale, min: Int, max: Int): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = min
    maximumFractionDigits = max
    roundingMode = RoundingMode.HALF_UP
}
