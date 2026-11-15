// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProgramContentTest {
    private fun parse(text: String) = ContentParser.parse(ContentFile("programs/upper-lower-4.md", text.trimIndent()))

    @Test
    fun `a program file is read with its weekdays, deload and notes`() {
        val draft =
            assertIs<ProgramDraft>(
                assertIs<ContentResult.Parsed>(
                    parse(
                        """
                        ---
                        format: 1
                        kind: program
                        id: upper-lower-4
                        name: 4 day upper/lower
                        days: { mon: upper-a, tue: lower-a, thu: upper-b, fri: lower-b }
                        deload-every: 6
                        deload-percent: 15
                        ---
                        Four weeks on, then a lighter one.
                        """,
                    ),
                ).item,
            )
        assertEquals("upper-lower-4", draft.id)
        assertEquals("4 day upper/lower", draft.name)
        assertEquals("Four weeks on, then a lighter one.", draft.notes)
        assertEquals(
            mapOf(
                DayOfWeek.MONDAY to "upper-a",
                DayOfWeek.TUESDAY to "lower-a",
                DayOfWeek.THURSDAY to "upper-b",
                DayOfWeek.FRIDAY to "lower-b",
            ),
            draft.days,
        )
        assertEquals(6, draft.deloadEvery)
        assertEquals(15, draft.deloadPercent)
    }

    @Test
    fun `a program without deload-every never deloads`() {
        val draft =
            assertIs<ProgramDraft>(
                assertIs<ContentResult.Parsed>(
                    parse("---\nformat: 1\nkind: program\nid: home\nname: Home\ndays:\n  sat: full-body\n---\n"),
                ).item,
            )
        assertEquals(null, draft.deloadEvery)
        assertEquals(mapOf(DayOfWeek.SATURDAY to "full-body"), draft.days)
    }

    @Test
    fun `a program file reports a wrong weekday, a missing schedule and a deload outside 2 to 16, each at its line`() {
        val invalid =
            assertIs<ContentResult.Invalid>(
                parse("---\nformat: 1\nkind: program\nid: p\nname: P\ndays:\n  monday: upper\n  tue: Upper A\ndeload-every: 1\n---\n"),
            )
        assertEquals(
            listOf(
                ContentProblem.UnknownKey("programs/upper-lower-4.md", 7, "days.monday"),
                ContentProblem.BadValue(
                    "programs/upper-lower-4.md",
                    7,
                    "days",
                    "a map from mon … sun to a routine id, with at least one day",
                ),
                ContentProblem.BadValue("programs/upper-lower-4.md", 8, "days.tue", "a routine id"),
                ContentProblem.BadValue("programs/upper-lower-4.md", 9, "deload-every", "a whole number from 2 to 16"),
            ),
            invalid.problems,
        )
        assertEquals(
            listOf(ContentProblem.MissingKey("programs/upper-lower-4.md", 2, "days")),
            assertIs<ContentResult.Invalid>(parse("---\nformat: 1\nkind: program\nid: p\nname: P\n---\n")).problems,
        )
    }

    @Test
    fun `the content check catches a wrong routine id at its line, and accepts known ones`() {
        val program = ContentFile("programs/p.md", "---\nformat: 1\nkind: program\nid: p\nname: P\ndays:\n  mon: legs\n  wed: lgs\n---\n")
        val legs =
            ContentFile(
                "routines/legs.md",
                "---\nformat: 1\nkind: routine\nid: legs\nname: Legs\nitems: [{ exercise: squat }]\nsources: [{ title: Me }]\n---\n",
            )
        val squat = ContentFile("exercises/squat.md", "---\nformat: 1\nkind: exercise\nid: squat\nname: Squat\n---\n")
        assertEquals(
            listOf(ContentProblem.UnknownRoutine("programs/p.md", 8, "lgs")),
            checkContent(listOf(program, legs, squat)) {
                false
            }.problems,
        )
        assertEquals(
            emptyList(),
            checkContent(listOf(program), knownRoutines = setOf("legs", "lgs")) { false }.problems,
        )
    }

    @Test
    fun `a program file belongs in programs`() {
        val file = ContentFile("routines/p.md", "---\nformat: 1\nkind: program\nid: p\nname: P\ndays: { mon: legs }\n---\n")
        assertEquals(
            listOf(ContentProblem.WrongPlace("routines/p.md", "programs/p.md"), ContentProblem.UnknownRoutine("routines/p.md", 6, "legs")),
            checkContent(listOf(file)) { false }.problems,
        )
    }

    @Test
    fun `deload-percent is optional, and outside 5 to 50 is reported at its line`() {
        val draft =
            assertIs<ProgramDraft>(
                assertIs<ContentResult.Parsed>(parse("---\nformat: 1\nkind: program\nid: p\nname: P\ndays: { mon: legs }\n---\n")).item,
            )
        assertEquals(null, draft.deloadPercent)
        assertEquals(
            listOf(ContentProblem.BadValue("programs/upper-lower-4.md", 7, "deload-percent", "a whole number from 5 to 50")),
            assertIs<ContentResult.Invalid>(
                parse("---\nformat: 1\nkind: program\nid: p\nname: P\ndays: { mon: legs }\ndeload-percent: 60\n---\n"),
            ).problems,
        )
    }
}
