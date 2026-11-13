package com.spotbot.cli

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal val utc: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
internal fun day(t: Long): String = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()
internal fun date(s: String): Long = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** CryptoScan Pro — Kırılım–Retest (KR) komut satırı. Yalnız herkese açık veri; gerçek emir yok. */
fun main(args: Array<String>) = runBlocking {
    val command = args.firstOrNull() ?: "help"
    val options = parseOptions(args.drop(1))
    when (command) {
        "kr" -> krResearch(options)
        "kr-aux" -> krAux(options)
        "universe" -> universeLive(options)
        else -> println(
            """
            CryptoScan Pro — Kırılım–Retest (yalnız paper trading)
              universe [--out dosya]                                  Canlı CMC ilk 300 → MEXC eşleşmesi (CMC anahtarı gerekir)
              kr --phase research|final --data klasör --out klasör    Ön kayıtlı backtest (docs/KR-ONKAYIT.md)
              kr-aux --data klasör --out klasör                       Yardımcı test (Binance 15M, 2023-11 → 2025-10)
            Veri klasörü: mexc/m15, mexc/h4, mexc/exchangeInfo.json, mexc/bookTicker.json, cmc/hist, binance/m15
            """.trimIndent(),
        )
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val v = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
            out[a.removePrefix("--")] = v ?: "true"
            i += if (v != null) 2 else 1
        } else i++
    }
    return out
}
