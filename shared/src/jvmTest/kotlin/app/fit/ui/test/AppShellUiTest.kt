// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room3.Room
import app.fit.content.Archives
import app.fit.content.BuiltInContent
import app.fit.content.BuiltInContentSync
import app.fit.content.ImageProbe
import app.fit.content.readContentFiles
import app.fit.core.AppInfo
import app.fit.core.ApplicationScope
import app.fit.core.Documents
import app.fit.core.OpenedDocument
import app.fit.data.db.AppDatabase
import app.fit.data.db.Files10
import app.fit.data.db.buildAppDatabase
import app.fit.data.prefs.SETTINGS_FILE_NAME
import app.fit.data.prefs.createSettingsDataStore
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.day.FixedClock
import app.fit.day.SetEdit
import app.fit.di.sharedModules
import app.fit.exercises.ExerciseRepository
import app.fit.guided.FakeArchives
import app.fit.guided.FakeDocuments
import app.fit.guided.FakeGuidedAlerts
import app.fit.guided.FakeLauncherShortcuts
import app.fit.guided.GuidedAlerts
import app.fit.guided.LauncherShortcuts
import app.fit.history.ui.calendarTag
import app.fit.reminders.FakeReminderAlarms
import app.fit.reminders.FakeReminderNotices
import app.fit.reminders.ReminderAlarms
import app.fit.reminders.ReminderNotices
import app.fit.routines.RoutineRepository
import app.fit.ui.App
import app.fit.workout.FakeWakeupScheduler
import app.fit.workout.FakeWorkoutAlerts
import app.fit.workout.WakeupScheduler
import app.fit.workout.WorkoutAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Every screen in the full app (toolbar, navigation, Koin), over a real Room database and DataStore
// in a temp folder, filled with the content the app ships with plus a fixture library (51 exercises
// and 5 routines together): the shipped set is a small example, and the screens need more to show.
// One screenshot per screen and state, for reviewing the UI without the phone.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class, InternalComposeUiApi::class)
class AppShellUiTest {
    private val dir = Files.createTempDirectory("fit-app")
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val today = LocalDate(2026, 11, 9)
    private lateinit var koin: Koin
    private val documents = FakeDocuments()
    private val fixture = File(checkNotNull(javaClass.getResource("/content-fixture")).toURI())

