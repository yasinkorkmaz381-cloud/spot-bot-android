package com.spotbot.core.analysis

import kotlinx.serialization.Serializable
import kotlin.math.abs

enum class SwingType { HIGH, LOW }

/** Pivot noktası. [confirmedAt] = indeks + sağdaki mum sayısı; o mum kapanmadan pivot bilinemez. */
@Serializable
data class SwingPoint(val index: Int, val price: Double, val type: SwingType, val confirmedAt: Int)

enum class SwingLabel { HH, HL, LH, LL }

enum class Trend { UP, DOWN, NEUTRAL }

@Serializable
data class BreakOfStructure(val bullish: Boolean, val index: Int, val level: Double)

@Serializable
data class StructureState(
    val trend: Trend,
    val lastHigh: SwingPoint?,
    val lastLow: SwingPoint?,
    val lastHighLabel: SwingLabel?,
    val lastLowLabel: SwingLabel?,
    val lastBos: BreakOfStructure?,
)

enum class LevelKind { SUPPORT, RESISTANCE }

/** Birden çok swing noktasının kümelenmesiyle oluşan destek/direnç seviyesi. */
@Serializable
data class Level(val price: Double, val touches: Int, val lastIndex: Int, val kind: LevelKind)

/** Fair Value Gap: üç mumluk boşluk. [index] boşluğu oluşturan üçüncü mumun indeksidir. */
@Serializable
data class FairValueGap(val index: Int, val bottom: Double, val top: Double, val bullish: Boolean)

/** Order block: yapı kırılımına yol açan hareketten önceki son ters yönlü mum bölgesi. */
@Serializable
data class OrderBlock(val index: Int, val bottom: Double, val top: Double, val bullish: Boolean)

/** Eşit tepeler (alış tarafı likiditesi) ya da eşit dipler (satış tarafı likiditesi). */
@Serializable
data class LiquidityPool(val price: Double, val count: Int, val buySide: Boolean)

object Structure {

    fun findSwings(high: DoubleArray, low: DoubleArray, strength: Int): List<SwingPoint> {
        val swings = ArrayList<SwingPoint>()
        for (i in strength until high.size - strength) {
            var isHigh = true
            var isLow = true
            for (k in 1..strength) {
                if (high[i - k] >= high[i] || high[i + k] > high[i]) isHigh = false
                if (low[i - k] <= low[i] || low[i + k] < low[i]) isLow = false
                if (!isHigh && !isLow) break
            }
            if (isHigh) swings += SwingPoint(i, high[i], SwingType.HIGH, i + strength)
            if (isLow) swings += SwingPoint(i, low[i], SwingType.LOW, i + strength)
        }
        return swings
    }

    /** [endIndex] itibarıyla bilinen (onaylanmış) swinglerden yapı durumunu çıkarır. */
    fun structure(swings: List<SwingPoint>, close: DoubleArray, endIndex: Int): StructureState {
        val highs = swings.filter { it.type == SwingType.HIGH }
        val lows = swings.filter { it.type == SwingType.LOW }
        val lastHigh = highs.lastOrNull()
        val prevHigh = highs.getOrNull(highs.size - 2)
        val lastLow = lows.lastOrNull()
        val prevLow = lows.getOrNull(lows.size - 2)
        val highLabel = if (lastHigh != null && prevHigh != null) {
            if (lastHigh.price > prevHigh.price) SwingLabel.HH else SwingLabel.LH
        } else null
        val lowLabel = if (lastLow != null && prevLow != null) {
            if (lastLow.price > prevLow.price) SwingLabel.HL else SwingLabel.LL
        } else null

        val bos = lastBreak(swings, close, endIndex)
        val trend = when {
            highLabel == SwingLabel.HH && lowLabel == SwingLabel.HL -> Trend.UP
            highLabel == SwingLabel.LH && lowLabel == SwingLabel.LL -> Trend.DOWN
            bos != null && lastLow != null && lastHigh != null && bos.index > maxOf(lastLow.confirmedAt, lastHigh.confirmedAt) ->
                if (bos.bullish) Trend.UP else Trend.DOWN
            else -> Trend.NEUTRAL
        }
        return StructureState(trend, lastHigh, lastLow, highLabel, lowLabel, bos)
    }

