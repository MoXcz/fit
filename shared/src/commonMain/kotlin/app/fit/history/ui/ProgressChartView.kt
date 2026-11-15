// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.fit.history.ProgressChart
import app.fit.resources.Res
import app.fit.resources.chart_description
import app.fit.ui.formatDate
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

const val CHART_TAG = "progress-chart"

// One line through each day's best, placed by date so gaps between sessions show, with the highest
// and lowest marked on their gridlines. Drawn on a Canvas: enough to see whether the number is
// going up, no chart library.
@Composable
fun ProgressChartView(
    chart: ProgressChart,
    today: LocalDate,
    modifier: Modifier = Modifier,
    // What the line is of, when not the metric's best (body weight).
    title: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val points = chart.points
    val first = points.first()
    val last = points.last()
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }
    val label = title ?: stringResource(chartLabel(chart.metric))
    val firstText = measureText(chart.metric, first.value, chart.unit)
    val lastText = measureText(chart.metric, last.value, chart.unit)
    val firstDate = formatDate(first.date, today)
    val lastDate = formatDate(last.date, today)
    val highText = measureText(chart.metric, high, chart.unit)
    val lowText = measureText(chart.metric, low, chart.unit)
    val description = stringResource(Res.string.chart_description, label, firstText, firstDate, lastText, lastDate)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", color = colors.outline)
    Column(modifier.testTag(CHART_TAG).clearAndSetSemantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                lastText,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp)) {
            val highLabel = measurer.measure(highText, labelStyle)
            val lowLabel = measurer.measure(lowText, labelStyle)
            val startLabel = measurer.measure(firstDate, labelStyle)
            val endLabel = measurer.measure(lastDate, labelStyle)
            val dot = 5.dp.toPx()
            val gap = 8.dp.toPx()
            // The plot leaves room for the value labels on the right and the dates below.
            val left = dot
            val right = size.width - maxOf(highLabel.size.width, lowLabel.size.width) - gap - dot
            val top = dot
            val bottom = size.height - startLabel.size.height - gap
            val firstDay = first.date.toEpochDays()
            val days = (last.date.toEpochDays() - firstDay).toFloat()

            fun x(date: LocalDate) = if (days == 0f) (left + right) / 2 else left + (date.toEpochDays() - firstDay) / days * (right - left)

            fun y(value: Double) =
                if (high ==
                    low
                ) {
                    (top + bottom) / 2
                } else {
                    (bottom - (value - low) / (high - low) * (bottom - top)).toFloat()
                }

            listOf(high to highLabel, low to lowLabel).distinctBy { it.first }.forEach { (value, text) ->
                drawLine(colors.outlineVariant, Offset(left, y(value)), Offset(right, y(value)), strokeWidth = 1.dp.toPx())
                val labelTop = (y(value) - text.size.height / 2).coerceIn(0f, bottom - text.size.height)
                drawText(text, topLeft = Offset(right + gap + dot, labelTop))
            }
            if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value)) }
                drawPath(path, colors.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            points.forEachIndexed { i, p ->
                drawCircle(colors.primary, radius = if (i == points.lastIndex) dot else 3.dp.toPx(), center = Offset(x(p.date), y(p.value)))
            }
            val dateTop = size.height - startLabel.size.height
            if (points.size > 1) {
                drawText(startLabel, topLeft = Offset(0f, dateTop))
                drawText(
                    endLabel,
                    topLeft = Offset((right + dot - endLabel.size.width).coerceAtLeast(startLabel.size.width + gap), dateTop),
                )
            } else {
                drawText(startLabel, topLeft = Offset((left + right - startLabel.size.width) / 2, dateTop))
            }
        }
    }
}
