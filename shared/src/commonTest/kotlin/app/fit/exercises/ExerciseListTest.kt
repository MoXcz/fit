// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import kotlin.test.Test
import kotlin.test.assertEquals

class ExerciseListTest {
    private val all =
        listOf(
            exercise(1, "Squat", "Quads"),
            exercise(2, "bench press", "Chest"),
            exercise(3, "Arm swing", ""),
            exercise(4, "Turkish get-up", "Kettlebell"),
            exercise(5, "Cable fly", "Chest"),
            exercise(6, "Old curl", "Biceps", archived = true),
            exercise(7, "Animal flow", "Animal"),
        )

    private fun List<ExerciseGroup>.names() = map { group -> group.category to group.exercises.map { it.fields.name } }

    @Test
    fun `groups follow the category order, then other categories A to Z, then none`() {
        assertEquals(
            listOf(
                "Chest" to listOf("bench press", "Cable fly"),
                "Quads" to listOf("Squat"),
                "Animal" to listOf("Animal flow"),
                "Kettlebell" to listOf("Turkish get-up"),
                "" to listOf("Arm swing"),
            ),
            groupExercises(all, query = "", archived = false).names(),
        )
    }

    @Test
    fun `search matches the name or the category, ignoring case`() {
        assertEquals(listOf("Chest" to listOf("bench press", "Cable fly")), groupExercises(all, " CHEST ", archived = false).names())
        assertEquals(listOf("Quads" to listOf("Squat")), groupExercises(all, "squ", archived = false).names())
    }

    @Test
    fun `archived exercises are listed only on their own`() {
        assertEquals(listOf("Biceps" to listOf("Old curl")), groupExercises(all, "", archived = true).names())
        assertEquals(emptyList(), groupExercises(all, "curl", archived = false))
    }
}
