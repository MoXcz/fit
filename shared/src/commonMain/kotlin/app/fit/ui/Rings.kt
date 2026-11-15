// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2025-2026 Nishant Mishra (Tomato, https://github.com/nsh07/Tomato): ProgressRing is the timer's progress ring, via Hush.
package app.fit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

// Tomato's progress ring: a plain thick ring, or a wavy one for rest.
@Composable
internal fun ProgressRing(
    progress: () -> Float,
    wavy: Boolean,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
) {
    if (wavy) {
        val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) }
        CircularWavyProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            stroke = stroke,
            trackStroke = stroke,
            wavelength = 60.dp,
            gapSize = strokeWidth / 2,
        )
    } else {
        CircularProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = color,
            trackColor = trackColor,
            strokeWidth = strokeWidth,
            gapSize = strokeWidth / 2,
        )
    }
}

enum class SegmentState { Done, Current, ToDo }

data class RingSegment(
    val state: SegmentState,
    val warmup: Boolean = false,
)

// More sets than this crowd the ring; it becomes a plain ring of the share done.
private const val MAX_SEGMENTS = 10

// The exercise's sets around a ring: done in the accent, the current one wide, warm-ups thinner.
// Accessibility services read it as progress: sets done of all.
@Composable
internal fun SetRing(
    segments: List<RingSegment>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp,
) {
    val colors = MaterialTheme.colorScheme
    if (segments.isEmpty() || segments.size > MAX_SEGMENTS) {
        val done = segments.count { it.state == SegmentState.Done }
        ProgressRing(
            progress = { if (segments.isEmpty()) 0f else done.toFloat() / segments.size },
            wavy = false,
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
            modifier = modifier,
            strokeWidth = strokeWidth,
        )
        return
    }
    val done = segments.count { it.state == SegmentState.Done }
    Canvas(modifier.progressSemantics(done.toFloat(), 0f..segments.size.toFloat(), steps = segments.size - 1)) {
        val stroke = strokeWidth.toPx()
        val wide = stroke * 1.5f
        val diameter = size.minDimension - wide
        val arc = Size(diameter, diameter)
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val radius = diameter / 2
        val count = segments.size
        // Round caps reach past each end, so the gap leaves room for them.
        val gap = if (count == 1) 0f else ((6.dp.toPx() + wide) / radius * 180f / PI.toFloat())
        val sweep = 360f / count - gap
        segments.forEachIndexed { index, segment ->
            val start = -90f + index * 360f / count + gap / 2
            val width = if (segment.warmup) stroke * 0.6f else stroke
            when (segment.state) {
                SegmentState.Done -> {
                    drawArc(colors.primary, start, sweep, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
                }

                SegmentState.ToDo -> {
                    drawArc(colors.surfaceContainerHighest, start, sweep, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
                }

                SegmentState.Current -> {
                    drawArc(colors.primaryContainer, start, sweep, false, topLeft, arc, style = Stroke(wide, cap = StrokeCap.Round))
                    drawArc(colors.primary, start, sweep, false, topLeft, arc, style = Stroke(stroke / 3, cap = StrokeCap.Round))
                }
            }
        }
    }
}
