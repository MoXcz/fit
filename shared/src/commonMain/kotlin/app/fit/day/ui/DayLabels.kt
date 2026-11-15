// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import androidx.compose.runtime.Composable
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.SetEntry
import app.fit.day.WorkoutDay
import app.fit.exercises.ui.typeLabel
import app.fit.resources.Res
import app.fit.resources.day_no_sets
import app.fit.resources.day_sets
import app.fit.resources.day_today
import app.fit.resources.day_tomorrow
import app.fit.resources.day_yesterday
import app.fit.resources.item_reps
import app.fit.resources.item_sets_target
import app.fit.resources.item_warmup_sets
import app.fit.resources.load_bodyweight
import app.fit.resources.load_bodyweight_plus
import app.fit.resources.set_load_reps
import app.fit.resources.workout_set_of
import app.fit.resources.workout_warmup_of
import app.fit.ui.formatClock
import app.fit.ui.formatDate
import app.fit.ui.formatNumber
import app.fit.ui.formatRange
import app.fit.ui.formatThousands
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// "Today", "Yesterday", "Tomorrow", else the date.
@Composable
internal fun dayTitle(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> stringResource(Res.string.day_today)
        today.plus(-1, DateTimeUnit.DAY) -> stringResource(Res.string.day_yesterday)
        today.plus(1, DateTimeUnit.DAY) -> stringResource(Res.string.day_tomorrow)
        else -> formatDate(date)
    }

// "60 kg", "BW + 10 kg", "BW": what a set lifts; a bodyweight set's weight is what's added.
@Composable
internal fun loadText(
    weight: Double?,
    unit: WeightUnit,
    bodyweight: Boolean,
): String? {
    val added = weight?.takeIf { it > 0 }?.let { "${formatNumber(it)} ${unit.symbol}" }
    return when {
        bodyweight && added != null -> stringResource(Res.string.load_bodyweight_plus, added)
        bodyweight -> stringResource(Res.string.load_bodyweight)
        else -> added
    }
}

// "3 × 8–12 reps · 60 kg · 2 warm-up sets": the working sets' plan and load.
@Composable
internal fun blockSummary(block: Block): String {
    val first = block.workingSets.firstOrNull()
    val tracks = block.tracks
    val target =
        listOfNotNull(
            first?.let { stringResource(Res.string.item_reps, formatRange(it.targetReps, it.targetRepsMax)) }?.takeIf { tracks.reps },
            formatClock(first?.targetDuration ?: block.duration).takeIf { tracks.time },
            first?.distanceUnit?.symbol?.takeIf { tracks.distance },
        ).joinToString(" · ").ifEmpty { typeLabel(tracks) }
    val warmups = block.sets.count { it.warmup }
    return listOfNotNull(
        stringResource(Res.string.item_sets_target, block.workingSets.size, target),
        first?.let { loadText(it.weight.takeIf { _ -> tracks.weight }, it.weightUnit, it.bodyweight) },
        warmups.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.item_warmup_sets, it, it) },
    ).joinToString(" · ")
}

// "Set 2 of 3" or "Warm-up 1 of 2": the set's place among its kind.
@Composable
internal fun setLabel(
    block: Block,
    set: SetEntry,
): String {
    val group = block.sets.filter { it.warmup == set.warmup }
    val k = group.indexOf(set) + 1
    return stringResource(if (set.warmup) Res.string.workout_warmup_of else Res.string.workout_set_of, k, group.size)
}

// What a set holds, short: "62.5 kg × 5", "0:40", "8 reps", "2.5 km · 30:00".
@Composable
internal fun setValues(
    block: Block,
    set: SetEntry,
): String {
    val tracks = block.tracks
    val load = loadText(set.weight, set.weightUnit, set.bodyweight)
    val reps = (set.reps ?: set.targetReps).takeIf { tracks.reps }
    val time = (set.duration ?: set.targetDuration ?: block.duration).takeIf { tracks.time }?.let(::formatClock)
    val distance = set.distance?.takeIf { tracks.distance }?.let { "${formatNumber(it)} ${set.distanceUnit.symbol}" }
    val lifted =
        when {
            load != null && reps != null -> stringResource(Res.string.set_load_reps, load, reps)
            reps != null -> stringResource(Res.string.item_reps, reps.toString())
            else -> load
        }
    return listOfNotNull(lifted, distance, time).joinToString(" · ")
}

// A plan row's targets: "3 × 5 · 62.5 kg", "2 × 0:40".
@Composable
internal fun planLine(block: Block): String {
    val first = block.workingSets.firstOrNull()
    val tracks = block.tracks
    val target =
        listOfNotNull(
            first?.let { formatRange(it.targetReps, it.targetRepsMax) }?.takeIf { tracks.reps },
            formatClock(first?.targetDuration ?: block.duration).takeIf { tracks.time },
        ).joinToString(" · ").ifEmpty { typeLabel(tracks) }
    return listOfNotNull(
        stringResource(Res.string.item_sets_target, block.workingSets.size, target),
        first?.let { loadText(it.weight.takeIf { _ -> tracks.weight }, it.weightUnit, it.bodyweight) },
    ).joinToString(" · ")
}

// "4,310 kg" (and "+ 200 lb" when a day mixes units).
internal fun volumeText(volume: Map<WeightUnit, Double>): String? = volumeLines(volume).joinToString(" + ").ifEmpty { null }

// One line per unit, never converted: "4,310 kg", "200 lb".
internal fun volumeLines(volume: Map<WeightUnit, Double>): List<String> =
    volume.entries
        .sortedBy { it.key.ordinal }
        .filter { it.value > 0 }
        .map { (unit, total) -> "${formatThousands(total)} ${unit.symbol}" }

// A finished day's result: "52:10 · 23 sets · 4,310 kg", or that nothing was logged.
@Composable
internal fun dayResult(
    day: WorkoutDay,
    clock: String?,
): String {
    if (day.doneSets == 0) return stringResource(Res.string.day_no_sets)
    val sets = pluralStringResource(Res.plurals.day_sets, day.doneSets, day.doneSets)
    return listOfNotNull(clock, sets, volumeText(day.volume)).joinToString(" · ")
}
