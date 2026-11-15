// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import app.fit.core.WeightUnit
import app.fit.day.FakeDayRepository
import app.fit.day.FixedClock
import app.fit.day.SetEdit
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.ui.ExerciseRoute
import app.fit.exercises.ui.ExerciseViewModel
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.history.ui.CHART_TAG
import app.fit.history.ui.HistoryRoute
import app.fit.history.ui.HistoryViewModel
import app.fit.history.ui.PICKED_DAY_TAG
import app.fit.history.ui.SummaryRoute
import app.fit.history.ui.SummaryViewModel
import app.fit.history.ui.calendarTag
import app.fit.lessons.FakeLessonRepository
import app.fit.routines.FakeRoutineRepository
import app.fit.workout.bench
import app.fit.workout.plank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// History, the summary and the exercise page over a fortnight of logged workouts in memory.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HistoryUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        appScope.cancel()
        Dispatchers.resetMain()
    }

    // The app's scope, where the summary saves notes as it goes.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val today = LocalDate(2026, 11, 9)
    private val clock = FixedClock(Instant.parse("2026-11-09T18:00:00Z"))
    private val exercises = FakeExerciseRepository(listOf(bench, plank))
    private val days = FakeDayRepository(exercises, FakeRoutineRepository(exercises), clock)
    private val guidedLogs = FakeGuidedRepository()

    private fun d(day: Int) = LocalDate(2026, 11, day)

    // A workout of bench sets at [load] × 5 (as many as [sets]) and plank holds of [hold] seconds.
    private fun log(
        date: LocalDate,
        name: String,
        load: Double,
        sets: Int = 3,
        hold: Int? = null,
        unit: WeightUnit = WeightUnit.Kg,
        minutes: Int? = 50,
        fatigue: Int? = null,
    ) = runBlocking {
        val block = days.addExercise(date, bench.id)!!
        repeat(sets - 3) { days.addSet(block) }
        days
            .dayOfBlock(block)
            .first()!!
            .blocks
            .single { it.id == block }
            .sets
            .forEach {
                days.editSet(it.id, SetEdit.Unit(unit))
                days.editSet(it.id, SetEdit.Weight(load))
                days.editSet(it.id, SetEdit.Reps(5))
                days.setDone(it.id, true)
            }
        if (hold != null) {
            val plankBlock = days.addExercise(date, plank.id)!!
            days
                .dayOfBlock(plankBlock)
                .first()!!
                .blocks
                .single { it.id == plankBlock }
                .sets
                .forEach {
                    days.editSet(it.id, SetEdit.Duration(hold.seconds))
                    days.setDone(it.id, true)
                }
        }
        days.setName(date, name)
        if (minutes != null) {
            val start = date.atTime(LocalTime(18, 0)).toInstant(TimeZone.UTC)
            days.setTimes(date, start, start + minutes.minutes)
        }
        days.setFatigue(date, fatigue)
    }

    // Two weeks and a day in lb: a lighter start, a big day (18 sets), a day only logged, and today's
    // records.
    private fun fortnight() {
        log(LocalDate(2026, 10, 30), "Push", 135.0, unit = WeightUnit.Lb)
        log(d(2), "Push", 57.5, hold = 40, fatigue = 3)
        log(d(4), "Push", 60.0, sets = 10, hold = 45, fatigue = 4, minutes = 64)
        log(d(6), "Heavy push", 60.0, sets = 18, minutes = 81, fatigue = 5)
        log(d(7), "Push", 60.0, minutes = null)
        log(d(8), "Push", 62.5, hold = 50, fatigue = 2)
        log(today, "Push", 65.0, hold = 60)
    }

    private fun SkikoComposeUiTest.history(
        widthDp: Int = 411,
        heightDp: Int = 891,
        opened: MutableList<String> = mutableListOf(),
    ) {
        val vm = HistoryViewModel(days, guidedLogs, clock, TimeZone.UTC).clearedAfterTest()
        setScreen {
            HistoryRoute(
                onOpenDay = { opened += "day:$it" },
                onOpenSummary = { opened += "summary:$it" },
                onBack = {},
                viewModel = vm,
            )
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.summary(date: LocalDate = today) {
        val vm = SummaryViewModel(date.toString(), days, clock, TimeZone.UTC, appScope).clearedAfterTest()
        setScreen { SummaryRoute(date = date, onBack = {}, viewModel = vm) }
        waitForIdle()
    }

    @Test
    fun `History shows the counts, the month with marked days, and the workouts with their fatigue`() {
        fortnight()
        val opened = mutableListOf<String>()
        runPhoneUiTest {
            history(opened = opened)
            // 7 workouts; 5 from the 3rd to today; 4 in a row back from today (the 5th had none).
            onNode(hasText("Workouts")).assert(hasText("7"))
            onNode(hasText("Last 7 days")).assert(hasText("5"))
            onNode(hasText("Day streak")).assert(hasText("4"))
            onNodeWithText("November 2026").assertIsDisplayed()
            onNodeWithTag(calendarTag(d(6))).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Finished workout"))
            onNodeWithTag(calendarTag(d(7))).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Workout"))
            onNodeWithTag(calendarTag(d(3))).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            screenshot("history")
            onNode(hasScrollAction()).performScrollToNode(hasText("Fatigue 4"))
            waitForIdle()
            onNodeWithText("Heavy push").assertIsDisplayed()
            onNodeWithText("Fatigue 5").assertIsDisplayed()
            screenshot("history-list")
            onNodeWithText("Heavy push").performClick()
        }
        assertEquals(listOf("summary:${d(6)}"), opened)
    }

    @Test
    fun `a recovery session gets its own mark on the calendar, apart from the workout's, and never counts`() {
        fortnight()
        runBlocking {
            listOf(d(6), d(3)).forEach { day ->
                val at = Instant.parse("${day}T07:00:00Z")
                guidedLogs.log(
                    GuidedLog(GuidedPlanId(1), GuidedKind.Recovery, "Wrist reset", day, at, at + 8.minutes, GuidedOutcome.Completed, 12),
                )
            }
        }
        runPhoneUiTest {
            history()
            onNode(hasText("Workouts")).assert(hasText("7"))
            onNodeWithTag(calendarTag(d(6))).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Finished workout, Recovery"),
            )
            onNodeWithTag(calendarTag(d(3))).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Recovery"))
            screenshot("history-recovery-mark")
        }
    }

    private fun recovery(
        day: LocalDate,
        title: String = "Wrist reset",
    ) = runBlocking {
        val at = Instant.parse("${day}T07:00:00Z")
        guidedLogs.log(GuidedLog(GuidedPlanId(1), GuidedKind.Recovery, title, day, at, at + 8.minutes, GuidedOutcome.Completed, 12))
    }

    private fun SkikoComposeUiTest.showPicked() {
        onNode(hasScrollAction()).performScrollToNode(hasText("Open day"))
        waitForIdle()
    }

    @Test
    fun `tapping a day in the History calendar stays on History and shows that day's workout, records and recovery`() {
        fortnight()
        recovery(d(8))
        val opened = mutableListOf<String>()
        runPhoneUiTest {
            history(opened = opened)
            // Today is picked when History opens.
            onNodeWithTag(PICKED_DAY_TAG).assert(hasAnyDescendant(hasText("Mon, 9 Nov")))
            onNodeWithTag(calendarTag(d(8))).performClick()
            waitForIdle()
            assertEquals(emptyList(), opened)
            onNodeWithText("History").assertIsDisplayed()
            showPicked()
            val picked = hasAnyAncestor(hasTestTag(PICKED_DAY_TAG))
            onNode(picked and hasText("Sun, 8 Nov")).assertIsDisplayed()
            onNode(picked and hasText("50:00 · 5 sets · 938 kg")).assertIsDisplayed()
            onNode(picked and hasText("62.5 kg (was 60)")).assertIsDisplayed()
            onNode(picked and hasText("Wrist reset · 8 min")).assertIsDisplayed()
            screenshot("history-day-workout")
            // Its workout row opens the summary, as the list's rows do.
            onNode(picked and hasText("Fatigue 2")).performClick()
            assertEquals(listOf("summary:${d(8)}"), opened)
        }
    }

    @Test
    fun `Open day under the History calendar opens the picked day on Today`() {
        fortnight()
        val opened = mutableListOf<String>()
        runPhoneUiTest {
            history(opened = opened)
            onNodeWithTag(calendarTag(d(4))).performClick()
            waitForIdle()
            showPicked()
            onNodeWithText("Open day").performClick()
        }
        assertEquals(listOf("day:${d(4)}"), opened)
    }

    @Test
    fun `a picked day with only a recovery session shows it, and one with nothing says so quietly and can still be opened`() {
        fortnight()
        recovery(d(3))
        val opened = mutableListOf<String>()
        runPhoneUiTest {
            history(opened = opened)
            val picked = hasAnyAncestor(hasTestTag(PICKED_DAY_TAG))
            onNodeWithTag(calendarTag(d(3))).performClick()
            waitForIdle()
            showPicked()
            onNode(picked and hasText("Wrist reset · 8 min")).assertIsDisplayed()
            onNodeWithText("Nothing logged").assertDoesNotExist()
            screenshot("history-day-recovery-only")
            // The 5th had no workout: no word about missing one.
            onNode(hasScrollAction()).performScrollToNode(hasTestTag(calendarTag(d(5))))
            onNodeWithTag(calendarTag(d(5))).performClick()
            waitForIdle()
            showPicked()
            onNode(picked and hasText("Thu, 5 Nov")).assertIsDisplayed()
            onNode(picked and hasText("Nothing logged")).assertIsDisplayed()
            onNode(picked and hasText("miss", substring = true, ignoreCase = true)).assertDoesNotExist()
            screenshot("history-day-nothing")
            onNodeWithText("Open day").performClick()
        }
        assertEquals(listOf("day:${d(5)}"), opened)
    }

    @Test
    fun `the History calendar moves back and forth by month, keeping the picked day`() {
        fortnight()
        runPhoneUiTest {
            history()
            onNodeWithTag(calendarTag(d(4))).performClick()
            onNodeWithContentDescription("Previous month").performClick()
            waitForIdle()
            onNodeWithText("October 2026").assertIsDisplayed()
            onNodeWithTag(PICKED_DAY_TAG).assert(hasAnyDescendant(hasText("Wed, 4 Nov")))
            onNodeWithContentDescription("Next month").performClick()
            waitForIdle()
            onNodeWithText("November 2026").assertIsDisplayed()
        }
    }

    @Test
    fun `on a 360 x 780 dp phone a tapped day comes into view by itself, down to Open day, with its calendar cell still showing`() {
        fortnight()
        recovery(d(8))
        runPhoneUiTest(360, 780) {
            history()
            onNodeWithText("Open day").assertIsNotDisplayed()
            onNodeWithTag(calendarTag(d(8))).performClick()
            waitForIdle()
            onNodeWithText("62.5 kg (was 60)").assertIsDisplayed()
            onNodeWithText("Wrist reset · 8 min").assertIsDisplayed()
            onNodeWithText("Open day").assertIsDisplayed()
            onNodeWithTag(calendarTag(d(8))).assertIsDisplayed()
            screenshot("history-day-tapped-small-phone")
        }
    }

    @Test
    fun `on a 360 x 780 dp phone a picked day's summary and Open day are reachable`() {
        fortnight()
        recovery(d(8))
        runPhoneUiTest(360, 780) {
            history()
            onNodeWithTag(calendarTag(d(8))).performClick()
            waitForIdle()
            showPicked()
            onNodeWithText("62.5 kg (was 60)").assertIsDisplayed()
            onNodeWithText("Open day").assertIsDisplayed()
            screenshot("history-day-workout-small-phone")
        }
        runPhoneUiTest(360, 780) {
            history()
            onNodeWithTag(calendarTag(d(5))).performClick()
            waitForIdle()
            showPicked()
            onNodeWithText("Nothing logged").assertIsDisplayed()
            screenshot("history-day-nothing-small-phone")
        }
    }

    @Test
    fun `History with nothing logged says so, and shows no streak to keep`() =
        runPhoneUiTest {
            history()
            onNodeWithText("No workouts yet.").assertIsDisplayed()
            onNodeWithText("Day streak").assertDoesNotExist()
            screenshot("history-empty")
        }

    @Test
    fun `on a 360 x 780 dp phone History and the summary fit`() {
        fortnight()
        runPhoneUiTest(360, 780) {
            history()
            onNodeWithText("Day streak").assertIsDisplayed()
            screenshot("history-small-phone")
        }
        runPhoneUiTest(360, 780) {
            summary()
            onNodeWithText("65 kg (was 62.5)").assertIsDisplayed()
            screenshot("summary-small-phone")
        }
    }

    @Test
    fun `the summary shows the result, records against earlier days in one unit, notes and fatigue`() {
        fortnight()
        runPhoneUiTest {
            summary()
            onNodeWithText("Completed · Mon, 9 Nov").assertIsDisplayed()
            onNodeWithText("50:00").assertIsDisplayed()
            onNodeWithText("975 kg").assertIsDisplayed()
            // 135 lb in October is never compared with kg.
            onNodeWithText("65 kg (was 62.5)").assertIsDisplayed()
            onNodeWithText("1:00 (was 0:50)").assertIsDisplayed()
            onNode(hasSetTextAction()).performTextReplacement("Shoulder felt fine")
            onNode(hasContentDescription("Fatigue 3 of 5")).performClick()
            waitForIdle()
            onNode(hasContentDescription("Fatigue 3 of 5")).assertIsOn()
            onNode(hasScrollAction()).performScrollToNode(hasText("Done"))
            screenshot("summary")
        }
        val day = runBlocking { days.day(today).first()!! }
        assertEquals(3, day.fatigue)
        assertEquals("Shoulder felt fine", day.notes)
    }

    @Test
    fun `a summary without records or sets says so plainly`() {
        log(d(2), "Push", 60.0)
        runPhoneUiTest {
            summary(d(2))
            onNodeWithText("No new records").assertIsDisplayed()
        }
        runPhoneUiTest {
            summary(d(3))
            onNodeWithText("No sets logged").assertIsDisplayed()
            screenshot("summary-nothing-logged")
        }
    }

    @Test
    fun `the exercise page charts the best load per day in its unit and lists sets by day`() {
        fortnight()
        runPhoneUiTest {
            val vm = ExerciseViewModel(bench.id.value, exercises, days, FakeLessonRepository(), clock, TimeZone.UTC).clearedAfterTest()
            setScreen { ExerciseRoute(id = bench.id, onEdit = {}, onOpenLesson = {}, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNode(hasScrollAction()).performScrollToNode(hasTestTag(CHART_TAG))
            waitForIdle()
            // The lb day isn't on the kg chart.
            onNodeWithTag(CHART_TAG).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Top set: 57.5 kg on Mon, 2 Nov, 65 kg on Mon, 9 Nov"),
                ),
            )
            screenshot("exercise-progress-chart")
            onNode(hasScrollAction()).performScrollToNode(hasText("Archive"))
            waitForIdle()
            onNodeWithText("135 lb × 5  ·  135 lb × 5  ·  135 lb × 5").assertIsDisplayed()
            screenshot("exercise-history")
        }
    }

    @Test
    fun `an exercise never done says so instead of a chart`() =
        runPhoneUiTest {
            val vm = ExerciseViewModel(plank.id.value, exercises, days, FakeLessonRepository(), clock, TimeZone.UTC).clearedAfterTest()
            setScreen { ExerciseRoute(id = plank.id, onEdit = {}, onOpenLesson = {}, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNode(hasScrollAction()).performScrollToNode(hasText("No sets done yet."))
            onNodeWithText("No sets done yet.").assertIsDisplayed()
        }

    @Test
    fun `with workouts but none yesterday or today, History shows no streak at all`() {
        log(d(2), "Push", 60.0)
        log(d(5), "Push", 60.0, fatigue = 2)
        runPhoneUiTest {
            history()
            onNode(hasText("Workouts")).assert(hasText("2"))
            onNode(hasText("Last 7 days")).assert(hasText("1"))
            onNodeWithText("Day streak").assertDoesNotExist()
            screenshot("history-no-streak")
        }
    }

    @Test
    fun `a day logged but never finished has a summary that says Logged, with no time`() {
        fortnight()
        runPhoneUiTest {
            summary(d(7))
            onNodeWithText("Logged · Sat, 7 Nov").assertIsDisplayed()
            onNodeWithText("Completed", substring = true).assertDoesNotExist()
            onNode(hasText("Time")).assert(hasText("–"))
            onNode(hasText("Sets")).assert(hasText("3"))
            screenshot("summary-logged-day")
        }
    }

    @Test
    fun `notes typed in the summary are saved when it closes, without waiting for the pause`() {
        log(today, "Push", 60.0)
        runPhoneUiTest {
            var shown by mutableStateOf(true)
            val vm = SummaryViewModel(today.toString(), days, clock, TimeZone.UTC, appScope).clearedAfterTest()
            setScreen { if (shown) SummaryRoute(date = today, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNode(hasSetTextAction()).performTextReplacement("Saved on the way out")
            shown = false
            waitForIdle()
            assertEquals("Saved on the way out", runBlocking { days.day(today).first()!!.notes })
        }
    }

    @Test
    fun `dates from another year say so, and the chart spaces sessions by date`() {
        log(LocalDate(2025, 12, 30), "Push", 50.0)
        log(d(2), "Push", 55.0)
        log(d(3), "Push", 57.5)
        log(today, "Push", 60.0)
        runPhoneUiTest {
            history()
            onNode(hasScrollAction()).performScrollToNode(hasText("Tue, 30 Dec 2025", substring = true))
            onNodeWithText("Tue, 30 Dec 2025", substring = true).assertIsDisplayed()
            onNodeWithText("Mon, 9 Nov 2026", substring = true).assertDoesNotExist()
        }
        runPhoneUiTest {
            val vm = ExerciseViewModel(bench.id.value, exercises, days, FakeLessonRepository(), clock, TimeZone.UTC).clearedAfterTest()
            setScreen { ExerciseRoute(id = bench.id, onEdit = {}, onOpenLesson = {}, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNode(hasScrollAction()).performScrollToNode(hasTestTag(CHART_TAG))
            waitForIdle()
            onNodeWithTag(CHART_TAG).assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Top set: 50 kg on Tue, 30 Dec 2025, 60 kg on Mon, 9 Nov"),
                ),
            )
            screenshot("exercise-chart-gaps")
        }
    }

    @Test
    fun `a long exercise history shows its latest 30 days, then Show all`() {
        (1..34).forEach { log(LocalDate(2026, 9, 1).plus(it, DateTimeUnit.DAY), "Push", 60.0) }
        runPhoneUiTest {
            val vm = ExerciseViewModel(bench.id.value, exercises, days, FakeLessonRepository(), clock, TimeZone.UTC).clearedAfterTest()
            setScreen { ExerciseRoute(id = bench.id, onEdit = {}, onOpenLesson = {}, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNode(hasScrollAction()).performScrollToNode(hasText("Show all (4 more days)"))
            waitForIdle()
            screenshot("exercise-history-show-all")
            onNodeWithText("Show all (4 more days)").performClick()
            waitForIdle()
            onNode(
                hasScrollAction(),
            ).performScrollToNode(hasText("Wed, 2 Sept", substring = true).or(hasText("Wed, 2 Sep", substring = true)))
            onNodeWithText("Show all", substring = true).assertDoesNotExist()
        }
    }
}
