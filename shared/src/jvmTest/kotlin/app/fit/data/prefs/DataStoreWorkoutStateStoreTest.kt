// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.prefs

import app.fit.day.BlockId
import app.fit.day.SetId
import app.fit.settings.DataStoreSettingsRepository
import app.fit.settings.Settings
import app.fit.workout.Hold
import app.fit.workout.HoldRun
import app.fit.workout.Rest
import app.fit.workout.WorkoutSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DataStoreWorkoutStateStoreTest {
    private val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb")
    private val dataStore = createSettingsDataStore { file.toString() }
    private val store = DataStoreWorkoutStateStore(dataStore)
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private val day = LocalDate(2026, 11, 9)

    @Test
    fun `nothing stored loads as nothing`() =
        runTest {
            assertNull(store.load())
        }

    @Test
    fun `a rest with the set that started it and a running hold come back as they were`() =
        runTest {
            val snapshot =
                WorkoutSnapshot(
                    date = day,
                    blockId = BlockId(3),
                    rest = Rest(BlockId(2), SetId(5), endsAt = now + 90.seconds, maxAt = now + 120.seconds, rang = true),
                    hold = Hold(SetId(7), BlockId(3), 40.seconds, now, HoldRun.Running(now + 40.seconds)),
                )
            store.save(snapshot)
            assertEquals(snapshot, store.load())
        }

    @Test
    fun `a paused hold and a rest without a max come back, and clearing them removes them`() =
        runTest {
            val snapshot =
                WorkoutSnapshot(
                    date = day,
                    rest = Rest(BlockId(2), SetId(5), endsAt = now + 90.seconds),
                    hold = Hold(SetId(7), BlockId(3), 40.seconds, now, HoldRun.Paused(12.seconds)),
                )
            store.save(snapshot)
            assertEquals(snapshot, store.load())
            store.save(WorkoutSnapshot(date = day))
            assertEquals(WorkoutSnapshot(date = day), store.load())
            store.save(WorkoutSnapshot())
            assertNull(store.load())
        }

    @Test
    fun `the snapshot shares the settings file without touching the settings`() =
        runTest {
            val settings = DataStoreSettingsRepository(dataStore)
            settings.setAlarm(false)
            store.save(WorkoutSnapshot(date = day, rest = Rest(BlockId(2), SetId(5), endsAt = now)))
            store.save(WorkoutSnapshot())
            assertEquals(Settings(alarm = false), settings.settings.first())
        }
}
