// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.fit.day.Block
import app.fit.day.ui.TimeField
import app.fit.day.ui.TodayAction
import app.fit.day.ui.TodayRoute
import app.fit.day.ui.TodayViewModel
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.history.ui.calendarTag
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.WARMUP_SECTION
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutController
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.plank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class TodayUiTest {
    // Not a test dispatcher: runTest would adopt its scheduler, and the ViewModels' once-a-second
    // tickers would keep it from ever going idle. Timers run on the fixture's TestClock either way.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val legs =
        Routine(
            RoutineId(1),
            null,
            "Legs",
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

    private val yesterday = TODAY.minus(1, DateTimeUnit.DAY)

    private class Today(
        val f: WorkoutFixture,
        val controller: WorkoutController,
        val vm: TodayViewModel,
    )

    // Legs as a routine, and yesterday's bench all done at 60 kg × 8, so today goes up a step.
    private suspend fun TestScope.fixture(): Today {
        val f = WorkoutFixture(this)
        f.routines.all.value = listOf(legs)
        val block = f.days.addExercise(yesterday, bench.id)!!
        f.days
            .dayOfBlock(block)
            .first()!!
            .blocks
            .single()
            .sets
            .forEach { f.days.setDone(it.id, true) }
        val controller = f.controller()
        return Today(
            f,
            controller,
            TodayViewModel(
                f.days,
                f.routines,
                f.programs,
                f.bodyWeights,
                f.settings,
                controller,
                f.guided(),
                f.plans,
                f.clock,
                TimeZone.UTC,
            ).clearedAfterTest(),
        )
    }

    private fun ComposeUiTest.today(t: Today) = setScreen { TodayRoute(onOpenBlock = {}, showToday = 0, viewModel = t.vm) }

    // An empty day lists the routines; one tap applies one.
    private fun ComposeUiTest.chooseLegs() {
        onNodeWithText("Legs").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.options(item: String) {
        onNodeWithContentDescription("Workout options").performClick()
        waitForIdle()
        onNodeWithText(item).performClick()
        waitForIdle()
    }

    // The fake repository answers at once.
    private fun blocksOf(t: Today): List<Block> =
        runBlocking {
            t.f.days
                .day(TODAY)
                .first()!!
                .blocks
        }

    @Test
    fun `an empty day fills from a routine, and the hero card says it's planned`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                onNodeWithText("Nothing planned").assertIsDisplayed()
                screenshot("today-empty")
                chooseLegs()
                onNodeWithText("Legs").assertIsDisplayed()
                onNodeWithText("Planned").assertIsDisplayed()
                onNodeWithText("2 exercises · ~9 min").assertIsDisplayed()
                // A dense plan: targets and the load that went up a step.
                onNodeWithText("3 × 8 · 62.5 kg").assertIsDisplayed()
                onNodeWithText("Start workout").assertIsDisplayed()
                screenshot("today-filled-from-routine")
            }
        }

    @Test
    fun `a recovery session done today is a quiet line under the workout, with its own mark in the week`() =
        runTest {
            val t = fixture()
            t.f.days.addExercise(TODAY, plank.id)
            val at = START - 2.hours
            t.f.plans.log(
                GuidedLog(GuidedPlanId(1), GuidedKind.Recovery, "Wrist reset", TODAY, at, at + 8.minutes, GuidedOutcome.Completed, 12),
            )
            runPhoneUiTest {
                today(t)
                onNodeWithText("Wrist reset · 8 min").assertIsDisplayed()
                screenshot("today-recovery-line")
            }
        }

    @Test
    fun `today's warm-up row runs the warm-up on the player`() =
        runTest {
            val t = fixture()
            t.f.days.addExercise(TODAY, plank.id, WARMUP_SECTION)
            t.f.days.addExercise(TODAY, bench.id)
            runPhoneUiTest {
                var players = 0
                setScreen { TodayRoute(onOpenBlock = {}, showToday = 0, onOpenPlayer = { players++ }, viewModel = t.vm) }
                waitForIdle()
                onNodeWithText("Warm-up · 1 exercise").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, players)
            }
        }

    @Test
    fun `in progress, the hero names the exercise and its next set, a running rest, and the plan marks it`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                onNodeWithText("Start workout").performClick()
                waitForIdle()
                val bench = blocksOf(t).first()
                this@runTest.change { bench.sets.take(3).forEach { t.controller.setDone(bench.id, it.id, true) } }
                advance(this@runTest, 18.seconds)
                // Today's clock moves on the screen's own once-a-second tick.
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("In progress · 0:18").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("In progress · 0:18").assertIsDisplayed()
                onAllNodesWithText("Bench press").assertCountEquals(2)
                onNodeWithText("Next · Set 2 of 3 · 62.5 kg × 8").assertIsDisplayed()
                onNodeWithText("Resting · 1:12 left").assertIsDisplayed()
                onNodeWithText("Resume workout").assertIsDisplayed()
                // The current row shows its progress; the list is named after the day.
                onNodeWithText("1/3").assertIsDisplayed()
                screenshot("today-in-progress")
            }
        }

    @Test
    fun `previous and next walk the week, and a past day says it was logged and offers its summary`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                onNodeWithContentDescription("Previous day").performClick()
                waitForIdle()
                onNodeWithText("Yesterday").assertIsDisplayed()
                onNodeWithText("Logged").assertIsDisplayed()
                onNodeWithText("3/3 sets · 1,440 kg").assertIsDisplayed()
                onNodeWithText("View summary").assertIsDisplayed()
                onNodeWithText("Start workout").assertDoesNotExist()
                onNodeWithText("3/3").assertIsDisplayed()
                screenshot("today-yesterday")
                onNodeWithContentDescription("Next day").performClick()
                onNodeWithContentDescription("Next day").performClick()
                waitForIdle()
                onNodeWithText("Tomorrow").assertIsDisplayed()
            }
        }

    @Test
    fun `the options sheet groups its rows with icons, and Back closes it before the screen`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                onNodeWithContentDescription("Workout options").performClick()
                waitForIdle()
                onNodeWithText("Session details").assertIsDisplayed()
                onNodeWithText("Save as routine").assertIsDisplayed()
                screenshot("today-options-sheet")
                pressBack()
                onNodeWithText("Session details").assertDoesNotExist()
                onNodeWithText("Legs").assertIsDisplayed()
                assertNull(t.vm.uiState.value.sheet)
            }
        }

    @Test
    fun `Save as routine starts from a free name and says it was saved`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                options("Save as routine")
                onNode(hasSetTextAction() and hasText("Legs 2")).assertExists()
                screenshot("today-save-as-routine")
                onNodeWithText("Save").performClick()
                waitForIdle()
                onNodeWithText("Saved as routine “Legs 2”").assertIsDisplayed()
                assertEquals(
                    listOf("Legs", "Legs 2"),
                    t.f.routines.all.value
                        .map { it.name },
                )
            }
        }

    @Test
    fun `Delete this day empties it, and Undo brings it back`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                options("Delete this day")
                onNodeWithText("Nothing planned").assertIsDisplayed()
                onNodeWithText("Undo").assertIsDisplayed()
                screenshot("today-deleted-undo")
                onNodeWithText("Undo").performClick()
                waitForIdle()
                onNodeWithText("Legs").assertIsDisplayed()
            }
        }

    @Test
    fun `reorder drags a row in place, and a swipe removes one with Undo`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                options("Reorder")
                onNodeWithText("Reorder").assertIsDisplayed()
                screenshot("today-reorder")
                val (bench, plank) = blocksOf(t)
                // Plank's handle, dragged up past bench.
                // The handle itself, not the row it's merged into.
                onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true)[1].performTouchInput {
                    down(center)
                    repeat(10) { moveBy(Offset(0f, -20f)) }
                    up()
                }
                advance(this@runTest, 0.seconds)
                assertEquals(listOf(plank.id, bench.id), blocksOf(t).map { it.id })
                onNodeWithText("Bench press").performTouchInput { swipeLeft() }
                advance(this@runTest, 0.seconds)
                assertEquals(listOf(plank.id), blocksOf(t).map { it.id })
                onNodeWithText("Undo").assertIsDisplayed()
                screenshot("today-reorder-removed-undo")
                // Back leaves reorder mode.
                pressBack()
                onNodeWithText("Nothing planned").assertDoesNotExist()
                onNodeWithText("Planned").assertIsDisplayed()
            }
        }

    @Test
    fun `session details refuse a start after the end`() =
        runTest {
            val t = fixture()
            val vm = t.vm
            runPhoneUiTest {
                today(t)
                chooseLegs()
                onNodeWithText("Start workout").performClick()
                vm.onAction(TodayAction.Finish)
                waitForIdle()
                assertNotNull(
                    vm.uiState.value.day
                        ?.finishedAt,
                )
                options("Session details")
                // The time dialog's own wheel is the platform's; pick a time through its action.
                vm.onAction(TodayAction.EditTime(TimeField.Start))
                vm.onAction(TodayAction.SetTime(LocalTime(11, 0)))
                waitForIdle()
                onNodeWithText("That's after the workout ended.").assertIsDisplayed()
                screenshot("today-start-after-end")
                assertNotNull(
                    vm.uiState.value.day
                        ?.startedAt,
                )
            }
        }

    @Test
    fun `a finished day leads with its result and offers its summary, which Finish opens`() =
        runTest {
            val t = fixture()
            val summaries = mutableListOf<LocalDate>()
            runPhoneUiTest {
                setScreen { TodayRoute(onOpenBlock = {}, showToday = 0, onOpenSummary = { summaries += it }, viewModel = t.vm) }
                chooseLegs()
                onNodeWithText("Start workout").performClick()
                waitForIdle()
                advance(this@runTest, 30.minutes)
                val bench = blocksOf(t).first()
                val plank = blocksOf(t)[1]
                // Every set ticked: the one button finishes.
                this@runTest.change {
                    bench.sets.forEach { t.controller.setDone(bench.id, it.id, true) }
                    plank.sets.forEach { t.controller.setDone(plank.id, it.id, true) }
                }
                advance(this@runTest, 0.seconds)
                onNodeWithText("All sets done").assertIsDisplayed()
                onNodeWithText("Finish workout").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Completed").assertIsDisplayed()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("30:00 · 5 sets · 1,500 kg").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf(TODAY), summaries)
                onNodeWithText("View summary").assertIsDisplayed()
                onNodeWithText("Review").assertDoesNotExist()
                onNodeWithText("Start workout").assertDoesNotExist()
                onNodeWithText("Resume workout").assertDoesNotExist()
                screenshot("today-completed")
                onNodeWithText("View summary").performClick()
                waitForIdle()
                assertEquals(listOf(TODAY, TODAY), summaries)
            }
        }

    @Test
    fun `a day finished without sets says nothing was logged, never 0 of 5`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                t.vm.onAction(TodayAction.Finish)
                waitForIdle()
                onNodeWithText("Completed").assertIsDisplayed()
                onNodeWithText("No sets logged").assertIsDisplayed()
                onAllNodesWithText("0/5", substring = true).assertCountEquals(0)
                screenshot("today-completed-nothing-logged")
            }
        }

    @Test
    fun `the calendar action opens a month with marked days, and a day picked in it opens on Today`() =
        runTest {
            val t = fixture()
            runPhoneUiTest {
                today(t)
                chooseLegs()
                val bench = blocksOf(t).first()
                this@runTest.change { bench.sets.forEach { t.controller.setDone(bench.id, it.id, true) } }
                advance(this@runTest, 0.seconds)
                onNodeWithContentDescription("Calendar").performClick()
                waitForIdle()
                onNodeWithText("November 2026").assertIsDisplayed()
                // Yesterday and today have done sets; neither is finished.
                onNodeWithTag(calendarTag(yesterday)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Workout"))
                onNodeWithTag(calendarTag(TODAY)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Workout"))
                onNodeWithTag(
                    calendarTag(LocalDate(2026, 11, 3)),
                ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                screenshot("today-calendar-sheet")
                onNodeWithContentDescription("Previous month").performClick()
                waitForIdle()
                onNodeWithText("October 2026").assertIsDisplayed()
                onNodeWithContentDescription("Next month").performClick()
                waitForIdle()
                onNodeWithTag(calendarTag(yesterday)).performClick()
                waitForIdle()
                onNodeWithText("November 2026").assertDoesNotExist()
                onNodeWithText("Yesterday").assertIsDisplayed()
                onNodeWithText("Logged").assertIsDisplayed()
                // Back from the sheet leaves the sheet, not the screen.
                onNodeWithContentDescription("Calendar").performClick()
                waitForIdle()
                pressBack()
                onNodeWithText("November 2026").assertDoesNotExist()
                onNodeWithText("Yesterday").assertIsDisplayed()
            }
        }
}
