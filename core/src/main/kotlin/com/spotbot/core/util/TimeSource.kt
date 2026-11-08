package com.spotbot.core.util

/** Test edilebilirlik için zaman kaynağı. */
fun interface TimeSource {
    fun now(): Long

    companion object {
        val System = TimeSource { java.lang.System.currentTimeMillis() }
    }
}
