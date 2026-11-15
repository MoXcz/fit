// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.exercises.FakeExerciseRepository
import app.fit.guided.FakeGuidedRepository
import app.fit.lessons.FakeLessonRepository
import app.fit.programs.FakeProgramRepository
import app.fit.routines.FakeRoutineRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuiltInContentSyncTest {
    private class FakeBuiltInContent(
        var list: List<ContentFile>,
    ) : BuiltInContent {
        override suspend fun files() = list

        override suspend fun imagePaths() = emptySet<String>()

        override suspend fun image(path: String): ByteArray? = null
    }

    private class FakeContentStateStore : ContentStateStore {
        var fingerprint: String? = null

        override suspend fun loadedFingerprint() = fingerprint

        override suspend fun setLoadedFingerprint(fingerprint: String?) {
            this.fingerprint = fingerprint
        }
    }

    private fun file(
        id: String,
        name: String,
    ) = ContentFile("exercises/$id.md", "---\nformat: 1\nkind: exercise\nid: $id\nname: $name\n---\n")

    private val content =
        FakeBuiltInContent(
            listOf(
                file("plank", "Plank"),
                ContentFile("exercises/bad.md", "no header"),
                ContentFile(
                    "routines/legs.md",
                    "---\nformat: 1\nkind: routine\nid: legs\nname: Legs\nitems: [{ exercise: plank }]\nsources: [{ title: Me }]\n---\n",
                ),
                ContentFile("programs/split.md", "---\nformat: 1\nkind: program\nid: split\nname: Split\ndays: { mon: legs }\n---\n"),
                ContentFile(
                    "lessons/plank.md",
                    "---\nformat: 1\nkind: lesson\nid: plank\ntitle: Plank\nexercises: [plank]\nsources: [{ title: Me }]\n---\n## Setup\n",
                ),
            ),
        )
    private val store = FakeContentStateStore()
    private val exercises = FakeExerciseRepository()
    private val routines = FakeRoutineRepository(exercises)
    private val programs = FakeProgramRepository()
    private val guided = FakeGuidedRepository()
    private val lessons = FakeLessonRepository()
    private val sync = BuiltInContentSync(content, store, exercises, routines, programs, guided, lessons)

    @Test
    fun `the first start loads every valid file and skips broken ones`() =
        runTest {
            assertTrue(sync.syncIfChanged())
            assertEquals(listOf(listOf("plank")), exercises.synced.map { drafts -> drafts.map { it.id } })
            assertEquals(listOf(listOf("legs")), routines.synced.map { drafts -> drafts.map { it.id } })
            assertEquals(listOf(listOf("split")), programs.synced.map { drafts -> drafts.map { it.id } })
            assertEquals(listOf(listOf("plank")), lessons.synced.map { drafts -> drafts.map { it.id } })
        }

    @Test
    fun `nothing happens again until the files change`() =
        runTest {
            sync.syncIfChanged()
            assertFalse(sync.syncIfChanged())
            content.list = content.list + file("dip", "Dip")
            assertTrue(sync.syncIfChanged())
            assertEquals(2, exercises.synced.size)
        }

    @Test
    fun `the fingerprint depends on paths and texts, not on their order`() {
        val a = file("a", "A")
        val b = file("b", "B")
        assertEquals(fingerprintOf(listOf(a, b)), fingerprintOf(listOf(b, a)))
        assertNotEquals(fingerprintOf(listOf(a)), fingerprintOf(listOf(a.copy(text = a.text + " "))))
        assertNotEquals(fingerprintOf(listOf(ContentFile("ab", "c"))), fingerprintOf(listOf(ContentFile("a", "bc"))))
    }
}
