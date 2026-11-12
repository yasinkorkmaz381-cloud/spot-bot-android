package com.spotbot.core.util

/** Test edilebilirlik için zaman kaynağı. */
fun interface TimeSource {
    fun now(): Long

    companion object {
        val System = TimeSource { java.lang.System.currentTimeMillis() }
    }
}

/**
 * MEXC sunucu saatine göre düzeltilmiş saat: cihaz saati yanlışsa değerlendirme pencereleri ve gün sınırları kaymasın.
 * [offsetMillis] her döngüde sunucu saatiyle güncellenir.
 */
class ServerClock(private val base: TimeSource = TimeSource.System) : TimeSource {
    @Volatile var offsetMillis: Long = 0
    override fun now(): Long = base.now() + offsetMillis
}
