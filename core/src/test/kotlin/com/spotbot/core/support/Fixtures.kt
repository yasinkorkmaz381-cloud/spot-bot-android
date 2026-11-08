package com.spotbot.core.support

import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe

object Fixtures {
    /** MEXC'den alınmış 500 adet BTCUSDT 1 saatlik mum (2026-10-19 → 2026-11-08). */
    val btc1h: List<Candle> by lazy { csv("/fixtures/BTCUSDT_1H.csv") }

    /** 8 Eylül – 9 Ekim 2026 arası gerçek MEXC verisi (backtest regresyon testi için). */
    fun period(symbol: String, timeframe: String): List<Candle> = csv("/fixtures/${symbol}_$timeframe.csv.gz")

    fun csv(path: String): List<Candle> {
        val stream = Fixtures::class.java.getResourceAsStream(path)!!
        val input = if (path.endsWith(".gz")) java.util.zip.GZIPInputStream(stream) else stream
        return input.bufferedReader().readLines().drop(1).map { line ->
            val p = line.split(',')
            Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toLong(), p[7].toDouble())
        }
    }

    fun text(path: String): String = Fixtures::class.java.getResourceAsStream(path)!!.bufferedReader().readText()

    /** Kapanış fiyatlarından sentetik mum dizisi; her mum ±[wick] fitilli. */
    fun candles(closes: List<Double>, timeframe: Timeframe = Timeframe.H1, start: Long = 0, wick: Double = 0.2, volume: Double = 100.0): List<Candle> =
        closes.mapIndexed { i, close ->
            val open = if (i == 0) close else closes[i - 1]
            Candle(
                openTime = start + i * timeframe.millis,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = volume,
                closeTime = start + (i + 1) * timeframe.millis,
                quoteVolume = volume * close,
            )
        }
}
