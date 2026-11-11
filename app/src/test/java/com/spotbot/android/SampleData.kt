package com.spotbot.android

import com.spotbot.core.model.Candle
import com.spotbot.core.rs1.BTC
import com.spotbot.core.rs1.DAY_MS
import com.spotbot.core.rs1.DailySeries
import com.spotbot.core.rs1.PaperState
import com.spotbot.core.rs1.Rs1Config
import com.spotbot.core.rs1.Rs1Engine
import com.spotbot.core.rs1.Rs1Result
import kotlin.math.exp

/** Ekran testleri için RS-1 motorunun sentetik veride ürettiği gerçek sonuç (temsili sayılar). */
object SampleData {
    const val T0 = 1_704_067_200_000L // 2024-01-01 pazartesi
    private const val DAYS = 400

    private fun series(volume: Double = 20e6, drift: (Int) -> Double): List<Candle> {
        var p = 100.0
        return (0 until DAYS).map { i ->
            val o = p
            val c = p * exp(drift(i))
            p = c
            val t = T0 + i * DAY_MS
            Candle(t, o, maxOf(o, c) * 1.01, minOf(o, c) * 0.99, c, volume / c, t + DAY_MS, volume)
        }
    }

    val cfg = Rs1Config()
    val data = mapOf(
        BTC to series { 0.002 },
        "SOLUSDT" to series { if (it % 35 == 20) -0.14 else 0.012 },
        "PEPEUSDT" to series { if (it in 330..345) -0.01 else if (it % 30 == 15) -0.12 else 0.008 },
        "XMRUSDT" to series { if (it % 40 == 25) -0.12 else 0.009 },
    )
    val start = T0 + 200 * DAY_MS
    val end = T0 + DAYS * DAY_MS

    fun result(closeAtEnd: Boolean = false): Rs1Result =
        Rs1Engine(data.mapValues { (s, c) -> DailySeries(s, c, cfg) }, cfg).run(start, end, closeAtEnd = closeAtEnd)

    fun paper(): PaperState = PaperState(startedAt = start, capital = 1_000.0, result = result(), updatedAt = end, dataEnd = end)
}
