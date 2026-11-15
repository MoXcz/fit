// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import androidx.compose.runtime.Composable
import app.fit.exercises.Sides
import app.fit.exercises.ui.label
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedStep
import app.fit.resources.Res
import app.fit.resources.item_reps
import app.fit.resources.recover_each_side
import app.fit.resources.recover_minutes
import app.fit.resources.recover_rounds
import app.fit.ui.formatClock
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.DurationUnit

// "6 min · Wrists, Forearms".
@Composable
internal fun planMeta(plan: GuidedPlan): String {
    val areas = plan.areas.map { stringResource(it.label) }.joinToString(", ")
    return listOf(stringResource(Res.string.recover_minutes, plan.minutes), areas).filter { it.isNotEmpty() }.joinToString(" · ")
}

// "0:30 · each side · 2 rounds", "10 reps".
@Composable
internal fun stepAmount(step: GuidedStep): String =
    listOfNotNull(
        step.duration?.let(::formatClock) ?: step.reps?.let { stringResource(Res.string.item_reps, "$it") },
        stringResource(Res.string.recover_each_side).takeIf { step.sides == Sides.Each },
        stringResource(Res.string.recover_rounds, step.rounds).takeIf { step.rounds > 1 },
    ).joinToString(" · ")

// "Wrist reset · 8 min": how long it actually ran, at least a minute.
@Composable
internal fun logLine(log: GuidedLog): String {
    val minutes =
        log.duration
            .toDouble(DurationUnit.MINUTES)
            .roundToInt()
            .coerceAtLeast(1)
    return "${log.title} · ${stringResource(Res.string.recover_minutes, minutes)}"
}
