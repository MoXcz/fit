// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fit.history.CalendarCell
import app.fit.history.DotSize
import app.fit.history.MonthGrid
import app.fit.resources.Res
import app.fit.resources.calendar_finished
import app.fit.resources.calendar_next
import app.fit.resources.calendar_previous
import app.fit.resources.calendar_recovery
import app.fit.resources.calendar_trained
import app.fit.resources.ic_keyboard_arrow_left
import app.fit.resources.ic_keyboard_arrow_right
import app.fit.ui.formatDate
import app.fit.ui.formatMonth
import app.fit.ui.weekdayLetter
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// Test tag of a day's cell: "calendar:2026-11-09".
fun calendarTag(date: LocalDate): String = "calendar:$date"

// A Monday-first month: today outlined, the selected day filled, future days muted, and a dot for a
// day with done working sets (larger with more sets; the accent once the day is finished).
@Composable
fun MonthCalendar(
    month: LocalDate,
    weeks: List<List<CalendarCell?>>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onPrevious) {
                Icon(
                    painterResource(Res.drawable.ic_keyboard_arrow_left),
                    contentDescription = stringResource(Res.string.calendar_previous),
                )
            }
            Text(
                formatMonth(month),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) {
                Icon(painterResource(Res.drawable.ic_keyboard_arrow_right), contentDescription = stringResource(Res.string.calendar_next))
            }
        }
        val first = MonthGrid.monthOf(month)
        val monday = first.minus(first.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
            (0 until 7).forEach {
                Text(
                    weekdayLetter(monday.plus(it, DateTimeUnit.DAY)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(Modifier.weight(1f).height(52.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (cell != null) DayCell(cell = cell, onSelect = onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    cell: CalendarCell,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val mark = cell.mark
    val workout =
        when {
            mark == null -> null
            mark.finished -> stringResource(Res.string.calendar_finished)
            else -> stringResource(Res.string.calendar_trained)
        }
    val recovery = stringResource(Res.string.calendar_recovery).takeIf { cell.recovery }
    val state = listOfNotNull(workout, recovery).joinToString(", ").ifEmpty { null }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(shape)
                .background(if (cell.selected) colors.primaryContainer else Color.Transparent)
                .then(if (cell.today) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
                .clickable(role = Role.Button, onClickLabel = formatDate(cell.date)) { onSelect(cell.date) }
                .semantics { state?.let { stateDescription = it } }
                .testTag(calendarTag(cell.date)),
    ) {
        Text(
            "${cell.date.day}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = if (cell.today) FontWeight.Bold else FontWeight.Medium,
            color =
                when {
                    cell.selected -> colors.onPrimaryContainer
                    cell.future -> colors.outline
                    else -> colors.onSurface
                },
        )
        Spacer(Modifier.height(2.dp))
        DayMarks(
            finished = mark?.finished,
            recovery = cell.recovery,
            diameter =
                when (mark?.size) {
                    null, DotSize.Small -> 6.dp
                    DotSize.Medium -> 8.dp
                    DotSize.Large -> 10.dp
                },
            selected = cell.selected,
        )
    }
}

// A day's marks side by side: the workout's dot, and a dash when a recovery session ran.
@Composable
fun DayMarks(
    finished: Boolean?,
    recovery: Boolean,
    diameter: Dp,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (finished != null || !recovery) DayDot(finished = finished, diameter = diameter, selected = selected)
        if (recovery) RecoveryDash(selected = selected)
    }
}

// A recovery session's mark: a short clay dash, unlike the workout's round mark in shape and colour,
// so the two never read as one.
@Composable
fun RecoveryDash(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val color = if (selected) colors.onPrimaryContainer else colors.tertiary
    Canvas(modifier.size(width = 10.dp, height = 10.dp)) {
        val height = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, (size.height - height) / 2),
            size = Size(size.width, height),
            cornerRadius = CornerRadius(height / 2),
        )
    }
}

// A day's mark: a filled dot once the workout is finished, a ring while it isn't, so the two differ
// by shape as well as colour. Nothing for a day without a workout ([finished] null).
@Composable
fun DayDot(
    finished: Boolean?,
    diameter: Dp,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // On the selected day's fill the accent and grey are too faint, so the mark takes its text colour.
    val color =
        when {
            selected -> colors.onPrimaryContainer
            finished == true -> colors.primary
            else -> colors.outline
        }
    Canvas(modifier.size(10.dp)) {
        if (finished == null) return@Canvas
        val radius = diameter.toPx() / 2
        if (finished) {
            drawCircle(color, radius)
        } else {
            val stroke = 1.5.dp.toPx()
            drawCircle(color, radius - stroke / 2, style = Stroke(stroke))
        }
    }
}
