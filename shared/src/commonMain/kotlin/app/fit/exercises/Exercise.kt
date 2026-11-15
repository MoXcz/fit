// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import app.fit.core.BodyArea
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@JvmInline
value class ExerciseId(
    val value: Long,
)

data class Exercise(
    val id: ExerciseId,
    // The content file's id for built-in and imported exercises; null for ones made in the app.
    val contentId: String?,
    val fields: ExerciseFields,
    val archived: Boolean = false,
    // Changed in the app, so content updates leave it alone.
    val edited: Boolean = false,
)

// Everything a content file or the editor can set.
data class ExerciseFields(
    val name: String,
    val category: String = "",
    val tracks: Tracks = Tracks.WeightAndReps,
    val sides: Sides = Sides.Both,
    val areas: List<BodyArea> = emptyList(),
    val defaults: ExerciseDefaults = ExerciseDefaults(),
    val weightUnit: WeightUnit = WeightUnit.Kg,
    val distanceUnit: DistanceUnit = DistanceUnit.Km,
    val notes: String = "",
    val videoUrl: String = "",
    val images: List<String> = emptyList(),
    val refs: List<ExerciseRef> = emptyList(),
    // What it's done with; plates per side only show for a barbell. Null: not said.
    val equipment: Equipment? = null,
)

// The content format's `equipment` values, by their slug.
enum class Equipment(
    val slug: String,
) {
    Barbell("barbell"),
    Dumbbell("dumbbell"),
    Kettlebell("kettlebell"),
    Cable("cable"),
    Machine("machine"),
    Band("band"),
    Bodyweight("bodyweight"),
    Other("other"),
    ;

    companion object {
        fun fromSlug(slug: String): Equipment? = entries.firstOrNull { it.slug == slug }
    }
}

data class Tracks(
    val weight: Boolean = false,
    val reps: Boolean = false,
    val time: Boolean = false,
    val distance: Boolean = false,
) {
    val any: Boolean get() = weight || reps || time || distance

    companion object {
        val WeightAndReps = Tracks(weight = true, reps = true)
    }
}

// Each: a timed step runs once per side.
enum class Sides { Both, Each }

data class ExerciseDefaults(
    val sets: Int = 3,
    val reps: Int = 10,
    val repsMax: Int? = null,
    val rest: Duration = 90.seconds,
    val restMax: Duration? = null,
    val duration: Duration = 45.seconds,
    val weight: Double = 0.0,
)

data class ExerciseRef(
    val kind: RefKind,
    val title: String,
    val url: String,
)

enum class RefKind { Link, Video, Blog, Paper }

// The old app's presets; any other combination is Custom.
enum class ExerciseType(
    val tracks: Tracks?,
) {
    WeightAndReps(Tracks(weight = true, reps = true)),
    Reps(Tracks(reps = true)),
    Time(Tracks(time = true)),
    RepsAndTime(Tracks(reps = true, time = true)),
    WeightAndTime(Tracks(weight = true, time = true)),
    WeightRepsAndTime(Tracks(weight = true, reps = true, time = true)),
    DistanceAndTime(Tracks(distance = true, time = true)),
    Distance(Tracks(distance = true)),
    WeightAndDistance(Tracks(weight = true, distance = true)),
    RepsAndDistance(Tracks(reps = true, distance = true)),
    Custom(null),
    ;

    companion object {
        fun of(tracks: Tracks): ExerciseType = entries.firstOrNull { it.tracks == tracks } ?: Custom
    }
}

internal fun exerciseForPreview(
    id: Long,
    name: String,
    tracks: Tracks = Tracks.WeightAndReps,
) = Exercise(ExerciseId(id), contentId = null, fields = ExerciseFields(name = name, tracks = tracks))
