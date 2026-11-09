package com.spotbot.android.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.core.model.Candle

data class ChartLine(val values: List<Double>, val color: Color, val label: String)

data class PriceMarker(val price: Double, val color: Color, val label: String)

/** Belirli bir zamandaki dikey çizgi (örn. işlem giriş/çıkış anı). */
data class TimeMarker(val time: Long, val color: Color, val label: String)

/** Mum grafiği: göstergeler çizgi, işlem seviyeleri kesikli yatay çizgi olarak çizilir. */
@Composable
fun CandleChart(
    candles: List<Candle>,
    modifier: Modifier = Modifier,
    lines: List<ChartLine> = emptyList(),
    markers: List<PriceMarker> = emptyList(),
    timeMarkers: List<TimeMarker> = emptyList(),
    /** Bu zamandan sonraki bölge gölgelenir (ör. "giriş sonrası: karar anında bilinmiyordu"). */
    shadeFrom: Long? = null,
    shadeLabel: String = "",
) {
    val gain = SpotBotTheme.tradingColors.gain
    val loss = SpotBotTheme.tradingColors.loss
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)

    Canvas(modifier.fillMaxWidth().height(260.dp)) {
        if (candles.isEmpty()) return@Canvas
        val axisWidth = 64.dp.toPx()
        val plotWidth = size.width - axisWidth
        val visibleMarkers = markers.filter { it.price > 0 }
        val values = candles.flatMap { listOf(it.high, it.low) } + lines.flatMap { it.values.filter { v -> !v.isNaN() } } + visibleMarkers.map { it.price }
        val max = values.max()
        val min = values.min()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(price: Double) = ((max - price) / range * size.height).toFloat()
        val step = plotWidth / candles.size
        val bodyWidth = (step * 0.7f).coerceAtLeast(1f)

        for (k in 0..4) {
            val price = min + range * k / 4
            val yy = y(price)
            drawLine(gridColor, Offset(0f, yy), Offset(plotWidth, yy), strokeWidth = 1f)
            drawText(measurer, formatPrice(price), Offset(plotWidth + 4.dp.toPx(), (yy - 6.dp.toPx()).coerceIn(0f, size.height - 14.dp.toPx())), labelStyle)
        }
        shadeFrom?.let { from ->
            val index = candles.indexOfFirst { it.openTime >= from }
            if (index >= 0) {
                val left = index * step
                drawRect(gridColor.copy(alpha = 0.35f), Offset(left, 0f), Size(plotWidth - left, size.height))
                if (shadeLabel.isNotEmpty()) {
                    // Etiket gölgeli bölgenin altına, eksen yazılarının soluna sığacak şekilde arka planla çizilir.
                    val layout = measurer.measure(shadeLabel, labelStyle)
                    val x = (left + 3.dp.toPx()).coerceAtMost(plotWidth - layout.size.width - 3.dp.toPx()).coerceAtLeast(0f)
                    val y = size.height - 30.dp.toPx()
                    drawRect(surfaceColor.copy(alpha = 0.85f), Offset(x - 2.dp.toPx(), y - 1.dp.toPx()), Size(layout.size.width + 4.dp.toPx(), layout.size.height + 2.dp.toPx()))
                    drawText(layout, topLeft = Offset(x, y))
                }
            }
        }
        candles.forEachIndexed { i, c ->
            val x = i * step + step / 2
            val color = if (c.close >= c.open) gain else loss
            drawLine(color, Offset(x, y(c.high)), Offset(x, y(c.low)), strokeWidth = 1.5f)
            val top = y(maxOf(c.open, c.close))
            val bottom = y(minOf(c.open, c.close))
            drawRect(color, Offset(x - bodyWidth / 2, top), Size(bodyWidth, (bottom - top).coerceAtLeast(1.5f)))
        }
        lines.forEach { line ->
            val path = Path()
            var started = false
            line.values.takeLast(candles.size).forEachIndexed { i, v ->
                if (v.isNaN()) return@forEachIndexed
                val point = Offset(i * step + step / 2, y(v))
                if (!started) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                started = true
            }
            drawPath(path, line.color, style = Stroke(width = 2f))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        timeMarkers.forEach { m ->
            val index = candles.indexOfLast { it.openTime <= m.time }
            if (index < 0) return@forEach
            val x = index * step + step / 2
            drawLine(m.color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            drawText(measurer, m.label, Offset((x + 3.dp.toPx()).coerceAtMost(plotWidth - 40.dp.toPx()), size.height - 14.dp.toPx()), labelStyle.copy(color = m.color))
        }
        // Yakın fiyatlardaki etiketler üst üste binmesin: her etiket çakışmayan ilk yatay konuma yerleştirilir.
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        visibleMarkers.forEach { m ->
            val yy = y(m.price)
            drawLine(m.color, Offset(0f, yy), Offset(plotWidth, yy), strokeWidth = 2f, pathEffect = dash)
            val layout = measurer.measure(m.label, labelStyle.copy(color = m.color))
            val top = (yy - 14.dp.toPx()).coerceAtLeast(0f)
            val candidates = listOf(4.dp.toPx(), plotWidth * 0.36f, plotWidth * 0.68f)
            val x = candidates.firstOrNull { cx ->
                val rect = androidx.compose.ui.geometry.Rect(cx, top, cx + layout.size.width, top + layout.size.height)
                placed.none { it.overlaps(rect) }
            } ?: candidates.first()
            val rect = androidx.compose.ui.geometry.Rect(x, top, x + layout.size.width, top + layout.size.height)
            placed += rect
            drawRect(surfaceColor.copy(alpha = 0.7f), rect.topLeft, rect.size)
            drawText(layout, topLeft = Offset(x, top))
        }
    }
}

/** Basit özsermaye eğrisi. */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (values.size < 2) return@Canvas
        val max = values.max()
        val min = values.min()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, ((max - v) / range * size.height).toFloat())
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        val firstY = ((max - values.first()) / range * size.height).toFloat()
        drawLine(baseline, Offset(0f, firstY), Offset(size.width, firstY), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
