// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentCheckTest {
    private fun exerciseFile(
        path: String,
        id: String,
        name: String,
        extra: String = "",
    ) = ContentFile(path, "---\nformat: 1\nkind: exercise\nid: $id\nname: $name\n$extra---\n")

    @Test
    fun `valid files give their items and no problems`() {
        val check =
            checkContent(
                listOf(exerciseFile("exercises/plank.md", "plank", "Plank"), exerciseFile("exercises/dip.md", "dip", "Dip")),
                imageExists = { false },
            )
        assertEquals(listOf("dip", "plank"), check.items.map { it.id })
        assertEquals(emptyList(), check.problems)
    }

    @Test
    fun `a file must be named after its id and sit in its kind's folder`() {
        val check =
            checkContent(
                listOf(exerciseFile("exercises/planks.md", "plank", "Plank"), exerciseFile("routines/dip.md", "dip", "Dip")),
                imageExists = { false },
            )
        assertEquals(
            listOf(
                ContentProblem.WrongPlace("exercises/planks.md", "exercises/plank.md"),
                ContentProblem.WrongPlace("routines/dip.md", "exercises/dip.md"),
            ),
            check.problems,
        )
    }

    @Test
    fun `ids are unique and exercise names are unique regardless of case`() {
        val check =
            checkContent(
                listOf(
                    exerciseFile("exercises/plank.md", "plank", "Plank"),
                    exerciseFile("exercises/plank-2.md", "plank-2", "PLANK"),
                    exerciseFile("other/plank.md", "plank", "Side plank"),
                ),
                imageExists = { false },
            )
        assertEquals(
            listOf(
                ContentProblem.DuplicateName("exercises/plank.md", "Plank"),
                ContentProblem.WrongPlace("other/plank.md", "exercises/plank.md"),
                ContentProblem.DuplicateId("other/plank.md", "plank"),
            ),
            check.problems,
        )
    }

    @Test
    fun `images must exist`() {
        val check =
            checkContent(
                listOf(exerciseFile("exercises/plank.md", "plank", "Plank", "images: [images/plank.webp, images/side.webp]\n")),
                imageExists = { it == "images/plank.webp" },
            )
        assertEquals(listOf(ContentProblem.MissingImage("exercises/plank.md", "images/side.webp")), check.problems)
    }

    @Test
    fun `parse problems are passed through`() {
        val check = checkContent(listOf(ContentFile("exercises/x.md", "no header")), imageExists = { false })
        assertEquals(listOf(ContentProblem.NoHeader("exercises/x.md")), check.problems)
    }

    @Test
    fun `the check takes an exercise's equipment and names the file and line of an unknown one`() {
        val check =
            checkContent(
                listOf(
                    exerciseFile("exercises/bench.md", "bench", "Bench", "equipment: barbell\n"),
                    exerciseFile("exercises/smith.md", "smith", "Smith", "equipment: smith\n"),
                ),
                imageExists = { false },
            )
        assertEquals(listOf("bench"), check.items.map { it.id })
        assertEquals(listOf(ContentProblem.BadValue("exercises/smith.md", 6, "equipment", EQUIPMENT_VALUES)), check.problems)
    }
}
