// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

// The old app's categories, in its order; the library lists them first, then any others A–Z, then
// exercises without one.
val EXERCISE_CATEGORIES =
    listOf(
        "Chest",
        "Back",
        "Shoulders",
        "Biceps",
        "Triceps",
        "Forearms",
        "Abs",
        "Quads",
        "Hamstrings",
        "Glutes",
        "Calves",
        "Cardio",
        "Mobility",
        "Full body",
        "Other",
    )

data class ExerciseGroup(
    // Blank for exercises without a category.
    val category: String,
    val exercises: List<Exercise>,
)

// The library: archived or active exercises whose name or category contains [query], by category.
fun groupExercises(
    all: List<Exercise>,
    query: String,
    archived: Boolean,
): List<ExerciseGroup> {
    val needle = query.trim()
    return all
        .filter { it.archived == archived }
        .filter {
            needle.isEmpty() || it.fields.name.contains(needle, ignoreCase = true) ||
                it.fields.category.contains(needle, ignoreCase = true)
        }.groupBy { it.fields.category.trim() }
        .map { (category, exercises) -> ExerciseGroup(category, exercises.sortedBy { it.fields.name.lowercase() }) }
        .sortedWith(compareBy({ categoryRank(it.category) }, { it.category.lowercase() }))
}

private fun categoryRank(category: String): Int =
    when {
        category.isEmpty() -> Int.MAX_VALUE
        else -> EXERCISE_CATEGORIES.indexOfFirst { it.equals(category, ignoreCase = true) }.takeIf { it >= 0 } ?: EXERCISE_CATEGORIES.size
    }
