package com.spotbot.android.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/** Eski (V4 ve öncesi) A/B/C motorunun kapanmış bir işlemi: yalnız okunur, karar motoruna girmez. */
data class ArchivedTrade(
    val portfolio: String,
    val symbol: String,
    val strategy: String,
    val openedAt: Long,
    val closedAt: Long,
    val netPnl: Double,
    val reason: String,
)

data class ArchiveSummary(val trades: List<ArchivedTrade>, val files: List<String>, val note: String?)

/**
 * Arşivlenmiş eski kayıtları okur (archive-v4/, archive-rs1/, archive-kr/). Eski sınıflar kaldırıldığı için dosyalar JSON olarak okunur; eski
 * strateji adları yalnız görüntüleme için kısa koda çevrilir. Bu sınıf işlem açamaz.
 */
class ArchiveRepository(private val dir: File) {
    private val archive get() = File(dir, SettingsRepository.ARCHIVE)

    fun load(): ArchiveSummary {
        val a = archive
        val rs1 = File(dir, SettingsRepository.ARCHIVE_RS1)
        val kr = File(dir, ARCHIVE_KR)
        if (!a.exists() && !rs1.exists() && !kr.exists()) return ArchiveSummary(emptyList(), emptyList(), null)
        val trades = ArrayList<ArchivedTrade>()
        File(a, "portfolio.json").takeIf { it.exists() }?.let { trades += read(it.readText(), "Aktif portföy (eski)") }
        File(a, "shadows.json").takeIf { it.exists() }?.let { f ->
            runCatching { storageJson.parseToJsonElement(f.readText()) as JsonObject }.getOrNull()?.forEach { (name, v) ->
                trades += readPortfolio(v, "Gölge portföy (${code(name)})")
            }
        }
        a.listFiles { f -> f.name.startsWith("paper-") && f.name.endsWith(".json") }?.forEach { f ->
            trades += readPaper(f.readText(), "RS-1 sıfırlanan hesap (${f.name.removePrefix("paper-").removeSuffix(".json")})")
        }
        File(rs1, "paper.json").takeIf { it.exists() }?.let { trades += readPaper(it.readText(), "Momentum RS-1 (eski strateji)") }
        kr.listFiles { f -> f.name.startsWith("kr-paper-") && f.name.endsWith(".json") }?.forEach { f ->
            trades += readKr(f.readText(), "Kırılım–Retest sıfırlanan hesap (${f.name.removePrefix("kr-paper-").removeSuffix(".json")})")
        }
        val files = listOf(a, rs1, kr).filter { it.exists() }.flatMap { d -> d.listFiles().orEmpty().map { "${d.name}/${it.name}" } }.sorted()
        val notes = listOf(a, rs1, kr).mapNotNull { File(it, "BILGI.txt").takeIf { f -> f.exists() }?.readText() }
        return ArchiveSummary(trades.sortedByDescending { it.closedAt }, files, notes.joinToString("\n").ifBlank { null })
    }

    /** Sıfırlanan Kırılım–Retest hesabı (yalnız görüntüleme). */
    private fun readKr(text: String, label: String): List<ArchivedTrade> = runCatching {
        val trades = ((storageJson.parseToJsonElement(text) as JsonObject)["book"] as? JsonObject)?.get("trades") as? JsonArray
        trades.orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            ArchivedTrade(label, o.str("symbol"), "KR", o.long("entryTime"), o.long("exitTime"), o.num("netPnl"), o.str("reason"))
        }
    }.getOrDefault(emptyList())

    private fun read(text: String, label: String): List<ArchivedTrade> =
        runCatching { readPortfolio(storageJson.parseToJsonElement(text), label) }.getOrDefault(emptyList())

    private fun readPortfolio(e: JsonElement, label: String): List<ArchivedTrade> {
        val closed = (e as? JsonObject)?.get("closedTrades") as? JsonArray ?: return emptyList()
        return closed.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val p = o["position"] as? JsonObject ?: return@mapNotNull null
            val qty = p.num("quantity")
            val net = o.num("exitPrice") * qty - o.num("exitFee") - (p.num("entryPrice") * qty + p.num("entryFee")) + p.num("realizedPnl")
            ArchivedTrade(
                label, p.str("symbol"), code(p.str("strategy")), p.long("openedAt"), o.long("closedAt"), net, o.str("reason"),
            )
        }
    }

    /** RS-1 paper hesabı sıfırlandığında arşivlenen kayıt. */
    private fun readPaper(text: String, label: String): List<ArchivedTrade> = runCatching {
        val trades = ((storageJson.parseToJsonElement(text) as JsonObject)["result"] as? JsonObject)?.get("trades") as? JsonArray
        trades.orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            ArchivedTrade(label, o.str("symbol"), "RS-1", o.long("entryTime"), o.long("exitTime"), o.num("netPnl"), o.str("reason"))
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content ?: ""

    companion object {
        const val ARCHIVE_KR = "archive-kr"
        /** Eski strateji kimliği → kısa kod (yalnız görüntüleme). */
        fun code(id: String): String = when {
            id.startsWith("BREAKOUT") -> "A"
            id.startsWith("TREND") -> "B"
            id.startsWith("RANGE") -> "C"
            else -> id
        }
    }
}