    /** Onaylanmış son swing tepesinin üzerinde (ya da dibinin altında) gerçekleşen en son kapanış. */
    fun lastBreak(swings: List<SwingPoint>, close: DoubleArray, endIndex: Int): BreakOfStructure? {
        var result: BreakOfStructure? = null
        var activeHigh: SwingPoint? = null
        var activeLow: SwingPoint? = null
        val byConfirmation = swings.sortedBy { it.confirmedAt }
        var next = 0
        val start = byConfirmation.firstOrNull()?.confirmedAt ?: return null
        for (i in start..endIndex) {
            while (next < byConfirmation.size && byConfirmation[next].confirmedAt <= i) {
                val s = byConfirmation[next++]
                if (s.type == SwingType.HIGH) activeHigh = s else activeLow = s
            }
            val h = activeHigh
            if (h != null && close[i] > h.price && h.confirmedAt < i) {
                result = BreakOfStructure(true, i, h.price)
                activeHigh = null
            }
            val l = activeLow
            if (l != null && close[i] < l.price && l.confirmedAt < i) {
                result = BreakOfStructure(false, i, l.price)
                activeLow = null
            }
        }
        return result
    }

    fun levels(swings: List<SwingPoint>, tolerance: Double, currentPrice: Double): List<Level> {
        if (swings.isEmpty() || tolerance <= 0 || tolerance.isNaN()) return emptyList()
        val sorted = swings.sortedBy { it.price }
        val clusters = mutableListOf(mutableListOf(sorted.first()))
        for (s in sorted.drop(1)) {
            val cluster = clusters.last()
            if (s.price - cluster.first().price <= tolerance * 2 && abs(s.price - cluster.map { it.price }.average()) <= tolerance) {
                cluster += s
            } else {
                clusters += mutableListOf(s)
            }
        }
        return clusters.map { c ->
            val price = c.map { it.price }.average()
            Level(price, c.size, c.maxOf { it.index }, if (price >= currentPrice) LevelKind.RESISTANCE else LevelKind.SUPPORT)
        }
    }

    /** [from, endIndex] aralığında oluşmuş ve [endIndex] itibarıyla tamamen doldurulmamış FVG'ler. */
    fun fairValueGaps(high: DoubleArray, low: DoubleArray, from: Int, endIndex: Int): List<FairValueGap> {
        val gaps = ArrayList<FairValueGap>()
        for (i in maxOf(2, from)..endIndex) {
            if (low[i] > high[i - 2]) {
                val gap = FairValueGap(i, high[i - 2], low[i], bullish = true)
                if ((i + 1..endIndex).none { low[it] <= gap.bottom }) gaps += gap
            } else if (high[i] < low[i - 2]) {
                val gap = FairValueGap(i, high[i], low[i - 2], bullish = false)
                if ((i + 1..endIndex).none { high[it] >= gap.top }) gaps += gap
            }
        }
        return gaps
    }

    /**
     * Yükseliş order block'ları: onaylı bir swing tepesinin kapanışla kırıldığı her hareket için,
     * kırılımdan önceki son düşüş mumu. Sonradan dibinin altında kapanış görmüşse geçersizdir.
     */
    fun bullishOrderBlocks(
        swings: List<SwingPoint>,
        open: DoubleArray,
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        from: Int,
        endIndex: Int,
    ): List<OrderBlock> {
        val blocks = ArrayList<OrderBlock>()
        val highs = swings.filter { it.type == SwingType.HIGH }
        for (swing in highs) {
            val breakIndex = (maxOf(swing.confirmedAt + 1, from)..endIndex).firstOrNull { close[it] > swing.price } ?: continue
            val obIndex = (breakIndex downTo maxOf(swing.index, from)).firstOrNull { close[it] < open[it] } ?: continue
            val block = OrderBlock(obIndex, low[obIndex], high[obIndex], bullish = true)
            if ((breakIndex + 1..endIndex).none { close[it] < block.bottom }) blocks += block
        }
        return blocks.distinctBy { it.index }
    }

    fun liquidityPools(swings: List<SwingPoint>, tolerance: Double, high: DoubleArray, low: DoubleArray, endIndex: Int): List<LiquidityPool> {
        if (tolerance <= 0 || tolerance.isNaN()) return emptyList()
        fun pools(points: List<SwingPoint>, buySide: Boolean): List<LiquidityPool> {
            val result = ArrayList<LiquidityPool>()
            val sorted = points.sortedBy { it.price }
            var i = 0
            while (i < sorted.size) {
                var j = i
                while (j + 1 < sorted.size && sorted[j + 1].price - sorted[i].price <= tolerance) j++
                if (j > i) {
                    val group = sorted.subList(i, j + 1)
                    val price = if (buySide) group.maxOf { it.price } else group.minOf { it.price }
                    val lastConfirmed = group.maxOf { it.confirmedAt }
                    val swept = (lastConfirmed + 1..endIndex).any { if (buySide) high[it] > price else low[it] < price }
                    if (!swept) result += LiquidityPool(price, group.size, buySide)
                }
                i = j + 1
            }
            return result
        }
        return pools(swings.filter { it.type == SwingType.HIGH }, buySide = true) +
            pools(swings.filter { it.type == SwingType.LOW }, buySide = false)
    }
}
