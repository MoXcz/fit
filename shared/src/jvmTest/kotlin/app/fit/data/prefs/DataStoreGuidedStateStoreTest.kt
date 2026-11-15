// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.prefs

import app.fit.guided.GuidedKind
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPhase
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedSnapshot
import app.fit.guided.GuidedSource
import app.fit.guided.StepClock
import app.fit.settings.DataStoreSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DataStoreGuidedStateStoreTest {
    private val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb")
    private val dataStore = createSettingsDataStore { file.toString() }
    private val store = DataStoreGuidedStateStore(dataStore)
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    @Test
    fun `nothing stored loads as nothing`() =
        runTest {
            assertNull(store.load())
        }

    @Test
    fun `a running plan step with its done items comes back as it was`() =
        runTest {
            val snapshot =
                GuidedSnapshot(
                    source = GuidedSource.Plan(GuidedPlanId(4)),
                    title = "Wrist reset",
                    kind = GuidedKind.Recovery,
                    startedAt = now,
                    index = 3,
                    phase = GuidedPhase.Step,
                    clock = StepClock.Running(now + 30.seconds),
                    done = setOf(0, 1, 2),
                )
            store.save(snapshot)
            assertEquals(snapshot, store.load())
        }

    @Test
    fun `a paused warm-up, a finished run and clearing come back as they were`() =
        runTest {
            val paused =
                GuidedSnapshot(
                    source = GuidedSource.Warmup(LocalDate(2026, 11, 9)),
                    title = "Warm-up",
                    kind = null,
                    startedAt = now,
                    phase = GuidedPhase.Transition,
                    clock = StepClock.Paused(4.seconds),
                )
            store.save(paused)
            assertEquals(paused, store.load())
            val finished =
                paused.copy(
                    source = GuidedSource.Plan(GuidedPlanId(1)),
                    kind = GuidedKind.Recovery,
                    phase = GuidedPhase.Finished,
                    clock = StepClock.Waiting,
                    outcome = GuidedOutcome.Stopped,
                    endedAt = now + 60.seconds,
                )
            store.save(finished)
            assertEquals(finished, store.load())
            store.save(null)
            assertNull(store.load())
        }

    @Test
    fun `auto-advance is on by default and kept when turned off`() =
        runTest {
            val settings = DataStoreSettingsRepository(dataStore)
            assertEquals(true, settings.settings.first().autoAdvance)
            settings.setAutoAdvance(false)
            assertFalse(settings.settings.first().autoAdvance)
        }
}
