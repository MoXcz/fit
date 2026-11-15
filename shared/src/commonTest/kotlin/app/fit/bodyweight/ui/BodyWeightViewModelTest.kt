// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight.ui

import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.FakeBodyWeightRepository
import app.fit.core.WeightUnit
import app.fit.day.FixedClock
import app.fit.settings.FakeSettingsRepository
import app.fit.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BodyWeightViewModelTest {
    private val today = LocalDate(2026, 11, 10)
    private val clock = FixedClock(Instant.parse("2026-11-10T08:00:00Z"))
    private val nov3 = BodyWeight(LocalDate(2026, 11, 3), 180.0, WeightUnit.Lb)
    private val nov6 = BodyWeight(LocalDate(2026, 11, 6), 82.0, WeightUnit.Kg)
    private val nov8 = BodyWeight(LocalDate(2026, 11, 8), 81.5, WeightUnit.Kg)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        repo: FakeBodyWeightRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = BodyWeightViewModel(repo, settings, clock, TimeZone.UTC).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `logging today starts in the unit last used, and saves one reading for the day`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov6, nov8))
            val vm = viewModel(repo)
            vm.onAction(BodyWeightAction.LogToday)
            assertEquals(BodyWeightEdit(today, "", WeightUnit.Kg), vm.uiState.value.edit)
            vm.onAction(BodyWeightAction.SetText("81,2"))
            vm.onAction(BodyWeightAction.Save)
            assertNull(vm.uiState.value.edit)
            assertEquals(
                BodyWeight(today, 81.2, WeightUnit.Kg),
                vm.uiState.value.readings
                    .first(),
            )
            // Logging again edits the same day's reading.
            vm.onAction(BodyWeightAction.LogToday)
            assertEquals(
                "81.2",
                vm.uiState.value.edit
                    ?.text,
            )
        }

    @Test
    fun `the first reading takes the settings' unit`() =
        runTest {
            val settings = FakeSettingsRepository(Settings(weightUnit = WeightUnit.Lb))
            val vm = viewModel(FakeBodyWeightRepository(), settings)
            vm.onAction(BodyWeightAction.LogToday)
            assertEquals(
                WeightUnit.Lb,
                vm.uiState.value.edit
                    ?.unit,
            )
        }

    @Test
    fun `editing a reading to empty deletes it, with Undo`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov6, nov8))
            val vm = viewModel(repo)
            vm.onAction(BodyWeightAction.Edit(nov8.date))
            vm.onAction(BodyWeightAction.SetText(""))
            vm.onAction(BodyWeightAction.Save)
            assertEquals(listOf(nov6), repo.readings.first())
            assertEquals(BodyWeightUndo.Deleted(nov8), vm.uiState.value.undo)
            vm.onAction(BodyWeightAction.Undo)
            assertEquals(listOf(nov8, nov6), repo.readings.first())
        }

    @Test
    fun `text that isn't a weight, or one above the cap, keeps the reading and the dialog open`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov8))
            val vm = viewModel(repo)
            vm.onAction(BodyWeightAction.Edit(nov8.date))
            listOf("8l", "1200").forEach { text ->
                vm.onAction(BodyWeightAction.SetText(text))
                assertTrue(
                    vm.uiState.value.edit!!
                        .invalid,
                )
                vm.onAction(BodyWeightAction.Save)
                assertNotNull(vm.uiState.value.edit)
                assertEquals(listOf(nov8), repo.readings.first())
            }
            assertNull(vm.uiState.value.undo)
        }

    @Test
    fun `replacing a reading offers the old one back`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov8))
            val vm = viewModel(repo)
            vm.onAction(BodyWeightAction.Edit(nov8.date))
            vm.onAction(BodyWeightAction.SetText("80"))
            vm.onAction(BodyWeightAction.Save)
            assertEquals(
                80.0,
                repo.readings
                    .first()
                    .single()
                    .weight,
            )
            assertEquals(BodyWeightUndo.Replaced(nov8), vm.uiState.value.undo)
            vm.onAction(BodyWeightAction.Undo)
            assertEquals(listOf(nov8), repo.readings.first())
        }

    @Test
    fun `a deleted reading comes back with Undo`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov6, nov8))
            val vm = viewModel(repo)
            vm.onAction(BodyWeightAction.Delete(nov6.date))
            assertEquals(BodyWeightUndo.Deleted(nov6), vm.uiState.value.undo)
            assertEquals(listOf(nov8), vm.uiState.value.readings)
            vm.onAction(BodyWeightAction.Undo)
            assertNull(vm.uiState.value.undo)
            assertEquals(listOf(nov8, nov6), vm.uiState.value.readings)
        }

    @Test
    fun `the chart is in the latest reading's unit, and readings in another unit stay off it`() =
        runTest {
            val vm = viewModel(FakeBodyWeightRepository(listOf(nov3, nov6, nov8)))
            val chart = vm.uiState.value.chart!!
            assertEquals(WeightUnit.Kg, chart.unit)
            assertEquals(listOf(82.0, 81.5), chart.points.map { it.value })
        }
}