    // Not a test dispatcher: see the other UI tests. Storage runs on its real IO threads here.
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val platform =
            module {
                single { createSettingsDataStore { dir.resolve(SETTINGS_FILE_NAME).toString() } }
                single { buildAppDatabase(Room.databaseBuilder<AppDatabase>(dir.resolve("fit.db").toString()), get()) }
                single<BuiltInContent> {
                    object : BuiltInContent {
                        override suspend fun files() = readContentFiles(File(System.getProperty("content.dir"))) + readContentFiles(fixture)

                        override suspend fun imagePaths() = emptySet<String>()

                        override suspend fun image(path: String): ByteArray? = null
                    }
                }
                single<WakeupScheduler> { FakeWakeupScheduler() }
                single<WorkoutAlerts> { FakeWorkoutAlerts() }
                single<GuidedAlerts> { FakeGuidedAlerts() }
                single<LauncherShortcuts> { FakeLauncherShortcuts() }
                single<ReminderAlarms> { FakeReminderAlarms() }
                single<ReminderNotices> { FakeReminderNotices() }
                single<Archives> { FakeArchives() }
                single { AppInfo("1.0.0") }
                single<ImageProbe> { ImageProbe { true } }
                single<Documents> { documents }
                // A fixed Monday morning in UTC, so every run looks the same.
                single<Clock> { clock }
                factory<TimeZone> { TimeZone.UTC }
            }
        koin = startKoin { modules(sharedModules + platform) }.koin
        runBlocking { koin.get<BuiltInContentSync>().syncIfChanged() }
    }

    @AfterTest
    fun tearDown() {
        koin.get<ApplicationScope>().cancel()
        koin.get<AppDatabase>().close()
        stopKoin()
        Dispatchers.resetMain()
    }

    private val days: DayRepository get() = koin.get()

    private fun routine(name: String) =
        runBlocking {
            koin
                .get<RoutineRepository>()
                .routines
                .first()
                .single { it.name == name }
                .id
        }

    private fun exercise(name: String) =
        runBlocking {
            koin
                .get<ExerciseRepository>()
                .exercises
                .first()
                .single { it.fields.name == name }
                .id
        }

    private fun blocks() = runBlocking { days.day(today).first()!!.blocks }

    private fun block(name: String): BlockId = blocks().first { it.exercise.fields.name == name }.id

    private fun SkikoComposeUiTest.app() {
        setContent { PhoneHost { CompositionLocalProvider(LocalSystemTheme provides SystemTheme.Dark) { App() } } }
        waitFor("Today")
    }

    // Storage answers on its own threads, so wait for what the screen should show.
    private fun SkikoComposeUiTest.waitFor(text: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.tab(label: String) {
        onAllNodesWithContentDescription(label).onFirst().performClick()
        waitForIdle()
    }

    private fun SkikoComposeUiTest.open(text: String) {
        waitFor(text)
        onAllNodesWithText(text).onFirst().performClick()
        waitForIdle()
    }

    // The toolbar's tab buttons are named after the tabs.
    private fun SkikoComposeUiTest.toolbarShown(): Boolean = onAllNodesWithContentDescription("Recover").fetchSemanticsNodes().isNotEmpty()

    // Train

    @Test
    fun `Train opens on Routines and switches to Exercises, which searches`() =
        runPhoneUiTest {
            app()
            tab("Train")
            waitFor("Upper body — dumbbell")
            screenshot("app-train")
            open("Exercises")
            waitFor("Search exercises")
            screenshot("app-exercises")
            onNode(hasSetTextAction()).performTextInput("press")
            waitFor("Bench press")
            screenshot("app-exercises-search")
            open("Routines")
            waitFor("Upper body — dumbbell")
        }

    @Test
    fun `Train's Lessons lists the bundled push-up lesson, its page renders and Practice opens the player`() =
        runPhoneUiTest {
            app()
            tab("Train")
            open("Lessons")
            waitFor("Push-up, Wall push-up")
            screenshot("app-lessons")
            open("Push-up")
            waitFor("Setup")
            assertFalse(toolbarShown())
            onNodeWithText("Lesson").assertExists()
            screenshot("app-lesson")
            onNode(hasScrollAction()).performScrollToNode(hasText("Stop if", substring = true))
            waitForIdle()
            screenshot("app-lesson-progressions")
            onNode(hasScrollAction()).performScrollToNode(hasText("Wall push-up"))
            waitForIdle()
            screenshot("app-lesson-exercises")
            onNode(hasScrollAction()).performScrollToNode(hasText("Hinge Health", substring = true))
            waitForIdle()
            screenshot("app-lesson-sources")
            onNode(hasScrollAction()).performScrollToNode(hasText("Lesson"))
            onAllNodesWithText("Practice").onFirst().performClick()
            waitFor("Get ready")
            screenshot("app-lesson-player")
            pressBack()
            waitFor("Setup")
        }

    @Test
    fun `a lesson opened again from its exercise goes back to the lesson, so Back leaves it`() =
        runPhoneUiTest {
            app()
            tab("Train")
            open("Lessons")
            open("Push-up")
            waitFor("Setup")
            onNode(hasScrollAction()).performScrollToNode(hasText("Wall push-up"))
            open("Wall push-up")
            waitFor("Lessons")
            open("Push-up")
            waitFor("Setup")
            pressBack()
            waitFor("Push-up, Wall push-up")
            assertTrue(toolbarShown())
        }

    @Test
    fun `an exercise page and its editor render, without the toolbar`() =
        runPhoneUiTest {
            app()
            tab("Train")
            open("Exercises")
            onNode(hasSetTextAction()).performTextInput("bench")
            open("Bench press")
            onNodeWithContentDescription("Edit").assertExists()
            assertFalse(toolbarShown())
            screenshot("app-exercise-page")
            onNodeWithContentDescription("Edit").performClick()
            waitForIdle()
            onNodeWithContentDescription("Save").assertExists()
            screenshot("app-exercise-edit")
            // Back to Train, still on Exercises with the search kept.
            pressBack()
            pressBack()
            waitFor("Bench press")
            onNode(hasSetTextAction() and hasText("bench")).assertExists()
            assertTrue(toolbarShown())
        }

    @Test
    fun `the routine editor renders a routine with sections, without the toolbar`() =
        runPhoneUiTest {
            app()
            tab("Train")
            open("Upper body — dumbbell")
            waitFor("Warm-up")
            assertFalse(toolbarShown())
            screenshot("app-routine-editor-sections")
            onNode(hasScrollAction()).performScrollToNode(hasText("Stretch"))
            waitForIdle()
            screenshot("app-routine-editor-sections-end")
        }

    @Test
    fun `the toolbar shows on the four tabs only`() {
        runBlocking { days.applyRoutine(today, routine("Monday — upper"), replace = false) }
        runPhoneUiTest {
            app()
            listOf("Today", "Train", "Recover", "More").forEach {
                tab(it)
                assertTrue(toolbarShown(), it)
            }
            open("Settings")
            assertFalse(toolbarShown())
            pressBack()
            tab("Today")
            open("Bench press")
            waitFor("Complete set")
            assertFalse(toolbarShown())
            pressBack()
            waitFor("Start workout")
            onNodeWithContentDescription("Workout options").performClick()
            open("Reorder")
            waitFor("Reorder")
            assertFalse(toolbarShown())
            pressBack()
            waitFor("Start workout")
            assertTrue(toolbarShown())
        }
    }

    // More and Recover

    @Test
    fun `More renders`() =
        runPhoneUiTest {
            app()
            tab("More")
            waitFor("Settings")
            screenshot("app-more")
        }

    @Test
    fun `Train's Programs creates a program and opens its editor, and More opens Body weight`() =
        runPhoneUiTest {
            app()
            tab("Train")
            open("Programs")
            waitFor("No programs yet.")
            open("New program")
            onNode(hasSetTextAction()).performTextInput("Upper/lower")
            open("Create")
            waitFor("Week 1 starts")
            assertFalse(toolbarShown())
            screenshot("app-program-editor")
            pressBack()
            waitFor("Upper/lower")
            screenshot("app-programs")
            tab("More")
            open("Body weight")
            waitFor("Log today")
            screenshot("app-bodyweight")
        }

    @Test
    fun `the Recover tab lists the bundled wrist session, its page starts it on the player`() =
        runPhoneUiTest {
            app()
            tab("Recover")
            waitFor("Wrist reset")
            assertTrue(toolbarShown())
            screenshot("app-recover")
            open("Wrist reset")
            waitFor("Wrist flexor stretch")
            assertFalse(toolbarShown())
            screenshot("app-recover-session")
            onNodeWithText("Start").performClick()
            waitFor("Get ready")
            screenshot("app-player")
            pressBack()
            waitFor("Steps")
        }

    // Today

    @Test
    fun `today in progress renders its ticked sets and elapsed time`() {
        runBlocking {
            days.applyRoutine(today, routine("Monday — upper"), replace = false)
            days.start(today)
            blocks()
                .first()
                .sets
                .take(2)
                .forEach { days.setDone(it.id, true) }
        }
        clock.now += 25.minutes
        runPhoneUiTest {
            app()
            waitFor("In progress")
            waitFor("25:00")
            screenshot("app-today-in-progress")
        }
    }

    @Test
    fun `today in reorder mode renders, without the toolbar`() {
        runBlocking { days.applyRoutine(today, routine("Monday — upper"), replace = false) }
        runPhoneUiTest {
            app()
            onNodeWithContentDescription("Workout options").performClick()
            open("Reorder")
            waitFor("Reorder")
            assertFalse(toolbarShown())
            screenshot("app-today-reorder")
        }
    }

    @Test
    fun `reorder drags a row within its section on a day with several sections`() {
        dumbbellDay()
        runPhoneUiTest {
            app()
            waitFor("Warm-up")
            onNodeWithContentDescription("Workout options").performClick()
            open("Reorder")
            waitFor("Jumping jacks")
            val before = blocks()
            val main = before.filter { it.section == "Main" }
            // The second Main row's handle, dragged up past the first; the warm-ups stay as they are.
            val handles = onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true)
            handles[before.indexOf(main[1])].performTouchInput {
                down(center)
                repeat(12) { moveBy(Offset(0f, -20f)) }
                up()
            }
            waitUntil(timeoutMillis = 5_000) { blocks().filter { it.section == "Main" }.first().id == main[1].id }
            val after = blocks()
            assertEquals(listOf(main[1].id, main[0].id) + main.drop(2).map { it.id }, after.filter { it.section == "Main" }.map { it.id })
            assertEquals(before.filter { it.section != "Main" }.map { it.id }, after.filter { it.section != "Main" }.map { it.id })
            screenshot("app-today-reorder-sections")
        }
    }

    @Test
    fun `the Choose routine sheet renders the built-in routines`() =
        runPhoneUiTest {
            app()
            onNodeWithContentDescription("Workout options").performClick()
            waitForIdle()
            open("Choose routine")
            waitFor("Friday — lower")
            screenshot("app-today-choose-routine")
        }

    @Test
    fun `the Copy a previous day sheet renders the earlier workouts`() {
        runBlocking {
            days.applyRoutine(today.minus(1, DateTimeUnit.DAY), routine("Tuesday — lower"), replace = false)
            days.applyRoutine(today.minus(4, DateTimeUnit.DAY), routine("Thursday — upper"), replace = false)
        }
        runPhoneUiTest {
            app()
            onNodeWithContentDescription("Workout options").performClick()
            open("Copy a previous day")
            waitFor("Tuesday — lower")
            screenshot("app-today-copy-day")
        }
    }

    @Test
    fun `the Session details sheet renders`() {
        runBlocking {
            days.applyRoutine(today, routine("Monday — upper"), replace = false)
            days.start(today)
        }
        runPhoneUiTest {
            app()
            onNodeWithContentDescription("Workout options").performClick()
            open("Session details")
            waitFor("Started")
            screenshot("app-today-session-details")
        }
    }

    // The dumbbell routine has Warm-up, Main and Stretch sections; a weighted exercise gets warm-up sets.
    private fun dumbbellDay() =
        runBlocking {
            days.applyRoutine(today, routine("Upper body — dumbbell"), replace = false)
            val press = blocks().first { it.exercise.fields.name == "Incline dumbbell press" }
            press.sets.forEach { days.editSet(it.id, SetEdit.Weight(22.5)) }
            days.addWarmups(press.id)
        }

    @Test
    fun `today shows a warm-up section as one row, then the other sections`() {
        dumbbellDay()
        runPhoneUiTest {
            app()
            waitFor("Warm-up · 8 exercises")
            screenshot("app-today-sections")
            onNode(hasScrollAction()).performScrollToNode(hasText("Stretch"))
            waitForIdle()
            screenshot("app-today-stretch-section")
            // The warm-up row opens its first exercise.
            onNode(hasScrollAction()).performScrollToNode(hasText("Warm-up · 8 exercises"))
            open("Warm-up · 8 exercises")
            waitFor("Jumping jacks")
            screenshot("app-focus-warmup-from-row")
        }
    }

    // Focus view

    @Test
    fun `the focus view renders a timed exercise's current set`() {
        runBlocking { days.addExercise(today, exercise("Plank")) }
        runPhoneUiTest {
            app()
            open("Plank")
            waitFor("Start timer")
            screenshot("app-focus-timed")
        }
    }

    @Test
    fun `the focus view renders a distance exercise's current set`() {
        runBlocking { days.addExercise(today, exercise("Walk")) }
        runPhoneUiTest {
            app()
            open("Walk")
            waitFor("Start timer")
            screenshot("app-focus-distance")
        }
    }

    @Test
    fun `the focus view's sets sheet renders warm-up rows`() {
        dumbbellDay()
        runPhoneUiTest {
            app()
            onNode(hasScrollAction()).performScrollToNode(hasText("Incline dumbbell press"))
            open("Incline dumbbell press")
            waitFor("Warm-up 1 of 2")
            screenshot("app-focus-warmup-set")
            open("All sets")
            waitFor("Add set")
            screenshot("app-focus-all-sets-warmups")
        }
    }

    // History and the summary

    // A finished Monday — upper with every working set of its first two exercises done.
    private fun loggedDay(date: LocalDate) =
        runBlocking {
            days.applyRoutine(date, routine("Monday — upper"), replace = false)
            days
                .day(date)
                .first()!!
                .blocks
                .take(2)
                .flatMap { it.workingSets }
                .forEach {
                    days.editSet(it.id, SetEdit.Weight(40.0))
                    days.setDone(it.id, true)
                }
            days.finish(date)
        }

    @Test
    fun `More opens Help with the user guide and About with the version, and Back returns to More`() =
        runPhoneUiTest {
            app()
            tab("More")
            screenshot("app-more-help-about")
            open("Help")
            waitFor("During a workout")
            assertFalse(toolbarShown())
            screenshot("app-help")
            pressBack()
            open("About")
            waitFor("Version 1.0.0")
            waitFor("Source code")
            screenshot("app-about")
            pressBack()
            waitFor("Backup and data")
            assertTrue(toolbarShown())
        }

    @Test
    fun `More opens History, a past workout opens its summary, and a picked calendar day opens on Today with Open day`() {
        loggedDay(today.minus(2, DateTimeUnit.DAY))
        loggedDay(today.minus(1, DateTimeUnit.DAY))
        runPhoneUiTest {
            app()
            tab("More")
            open("History")
            waitFor("Past workouts")
            assertFalse(toolbarShown())
            screenshot("app-history")
            onAllNodesWithText("Monday — upper").onFirst().performClick()
            waitFor("Records")
            screenshot("app-summary-from-history")
            pressBack()
            waitFor("Past workouts")
            onNodeWithTag(calendarTag(today.minus(2, DateTimeUnit.DAY))).performClick()
            waitForIdle()
            // Still History: the day shows under the calendar.
            assertFalse(toolbarShown())
            onNode(hasScrollAction()).performScrollToNode(hasText("Open day"))
            screenshot("app-history-day-picked")
            open("Open day")
            waitFor("Completed")
            onNodeWithText("Sat, 7 Nov").assertExists()
            assertTrue(toolbarShown())
        }
    }

    @Test
    fun `Finish in the focus view opens the summary, and Back from it shows Today with View summary`() {
        runBlocking {
            days.applyRoutine(today, routine("Monday — upper"), replace = false)
            days.start(today)
            blocks().first().workingSets.forEach { days.setDone(it.id, true) }
        }
        runPhoneUiTest {
            app()
            open("Resume workout")
            waitFor("Complete set")
            onNodeWithContentDescription("More options").performClick()
            open("Finish workout")
            waitFor("Records")
            assertFalse(toolbarShown())
            screenshot("app-summary-after-finish")
            open("Done")
            waitFor("View summary")
            screenshot("app-today-completed")
        }
    }

    @Test
    fun `the exercise page shows a built-in exercise's history and chart`() {
        loggedDay(today.minus(7, DateTimeUnit.DAY))
        loggedDay(today.minus(2, DateTimeUnit.DAY))
        runPhoneUiTest {
            app()
            tab("Train")
            open("Exercises")
            onNode(hasSetTextAction()).performTextInput("bench")
            open("Bench press")
            onNode(hasScrollAction()).performScrollToNode(hasText("History"))
            waitForIdle()
            screenshot("app-exercise-page-history")
        }
    }

    @Test
    fun `More imports a pack through the real importer, the routine shows on Train, and importing it again asks before replacing`() =
        runPhoneUiTest {
            val pack =
                FakeArchives.zip(
                    Files10.exercise("goblet-squat", "Goblet squat"),
                    Files10.routine("home-a", "Home A", "goblet-squat"),
                )
            app()
            tab("More")
            open("Import content")
            waitFor("Choose file")
            assertFalse(toolbarShown())
            documents.next = OpenedDocument.File("home.zip", pack)
            open("Choose file")
            waitFor("Import 2 items")
            screenshot("app-import-preview")
            open("Import 2 items")
            waitFor("Imported 2 items")
            pressBack()
            tab("Train")
            waitFor("Home A")
            screenshot("app-train-imported")

            tab("More")
            open("Import content")
            documents.next = OpenedDocument.File("home.zip", pack)
            open("Choose file")
            waitFor("Replaces Home A")
            open("Import 2 items")
            waitFor("Replace content?")
            open("Replace")
            waitFor("Imported 2 items")
            assertEquals(1, runBlocking { koin.get<RoutineRepository>().routines.first() }.count { it.name == "Home A" })
        }

    @Test
    fun `Backup and data exports a backup, wipes everything and restores it`() {
        loggedDay(today)
        runPhoneUiTest {
            app()
            tab("More")
            open("Backup and data")
            waitFor("Export backup")
            assertFalse(toolbarShown())
            open("Export backup")
            waitFor("Backup saved")
            val backup = documents.saved.single()
            assertEquals("fit-backup-2026-11-09.zip", backup.first)

            open("Wipe everything")
            waitFor("Wipe everything?")
            open("Wipe")
            waitFor("Everything wiped")
            assertEquals(null, runBlocking { days.day(today).first() })
            // The built-in content is back.
            assertTrue(runBlocking { koin.get<RoutineRepository>().routines.first() }.isNotEmpty())

            documents.next = OpenedDocument.File(backup.first, backup.second)
            open("Restore backup")
            waitFor("Restore this backup?")
            screenshot("app-restore-confirm")
            open("Restore")
            waitFor("Backup restored")
            assertTrue(runBlocking { days.day(today).first() }!!.blocks.isNotEmpty())
            pressBack()
            tab("Today")
            waitFor("Completed")
        }
    }

    @Test
    fun `an Undo offer on Today doesn't come back after a wipe, so nothing of the old data is put back`() {
        loggedDay(today)
        runPhoneUiTest {
            app()
            waitFor("Completed")
            onNodeWithContentDescription("Workout options").performClick()
            waitForIdle()
            open("Delete this day")
            waitFor("Undo")

            // Today stays under More with its offer; the wipe happens over it.
            tab("More")
            open("Backup and data")
            open("Wipe everything")
            waitFor("Wipe everything?")
            open("Wipe")
            waitFor("Everything wiped")
            pressBack()
            tab("Today")
            waitFor("Today")
            waitForIdle()
            assertTrue(onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty())
            assertEquals(null, runBlocking { days.day(today).first() })
            assertTrue(
                runBlocking {
                    koin
                        .get<AppDatabase>()
                        .dayDao()
                        .byDay(today.toString())
                        .first()
                } == null,
            )
        }
    }
}
