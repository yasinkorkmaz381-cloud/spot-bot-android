package com.spotbot.core.exchange

import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.OrderBook
import com.spotbot.core.model.SymbolInfo
import com.spotbot.core.model.Ticker24h
import com.spotbot.core.model.Timeframe

/** Borsadan herkese açık piyasa verisi. Gerçek uygulaması [com.spotbot.core.exchange.mexc.MexcRestClient]. */
interface MarketDataApi {
    suspend fun serverTime(): Long

    suspend fun exchangeInfo(): List<SymbolInfo>

    /**
     * En fazla [limit] (MEXC'de pratikte ≤500) mum; eskiden yeniye sıralı.
     * Not: MEXC, [endTime] verilmezse [startTime]'ı yok sayıp en son mumları döndürür.
     */
    suspend fun klines(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 500,
        startTime: Long? = null,
        endTime: Long? = null,
    ): List<Candle>

    suspend fun tickers24h(): List<Ticker24h>

    suspend fun bookTickers(): List<BookTicker>

    suspend fun bookTicker(symbol: String): BookTicker

    suspend fun orderBook(symbol: String, limit: Int = 100): OrderBook
}

class ExchangeException(message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
