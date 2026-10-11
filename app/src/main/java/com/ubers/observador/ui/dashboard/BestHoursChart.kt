package com.ubers.observador.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ubers.observador.domain.BestHours
import com.ubers.observador.domain.ChartScale
import com.ubers.observador.domain.SampleConfidence
import com.ubers.observador.domain.TimeSlotStats
import kotlin.math.roundToInt

/** Theme colours resolved before drawing, so light and dark mode both come from MaterialTheme. */
private data class ChartColors(
    val bar: Color,
    val fewData: Color,
    val noData: Color,
    val selected: Color,
    val baseline: Color,
    val minLine: Color,
    val labelBackground: Color
)

/**
 * Twelve bars, one per 2-hour slot, as tall as the slot's median RD$/h. Hollow grey bars have
 * fewer than [SampleConfidence.LOW_FROM] offers (shape, not only colour, tells them apart); a
 * short mark means nothing was seen; ★ marks the best slot. All numbers come in ready
 * ([BestHours.chartScale]): the Canvas only draws.
 *
 * The Canvas is invisible to TalkBack, so a row of transparent columns sits on top of it: one
 * per slot, full height, each a large touch target with its own spoken description.
 */
@Composable
fun BestHoursChart(
    slots: List<TimeSlotStats>,
    bestStartHour: Int?,
    selectedStartHour: Int?,
    minRdpPerHour: Double,
    use24Hour: Boolean,
    onSelect: (TimeSlotStats) -> Unit,
    modifier: Modifier = Modifier
) {
    val scale = remember(slots, minRdpPerHour) { BestHours.chartScale(slots, minRdpPerHour) }
    val fewData = remember(slots) { slots.map { it.offerCount > 0 && !it.isValid } }
    val descriptions = remember(slots, bestStartHour) {
        slots.map { BestHours.describe(it, isBest = it.startHour == bestStartHour) }
    }
    val axisLabels = remember(slots, use24Hour) { slots.map { BestHours.axisLabel(it.startHour, use24Hour) } }
    val bestIndex = slots.indexOfFirst { it.startHour == bestStartHour }
    val selectedIndex = slots.indexOfFirst { it.startHour == selectedStartHour }

    val scheme = MaterialTheme.colorScheme
    val colors = ChartColors(
        bar = scheme.primary,
        // outline, not outlineVariant: the latter nearly vanishes on a dark card
        fewData = scheme.outline,
        noData = scheme.outline,
        selected = scheme.primary.copy(alpha = SELECTED_ALPHA),
        baseline = scheme.outline,
        minLine = scheme.tertiary,
        labelBackground = CardDefaults.cardColors().containerColor
    )
    val textMeasurer = rememberTextMeasurer()
    val starStyle = MaterialTheme.typography.titleMedium.copy(color = scheme.primary)
    val minLabelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.tertiary)
    val star = remember(textMeasurer, starStyle) { textMeasurer.measure(STAR, starStyle) }
    val minLabel = remember(textMeasurer, minLabelStyle, minRdpPerHour) {
        textMeasurer.measure("tu mín. ${minRdpPerHour.roundToInt()}", minLabelStyle)
    }
    val largeFont = LocalDensity.current.fontScale > LARGE_FONT_SCALE

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawBars(scale, fewData, bestIndex, selectedIndex, colors, star)
                drawMinLine(scale.minLineFraction, star, minLabel, colors)
            }
            Row(modifier = Modifier.matchParentSize()) {
                slots.forEachIndexed { i, slot ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = i == selectedIndex,
                                role = Role.Button,
                                onClick = { onSelect(slot) }
                            )
                            .semantics { contentDescription = descriptions[i] }
                    )
                }
            }
        }
        // Hours under the bars. TalkBack already hears them in each bar, so they are hidden from it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {}
        ) {
            axisLabels.forEachIndexed { i, label ->
                Text(
                    // With a very large font every other hour is shown; a label wider than its
                    // column spills into the blank one next to it instead of being cut
                    text = if (largeFont && i % 2 == 1) "" else label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
        Text(
            text = "$STAR mejor franja · barra hueca: pocos datos (menos de ${SampleConfidence.LOW_FROM} ofertas)" +
                if (scale.minLineFraction != null) " · línea: tu mínimo RD$/h" else "",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

private fun DrawScope.drawBars(
    scale: ChartScale,
    fewData: List<Boolean>,
    bestIndex: Int,
    selectedIndex: Int,
    colors: ChartColors,
    star: TextLayoutResult
) {
    val count = scale.barFractions.size
    if (count == 0) return
    val cell = size.width / count
    val base = size.height
    // The star's height is kept free above the plot, so it fits over the tallest bar
    val plot = (size.height - star.size.height).coerceAtLeast(0f)
    val barWidth = minOf(cell * BAR_WIDTH_SHARE, BAR_MAX_WIDTH.toPx())
    val corner = CornerRadius(BAR_CORNER.toPx())

    if (selectedIndex in 0 until count) {
        drawRoundRect(
            color = colors.selected,
            topLeft = Offset(cell * selectedIndex, 0f),
            size = Size(cell, size.height),
            cornerRadius = corner
        )
    }
    drawLine(colors.baseline, Offset(0f, base), Offset(size.width, base), strokeWidth = HAIRLINE.toPx())

    scale.barFractions.forEachIndexed { i, fraction ->
        val left = cell * i + (cell - barWidth) / 2
        if (fraction == null) {
            // Nothing seen: a short neutral mark, so the gap does not read as "zero pay"
            val y = base - NO_DATA_MARK_LIFT.toPx()
            drawLine(colors.noData, Offset(left, y), Offset(left + barWidth, y), strokeWidth = HAIRLINE.toPx() * 2)
            return@forEachIndexed
        }
        val height = (plot * fraction).toFloat().coerceAtLeast(MIN_BAR_HEIGHT.toPx())
        val bar = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = base - height,
                    right = left + barWidth,
                    bottom = base,
                    topLeftCornerRadius = corner,
                    topRightCornerRadius = corner
                )
            )
        }
        if (fewData[i]) {
            drawPath(bar, colors.fewData, style = Stroke(width = FEW_DATA_STROKE.toPx()))
        } else {
            drawPath(bar, colors.bar, style = Fill)
        }
        if (i == bestIndex) {
            drawText(
                star,
                topLeft = Offset(cell * i + (cell - star.size.width) / 2, base - height - star.size.height)
            )
        }
    }
}

/** Dashed line at the driver's RD$/h minimum, labelled at the right edge over a plain backing. */
private fun DrawScope.drawMinLine(
    fraction: Double?,
    star: TextLayoutResult,
    label: TextLayoutResult,
    colors: ChartColors
) {
    if (fraction == null) return
    val plot = (size.height - star.size.height).coerceAtLeast(0f)
    val y = size.height - (plot * fraction).toFloat()
    val dash = DASH.toPx()
    drawLine(
        color = colors.minLine,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = HAIRLINE.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    )
    val labelTop = (y - label.size.height).coerceAtLeast(0f)
    val labelLeft = size.width - label.size.width
    drawRect(
        color = colors.labelBackground,
        topLeft = Offset(labelLeft, labelTop),
        size = Size(label.size.width.toFloat(), label.size.height.toFloat())
    )
    drawText(label, topLeft = Offset(labelLeft, labelTop))
}

private const val STAR = "★"
private const val SELECTED_ALPHA = 0.12f
private const val BAR_WIDTH_SHARE = 0.6f
private val CHART_HEIGHT = 160.dp
private val BAR_MAX_WIDTH = 24.dp
private val BAR_CORNER = 4.dp
private val MIN_BAR_HEIGHT = 2.dp
private val NO_DATA_MARK_LIFT = 2.dp
private val HAIRLINE = 1.dp
private val FEW_DATA_STROKE = 1.5.dp
private val DASH = 4.dp
