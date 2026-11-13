package com.spotbot.core.exchange.mexc

import com.spotbot.core.model.BookTicker
import com.spotbot.core.model.Candle
import com.spotbot.core.model.Timeframe

/**
 * MEXC WebSocket protobuf mesajlarının bağımlılıksız çözücüsü.
 * Alan numaraları resmi tanımlardan alınmıştır: github.com/mexcdevelop/websocket-proto
 * (PushDataV3ApiWrapper, PublicAggreBookTickerV3Api, PublicBookTickerV3Api, PublicSpotKlineV3Api).
 */
object MexcProto {

    sealed interface Body {
        data class BookTickerBody(val bid: Double, val bidQty: Double, val ask: Double, val askQty: Double) : Body
        data class KlineBody(val interval: String, val candle: Candle) : Body
        data class Other(val fieldNumber: Int) : Body
    }

    data class PushMessage(val channel: String, val symbol: String?, val sendTime: Long?, val body: Body?)

    fun decode(bytes: ByteArray): PushMessage {
        val reader = ProtoReader(bytes)
        var channel = ""
        var symbol: String? = null
        var sendTime: Long? = null
        var createTime: Long? = null
        var body: Body? = null
        while (reader.hasMore()) {
            val tag = reader.readTag()
            val field = tag ushr 3
            val wireType = tag and 7
            when (field) {
                1 -> channel = reader.readString()
                3 -> symbol = reader.readString()
                4 -> reader.skip(wireType)
                5 -> createTime = reader.readVarint()
                6 -> sendTime = reader.readVarint()
                FIELD_BOOK_TICKER, FIELD_AGGRE_BOOK_TICKER -> body = decodeBookTicker(reader.readMessage())
                FIELD_SPOT_KLINE -> body = decodeKline(reader.readMessage())
                else -> {
                    if (field in 301..399) body = Body.Other(field)
                    reader.skip(wireType)
                }
            }
        }
        return PushMessage(channel, symbol, sendTime ?: createTime, body)
    }

    private fun decodeBookTicker(r: ProtoReader): Body.BookTickerBody {
        var bid = 0.0
        var bidQty = 0.0
        var ask = 0.0
        var askQty = 0.0
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                1 -> bid = r.readString().toDoubleOrZero()
                2 -> bidQty = r.readString().toDoubleOrZero()
                3 -> ask = r.readString().toDoubleOrZero()
                4 -> askQty = r.readString().toDoubleOrZero()
                else -> r.skip(tag and 7)
            }
        }
        return Body.BookTickerBody(bid, bidQty, ask, askQty)
    }

    private fun decodeKline(r: ProtoReader): Body.KlineBody {
        var interval = ""
        var windowStart = 0L
        var windowEnd = 0L
        var open = 0.0
        var close = 0.0
        var high = 0.0
        var low = 0.0
        var volume = 0.0
        var amount = 0.0
        while (r.hasMore()) {
            val tag = r.readTag()
            when (tag ushr 3) {
                1 -> interval = r.readString()
                2 -> windowStart = r.readVarint()
                3 -> open = r.readString().toDoubleOrZero()
                4 -> close = r.readString().toDoubleOrZero()
                5 -> high = r.readString().toDoubleOrZero()
                6 -> low = r.readString().toDoubleOrZero()
                7 -> volume = r.readString().toDoubleOrZero()
                8 -> amount = r.readString().toDoubleOrZero()
                9 -> windowEnd = r.readVarint()
                else -> r.skip(tag and 7)
            }
        }
        val candle = Candle(windowStart * 1000, open, high, low, close, volume, windowEnd * 1000, amount)
        return Body.KlineBody(interval, candle)
    }

    fun timeframeOf(streamInterval: String): Timeframe? = Timeframe.entries.firstOrNull { it.mexcStream == streamInterval }

    private const val FIELD_BOOK_TICKER = 305
    private const val FIELD_SPOT_KLINE = 308
    private const val FIELD_AGGRE_BOOK_TICKER = 315

    private fun String.toDoubleOrZero() = toDoubleOrNull() ?: 0.0
}

/** Protobuf ikili formatı için asgari okuyucu (varint, 64/32 bit, uzunluk ön ekli alanlar). */
internal class ProtoReader(private val buffer: ByteArray, private var pos: Int = 0, private val end: Int = buffer.size) {

    fun hasMore(): Boolean = pos < end

    fun readTag(): Int = readVarint().toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            check(pos < end) { "protobuf: beklenmedik son" }
            val b = buffer[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            check(shift < 64) { "protobuf: hatalı varint" }
        }
    }

    fun readString(): String {
        val length = readLength()
        val text = String(buffer, pos, length, Charsets.UTF_8)
        pos += length
        return text
    }

    fun readMessage(): ProtoReader {
        val length = readLength()
        val reader = ProtoReader(buffer, pos, pos + length)
        pos += length
        return reader
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readLength())
            5 -> advance(4)
            else -> error("protobuf: desteklenmeyen tel türü $wireType")
        }
    }

    private fun readLength(): Int {
        val length = readVarint().toInt()
        check(length >= 0 && pos + length <= end) { "protobuf: hatalı uzunluk" }
        return length
    }

    private fun advance(count: Int) {
        check(pos + count <= end) { "protobuf: beklenmedik son" }
        pos += count
    }
}

/** MEXC WebSocket kanal adları. */
object MexcChannels {
    fun bookTicker(symbol: String) = "spot@public.aggre.bookTicker.v3.api.pb@100ms@$symbol"
    fun kline(symbol: String, timeframe: com.spotbot.core.model.Timeframe) =
        "spot@public.kline.v3.api.pb@$symbol@${timeframe.mexcStream}"

    fun toBookTicker(message: MexcProto.PushMessage, receivedAt: Long): BookTicker? {
        val body = message.body as? MexcProto.Body.BookTickerBody ?: return null
        val symbol = message.symbol ?: message.channel.substringAfterLast('@')
        return BookTicker(symbol, body.bid, body.bidQty, body.ask, body.askQty, message.sendTime ?: receivedAt)
    }
}
