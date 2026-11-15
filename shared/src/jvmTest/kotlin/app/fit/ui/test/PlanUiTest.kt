// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.fit.bodyweight.BodyWeight
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.SetEdit
import app.fit.day.ui.TodayRoute
import app.fit.day.ui.TodayViewModel
import app.fit.history.ui.SummaryRoute
import app.fit.history.ui.SummaryViewModel
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.ui.ExercisePickerViewModel
import app.fit.routines.ui.RoutineEditorRoute
import app.fit.routines.ui.RoutineEditorViewModel
import app.fit.routines.ui.RoutinePickerTarget
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import app.fit.workout.ui.DIAL_TAG
import app.fit.workout.ui.RPE_TAG
import app.fit.workout.ui.WorkoutRoute
import app.fit.workout.ui.WorkoutViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The active program on Today and in the focus view: the empty day's plan, deload weeks, set options
// and the day's body weight.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlanUiTest {
    // Not a test dispatcher: see the other UI tests.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val legs =
        Routine(
            RoutineId(1),
            null,
            "Upper A",
            "",
            listOf(
                RoutineItem(
                    RoutineItemId(10),
                    bench,
                    ItemTarget(sets = 3, reps = 8, rest = 90.seconds, duration = 45.seconds, progressStep = 2.5, warmupSets = 2),
                ),
                RoutineItem(RoutineItemId(11), plank, ItemTarget(sets = 2, reps = 1, rest = 30.seconds, duration = 40.seconds)),
            ),
        )
    private val arms =
        Routine(
            RoutineId(2),
            null,
            "Arms",
            "",
            listOf(RoutineItem(RoutineItemId(20), curl, ItemTarget(sets = 3, reps = 10, rest = 60.seconds, duration = 45.seconds))),
        )
    private val pull =
        Routine(
            RoutineId(3),
            null,
            "Pull",
            "",
            listOf(RoutineItem(RoutineItemId(30), curl, ItemTarget(sets = 2, reps = 12, rest = 60.seconds, duration = 45.seconds))),
        )
    private val tomorrow = TODAY.plus(1, DateTimeUnit.DAY)

    // Monday 9 Nov is week 2 of "Upper/lower" (from 2 Nov); yesterday's bench was 60 kg × 8 all done.
    private suspend fun TestScope.fixture(deloadEvery: Int? = null): WorkoutFixture {
        val f = WorkoutFixture(this)
        f.routines.all.value = listOf(legs, arms, pull)
        f.programs.all.value =
            listOf(
                Program(
                    ProgramId(1),
                    name = "Upper/lower",
                    active = true,
                    startsOn = LocalDate(2026, 11, 2),
                    deloadEvery = deloadEvery,
                    days = mapOf(DayOfWeek.MONDAY to legs.id, DayOfWeek.TUESDAY to arms.id),
                ),
            )
        val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
        val block = f.days.addExercise(yesterday, bench.id)!!
        f.days
            .dayOfBlock(block)
            .first()!!
            .blocks
            .single()
            .sets
            .forEach { f.days.setDone(it.id, true) }
        return f
    }

    private fun ComposeUiTest.today(
        f: WorkoutFixture,
        opened: MutableList<BlockId> = mutableListOf(),
    ): TodayViewModel {
        val vm =
            TodayViewModel(
                f.days,
                f.routines,
                f.programs,
                f.bodyWeights,
                f.settings,
                f.controller(),
                f.guided(),
                f.plans,
                f.clock,
                TimeZone.UTC,
            ).clearedAfterTest()
        setScreen { TodayRoute(onOpenBlock = { opened += it }, showToday = 0, viewModel = vm) }
        waitForIdle()
        return vm
    }

    private fun blocks(
        f: WorkoutFixture,
        date: LocalDate = TODAY,
    ): List<Block> =
        runBlocking {
            f.days
                .day(date)
                .first()
                ?.blocks
                .orEmpty()
        }

    private fun ComposeUiTest.focus(
        f: WorkoutFixture,
        block: BlockId,
    ) {
        val vm = WorkoutViewModel(block.value, f.days, f.controller(), f.settings, f.clock, TimeZone.UTC).clearedAfterTest()
        setScreen { WorkoutRoute(id = block, onBack = {}, viewModel = vm) }
        waitForIdle()
    }

    private fun ComposeUiTest.dial(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag(DIAL_TAG)))

    @Test
    fun `an empty today leads with its planned routine, lists the others and Add exercise, and Start applies and starts it`() =
        runTest {
            val f = fixture()
            runPhoneUiTest {
                val opened = mutableListOf<BlockId>()
                today(f, opened)
                onNodeWithText("Planned · Week 2").assertIsDisplayed()
                onNodeWithText("Upper A").assertIsDisplayed()
                onNodeWithText("2 exercises · ~9 min").assertIsDisplayed()
                onNodeWithText("Start workout").assertIsDisplayed()
                onNodeWithText("Other routines").assertIsDisplayed()
                onNodeWithText("Arms").assertIsDisplayed()
                onNodeWithText("Pull").assertIsDisplayed()
                onNodeWithText("Add exercise").assertIsDisplayed()
                screenshot("today-empty-plan")
                onNodeWithText("Start workout").performClick()
                waitForIdle()
                val applied = blocks(f)
                assertEquals(listOf("Bench press", "Plank"), applied.map { it.exercise.fields.name })
                assertTrue(
                    runBlocking {
                        f.days
                            .day(TODAY)
                            .first()!!
                            .started
                    },
                )
                assertEquals(listOf(applied.first().id), opened)
            }
        }

    @Test
    fun `on a 360 x 780 dp phone the empty today with its plan fits`() =
        runTest {
            val f = fixture(deloadEvery = 2)
            runPhoneUiTest(360, 780) {
                today(f)
                onNodeWithText("Deload week").assertIsDisplayed()
                onNodeWithText("Start workout").assertIsDisplayed()
                screenshot("today-empty-plan-360x780")
            }
        }

    @Test
    fun `another day's plan is Use routine, which applies it without starting the clock`() =
        runTest {
            val f = fixture()
            runPhoneUiTest {
                today(f)
                onNodeWithContentDescription("Next day").performClick()
                waitForIdle()
                onNodeWithText("Tomorrow").assertIsDisplayed()
                onNodeWithText("Arms").assertIsDisplayed()
                onNodeWithText("Use routine").assertIsDisplayed()
                screenshot("today-use-routine")
                onNodeWithText("Use routine").performClick()
                waitForIdle()
                assertEquals(listOf("Curl"), blocks(f, tomorrow).map { it.exercise.fields.name })
                assertFalse(
                    runBlocking {
                        f.days
                            .day(tomorrow)
                            .first()!!
                            .started
                    },
                )
                onNodeWithText("Planned · Week 2").assertIsDisplayed()
                onNodeWithText("Review").assertIsDisplayed()
                screenshot("today-use-routine-applied")
            }
        }

    @Test
    fun `in a deload week the hero says Week N and Deload week, and the plan's loads are cut`() =
        runTest {
            val f = fixture(deloadEvery = 2)
            runPhoneUiTest {
                today(f)
                onNodeWithText("Deload week").assertIsDisplayed()
                screenshot("today-deload-empty")
                // Apply without starting, to see the plan.
                this@runTest.change { f.days.applyRoutine(TODAY, legs.id, replace = false) }
                waitForIdle()
                onNodeWithText("Planned · Week 2").assertIsDisplayed()
                onNodeWithText("Deload week").assertIsDisplayed()
                // 60 kg, no step in a deload week, less 10 %: 54, rounded to 55.
                onNodeWithText("3 × 8 · 55 kg").assertIsDisplayed()
                screenshot("today-deload-week")
            }
        }

    @Test
    fun `the dial says Deload week on a deload day`() =
        runTest {
            val f = fixture(deloadEvery = 2)
            f.days.applyRoutine(TODAY, legs.id, replace = false)
            val bench = blocks(f).first()
            runPhoneUiTest {
                focus(f, bench.id)
                dial("Deload week").assertIsDisplayed()
                dial("27.5").assertIsDisplayed()
                screenshot("focus-deload-week")
            }
        }

    @Test
    fun `RPE and bodyweight are set options in the focus view's sheet, and a done set is rated from All sets`() =
        runTest {
            val f = fixture()
            val block = f.days.addExercise(TODAY, curl.id)!!
            runPhoneUiTest {
                focus(f, block)
                onNodeWithText("All sets").performClick()
                waitForIdle()
                onNodeWithText("Add warm-up sets").assertExists()
                onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText("All sets"))).performScrollToNode(hasTestTag(RPE_TAG))
                onNodeWithText("Set options · Set 1 of 2").assertIsDisplayed()
                onNode(hasText("8.5") and hasAnyAncestor(hasTestTag(RPE_TAG))).performClick()
                waitForIdle()
                onAllNodesWithText("RPE 8.5").onFirst().assertIsDisplayed()
                assertEquals(
                    8.5,
                    blocks(f)
                        .single()
                        .sets
                        .first()
                        .rpe,
                )
                screenshot("focus-set-options-rpe")
                onNodeWithText("Bodyweight").performClick()
                waitForIdle()
                assertEquals(listOf(true, true), blocks(f).single().sets.map { it.bodyweight })
                // Bodyweight sets get no warm-ups: their weight is only what's added.
                onNodeWithText("Add warm-up sets").assertDoesNotExist()
                screenshot("focus-set-options-bodyweight")
            }
        }

    @Test
    fun `a set done without a rest is rated afterwards by picking it in All sets`() =
        runTest {
            val f = fixture()
            val block = f.days.addExercise(TODAY, curl.id)!!
            val first =
                blocks(f)
                    .single()
                    .sets
                    .first()
                    .id
            // Curl rests 0 s: once both sets are done there's no next set and no rest.
            blocks(f).single().sets.forEach { f.days.setDone(it.id, true) }
            runPhoneUiTest {
                focus(f, block)
                onNodeWithText("All sets").performClick()
                waitForIdle()
                onNodeWithText("Set options · Set 2 of 2").assertExists()
                onNode(
                    SemanticsMatcher("rates set 1") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Rate set 1" },
                ).performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                onNode(
                    hasScrollToNodeAction() and hasAnyDescendant(hasText("All sets")),
                ).performScrollToNode(hasText("Set options · Set 1 of 2"))
                onNodeWithText("Set options · Set 1 of 2").assertIsDisplayed()
                onNode(hasText("7") and hasAnyAncestor(hasTestTag(RPE_TAG))).performClick()
                waitForIdle()
                assertEquals(listOf(7.0, null), blocks(f).single().sets.map { it.rpe })
                onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText("All sets"))).performScrollToNode(hasText("RPE 7"))
                screenshot("focus-rate-done-set")
                assertEquals(
                    first,
                    blocks(f)
                        .single()
                        .sets
                        .first { it.rpe == 7.0 }
                        .id,
                )
            }
        }

    @Test
    fun `a light load in a deload week comes out a step lighter, never the same and never 0`() =
        runTest {
            val f = fixture(deloadEvery = 2)
            runPhoneUiTest {
                today(f)
                // Curl's 10 kg less 10 % rounds back to 10, so it goes a step down.
                this@runTest.change { f.days.applyRoutine(TODAY, arms.id, replace = false) }
                waitForIdle()
                onNodeWithText("3 × 10 · 7.5 kg").assertIsDisplayed()
                onNodeWithText("Deload week").assertIsDisplayed()
                screenshot("today-deload-light-load")
            }
        }

    @Test
    fun `session details show the program week, take the day's body weight, refuse text that isn't one, and offer Undo`() =
        runTest {
            val f = fixture(deloadEvery = 2)
            f.bodyWeights.restore(BodyWeight(LocalDate(2026, 11, 2), 82.0, WeightUnit.Kg))
            f.days.applyRoutine(TODAY, legs.id, replace = false)
            runPhoneUiTest {
                today(f)
                details()
                onNodeWithText("Upper/lower · Week 2 · Deload week").assertIsDisplayed()
                onNodeWithText("Body weight").assertIsDisplayed()
                onNode(hasSetTextAction() and hasText("Weight")).performTextInput("81.5")
                waitForIdle()
                screenshot("today-details-bodyweight")
                pressBack()
                assertEquals(
                    BodyWeight(TODAY, 81.5, WeightUnit.Kg),
                    runBlocking {
                        f.bodyWeights.readings
                            .first()
                            .first()
                    },
                )
                // Text that isn't a weight says so and changes nothing.
                details()
                onNode(hasSetTextAction() and hasText("Weight")).performTextReplacement("81.5kg")
                waitForIdle()
                onNodeWithText("Enter a weight up to 1000").assertIsDisplayed()
                screenshot("today-details-bodyweight-invalid")
                pressBack()
                assertEquals(
                    81.5,
                    runBlocking {
                        f.bodyWeights.readings
                            .first()
                            .first()
                            .weight
                    },
                )
                // Emptied: the day's reading goes, with Undo.
                details()
                onNode(hasSetTextAction() and hasText("Weight")).performTextReplacement("")
                pressBack()
                onNodeWithText("Deleted 81.5 kg").assertIsDisplayed()
                screenshot("today-bodyweight-deleted-undo")
                onNodeWithText("Undo").performClick()
                waitForIdle()
                assertEquals(
                    81.5,
                    runBlocking {
                        f.bodyWeights.readings
                            .first()
                            .first()
                            .weight
                    },
                )
            }
        }

    private fun ComposeUiTest.details() {
        onNodeWithContentDescription("Workout options").performClick()
        waitForIdle()
        onNodeWithText("Session details").performClick()
        waitForIdle()
    }

    @Test
    fun `the routine editor shows a bodyweight set's last load as what was added`() =
        runTest {
            val f = fixture()
            val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
            val block = f.days.addExercise(yesterday, curl.id)!!
            blocks(f, yesterday).single { it.id == block }.sets.forEach {
                f.days.editSet(it.id, SetEdit.Bodyweight(true))
                f.days.setDone(it.id, true)
            }
            val editor = RoutineEditorViewModel(arms.id.value, f.routines, f.days, f.clock, TimeZone.UTC).clearedAfterTest()
            val picker = ExercisePickerViewModel(RoutinePickerTarget(arms.id, f.routines), f.exercises).clearedAfterTest()
            runPhoneUiTest {
                setScreen { RoutineEditorRoute(id = arms.id, onBack = {}, onUsedToday = {}, viewModel = editor, picker = picker) }
                waitForIdle()
                onNodeWithText("+10 kg").assertIsDisplayed()
                screenshot("routine-editor-bodyweight-load")
            }
        }

    @Test
    fun `the summary counts a bodyweight set's added weight only in its volume`() =
        runTest {
            val f = fixture()
            f.bodyWeights.restore(BodyWeight(TODAY, 80.0, WeightUnit.Kg))
            val block = f.days.addExercise(TODAY, curl.id)!!
            val sets = blocks(f).single { it.id == block }.sets
            // Set 1: body weight + 10 kg × 10; set 2: body weight alone × 12, which adds nothing.
            sets.forEach { f.days.editSet(it.id, SetEdit.Bodyweight(true)) }
            f.days.editSet(sets[1].id, SetEdit.Weight(null))
            f.days.editSet(sets[1].id, SetEdit.Reps(12))
            sets.forEach { f.days.setDone(it.id, true) }
            f.days.finish(TODAY)
            val vm = SummaryViewModel(TODAY.toString(), f.days, f.clock, TimeZone.UTC, MainScope()).clearedAfterTest()
            runPhoneUiTest {
                setScreen { SummaryRoute(date = TODAY, onBack = {}, viewModel = vm) }
                waitForIdle()
                onNodeWithText("100 kg").assertIsDisplayed()
                screenshot("summary-bodyweight-volume")
            }
        }
}
