// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.runtime.Composable
import app.fit.core.BodyArea
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseType
import app.fit.exercises.RefKind
import app.fit.exercises.Tracks
import app.fit.resources.Res
import app.fit.resources.area_ankles
import app.fit.resources.area_elbows
import app.fit.resources.area_feet
import app.fit.resources.area_forearms
import app.fit.resources.area_full_body
import app.fit.resources.area_hips
import app.fit.resources.area_knees
import app.fit.resources.area_lower_back
import app.fit.resources.area_neck
import app.fit.resources.area_shoulders
import app.fit.resources.area_upper_back
import app.fit.resources.area_wrists
import app.fit.resources.equipment_band
import app.fit.resources.equipment_barbell
import app.fit.resources.equipment_bodyweight
import app.fit.resources.equipment_cable
import app.fit.resources.equipment_dumbbell
import app.fit.resources.equipment_kettlebell
import app.fit.resources.equipment_machine
import app.fit.resources.equipment_other
import app.fit.resources.metric_distance
import app.fit.resources.metric_reps
import app.fit.resources.metric_time
import app.fit.resources.metric_weight
import app.fit.resources.ref_blog
import app.fit.resources.ref_link
import app.fit.resources.ref_paper
import app.fit.resources.ref_video
import app.fit.resources.type_custom
import app.fit.resources.type_distance
import app.fit.resources.type_distance_and_time
import app.fit.resources.type_reps
import app.fit.resources.type_reps_and_distance
import app.fit.resources.type_reps_and_time
import app.fit.resources.type_time
import app.fit.resources.type_weight_and_distance
import app.fit.resources.type_weight_and_reps
import app.fit.resources.type_weight_and_time
import app.fit.resources.type_weight_reps_and_time
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun typeLabel(tracks: Tracks): String =
    stringResource(
        when (ExerciseType.of(tracks)) {
            ExerciseType.WeightAndReps -> Res.string.type_weight_and_reps
            ExerciseType.Reps -> Res.string.type_reps
            ExerciseType.Time -> Res.string.type_time
            ExerciseType.RepsAndTime -> Res.string.type_reps_and_time
            ExerciseType.WeightAndTime -> Res.string.type_weight_and_time
            ExerciseType.WeightRepsAndTime -> Res.string.type_weight_reps_and_time
            ExerciseType.DistanceAndTime -> Res.string.type_distance_and_time
            ExerciseType.Distance -> Res.string.type_distance
            ExerciseType.WeightAndDistance -> Res.string.type_weight_and_distance
            ExerciseType.RepsAndDistance -> Res.string.type_reps_and_distance
            ExerciseType.Custom -> Res.string.type_custom
        },
    )

internal val Equipment.label: StringResource
    get() =
        when (this) {
            Equipment.Barbell -> Res.string.equipment_barbell
            Equipment.Dumbbell -> Res.string.equipment_dumbbell
            Equipment.Kettlebell -> Res.string.equipment_kettlebell
            Equipment.Cable -> Res.string.equipment_cable
            Equipment.Machine -> Res.string.equipment_machine
            Equipment.Band -> Res.string.equipment_band
            Equipment.Bodyweight -> Res.string.equipment_bodyweight
            Equipment.Other -> Res.string.equipment_other
        }

internal val Metric.label: StringResource
    get() =
        when (this) {
            Metric.Weight -> Res.string.metric_weight
            Metric.Reps -> Res.string.metric_reps
            Metric.Time -> Res.string.metric_time
            Metric.Distance -> Res.string.metric_distance
        }

internal fun Tracks.has(metric: Metric): Boolean =
    when (metric) {
        Metric.Weight -> weight
        Metric.Reps -> reps
        Metric.Time -> time
        Metric.Distance -> distance
    }

internal val BodyArea.label: StringResource
    get() =
        when (this) {
            BodyArea.Wrists -> Res.string.area_wrists
            BodyArea.Forearms -> Res.string.area_forearms
            BodyArea.Elbows -> Res.string.area_elbows
            BodyArea.Shoulders -> Res.string.area_shoulders
            BodyArea.Neck -> Res.string.area_neck
            BodyArea.UpperBack -> Res.string.area_upper_back
            BodyArea.LowerBack -> Res.string.area_lower_back
            BodyArea.Hips -> Res.string.area_hips
            BodyArea.Knees -> Res.string.area_knees
            BodyArea.Ankles -> Res.string.area_ankles
            BodyArea.Feet -> Res.string.area_feet
            BodyArea.FullBody -> Res.string.area_full_body
        }

internal val RefKind.label: StringResource
    get() =
        when (this) {
            RefKind.Link -> Res.string.ref_link
            RefKind.Video -> Res.string.ref_video
            RefKind.Blog -> Res.string.ref_blog
            RefKind.Paper -> Res.string.ref_paper
        }
