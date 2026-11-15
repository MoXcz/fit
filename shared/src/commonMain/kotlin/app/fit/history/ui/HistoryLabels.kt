// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.runtime.Composable
import app.fit.core.MeasureUnit
import app.fit.day.PastSet
import app.fit.day.ui.loadText
import app.fit.history.Metric
import app.fit.history.Record
import app.fit.resources.Res
import app.fit.resources.chart_farthest
import app.fit.resources.chart_longest
import app.fit.resources.chart_most_reps
import app.fit.resources.chart_top_load
import app.fit.resources.item_reps
import app.fit.resources.record_was
import app.fit.resources.set_load_reps
import app.fit.ui.formatClock
import app.fit.ui.formatNumber
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

// "62.5 kg", "12 reps", "5.2 km", "1:10".
@Composable
internal fun measureText(
    metric: Metric,
    value: Double,
    unit: MeasureUnit?,
): String =
    when (metric) {
        Metric.Load, Metric.Distance -> listOfNotNull(formatNumber(value), unit?.symbol).joinToString(" ")
        Metric.Reps -> stringResource(Res.string.item_reps, formatNumber(value))
        Metric.Time -> formatClock(value.seconds)
    }

// "22.5 kg (was 20)": the old value without its unit, which is the same.
@Composable
internal fun recordText(record: Record): String {
    val was =
        when (record.metric) {
            Metric.Time -> formatClock(record.was.seconds)
            else -> formatNumber(record.was)
        }
    return stringResource(Res.string.record_was, measureText(record.metric, record.value, record.unit), was)
}

internal fun chartLabel(metric: Metric): StringResource =
    when (metric) {
        Metric.Load -> Res.string.chart_top_load
        Metric.Reps -> Res.string.chart_most_reps
        Metric.Distance -> Res.string.chart_farthest
        Metric.Time -> Res.string.chart_longest
    }

// What a logged set holds, from what was recorded: "60 kg × 8", "8 reps", "2.5 km · 30:00", "0:40".
@Composable
internal fun pastSetText(set: PastSet): String {
    val load = loadText(set.weight, set.weightUnit, set.bodyweight)
    val reps = set.reps?.takeIf { it > 0 }
    val lifted =
        when {
            load != null && reps != null -> stringResource(Res.string.set_load_reps, load, reps)
            reps != null -> stringResource(Res.string.item_reps, reps.toString())
            else -> load
        }
    val distance = set.distance?.takeIf { it > 0 }?.let { "${formatNumber(it)} ${set.distanceUnit.symbol}" }
    val time = set.duration?.takeIf { it.isPositive() }?.let(::formatClock)
    return listOfNotNull(lifted, distance, time).joinToString(" · ")
}
