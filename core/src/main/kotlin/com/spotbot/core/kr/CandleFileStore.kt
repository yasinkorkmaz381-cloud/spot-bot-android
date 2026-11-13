package com.spotbot.core.kr

import com.spotbot.core.model.Candle
import kotlinx.serialization.json.Json
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Mumları diskte sabit boyutlu ikili kayıtlar olarak tutar ve bellek eşlemesiyle (mmap) okur: bir yıllık 15M verisi
 * Java belleğine yüklenmez, telefon belleği yetmezliği olmaz. Kayıt: açılış (dakika, int) + açılış/yüksek/düşük/kapanış
 * (double) + hacim/USDT hacmi (float) = 44 bayt. Fiyatlar double tutulur (karar karşılaştırmaları değişmesin).
 */
class CandleFileStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(symbol: String, period: Long) = File(dir, "${symbol}_${period / 60_000}m.bin")

    fun load(symbol: String, period: Long): List<Candle> {
        val f = file(symbol, period)
        if (!f.exists() || f.length() < RECORD) return emptyList()
        RandomAccessFile(f, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            return MappedCandles(buf, (raf.length() / RECORD).toInt(), period)
        }
    }

    /** Var olan kayıtla birleştirip (açılış zamanına göre tekil, sıralı) atomik yazar. */
    fun save(symbol: String, period: Long, candles: List<Candle>) {
        val merged = (load(symbol, period) + candles).associateBy { it.openTime }.values.sortedBy { it.openTime }
        val f = file(symbol, period)
        val tmp = File(dir, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
            for (c in merged) {
                out.writeInt((c.openTime / 60_000).toInt())
                out.writeDouble(c.open); out.writeDouble(c.high); out.writeDouble(c.low); out.writeDouble(c.close)
                out.writeFloat(c.volume.toFloat()); out.writeFloat(c.quoteVolume.toFloat())
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** İndirilmiş (boş olsa bile denetlenmiş) zaman aralığı. */
    fun coverage(symbol: String, period: Long): Pair<Long, Long>? =
        File(dir, file(symbol, period).name + ".range").takeIf { it.exists() }?.readText()?.split(',')?.let { it[0].toLong() to it[1].toLong() }

    fun setCoverage(symbol: String, period: Long, from: Long, to: Long) =
        File(dir, file(symbol, period).name + ".range").writeText("$from,$to")

    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() { dir.listFiles()?.forEach { it.deleteRecursively() } }

    companion object { const val RECORD = 44 }
}

/** Bellek eşlemeli salt-okunur mum listesi; her erişimde küçük bir [Candle] nesnesi üretir. */
class MappedCandles(private val buf: ByteBuffer, override val size: Int, private val period: Long) : AbstractList<Candle>() {
    override fun get(index: Int): Candle {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index / $size")
        val p = index * CandleFileStore.RECORD
        val t = buf.getInt(p) * 60_000L
        return Candle(t, buf.getDouble(p + 4), buf.getDouble(p + 12), buf.getDouble(p + 20), buf.getDouble(p + 28),
            buf.getFloat(p + 36).toDouble(), t + period, buf.getFloat(p + 40).toDouble())
    }
}

/** Günlük CMC listelerinin önbelleği (geçmiş listeler değişmez; her gün bir kez kredi harcanır). */
class CmcListFileCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }
    init { dir.mkdirs() }
    private fun file(day: Long) = File(dir, "cmc-$day.json")
    fun get(day: Long): CmcList? = file(day).takeIf { it.exists() }?.let { runCatching { json.decodeFromString(CmcList.serializer(), it.readText()) }.getOrNull() }
    fun put(day: Long, list: CmcList) {
        val f = file(day)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(json.encodeToString(CmcList.serializer(), list))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
