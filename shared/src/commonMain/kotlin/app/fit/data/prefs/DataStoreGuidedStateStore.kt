// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPhase
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedSnapshot
import app.fit.guided.GuidedSource
import app.fit.guided.GuidedStateStore
import app.fit.guided.StepClock
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// The guided session next to the workout's, in the settings file, as epoch millis. A value written by
// a newer version that this one can't read drops the session instead of crashing.
class DataStoreGuidedStateStore(
    private val dataStore: DataStore<Preferences>,
) : GuidedStateStore {
    override suspend fun load(): GuidedSnapshot? {
        val prefs = dataStore.data.first()
        val source =
            when {
                prefs[Keys.planId] != null -> GuidedSource.Plan(GuidedPlanId(prefs[Keys.planId]!!))
                prefs[Keys.date] != null -> GuidedSource.Warmup(LocalDate.parse(prefs[Keys.date]!!))
                else -> return null
            }
        val phase = GuidedPhase.entries.firstOrNull { it.name == prefs[Keys.phase] } ?: return null
        val clock =
            when {
                prefs[Keys.endsAt] != null -> StepClock.Running(Instant.fromEpochMilliseconds(prefs[Keys.endsAt]!!))
                prefs[Keys.paused] != null -> StepClock.Paused(prefs[Keys.paused]!!.milliseconds)
                else -> StepClock.Waiting
            }
        return GuidedSnapshot(
            source = source,
            title = prefs[Keys.title].orEmpty(),
            kind = GuidedKind.entries.firstOrNull { it.name == prefs[Keys.kind] },
            startedAt = Instant.fromEpochMilliseconds(prefs[Keys.startedAt] ?: return null),
            index = prefs[Keys.index] ?: 0,
            phase = phase,
            clock = clock,
            done =
                prefs[Keys.done]
                    .orEmpty()
                    .split(',')
                    .mapNotNull { it.toIntOrNull() }
                    .toSet(),
            outcome = GuidedOutcome.entries.firstOrNull { it.name == prefs[Keys.outcome] },
            endedAt = prefs[Keys.endedAt]?.let(Instant::fromEpochMilliseconds),
            idleFrom = prefs[Keys.idleFrom]?.let(Instant::fromEpochMilliseconds),
            pausedAt = prefs[Keys.pausedAt]?.let(Instant::fromEpochMilliseconds),
            pausedFor = (prefs[Keys.pausedFor] ?: 0L).milliseconds,
        )
    }

    override suspend fun save(snapshot: GuidedSnapshot?) {
        dataStore.edit { prefs ->
            Keys.all.forEach { prefs.remove(it) }
            snapshot ?: return@edit
            when (val source = snapshot.source) {
                is GuidedSource.Plan -> prefs[Keys.planId] = source.id.value
                is GuidedSource.Warmup -> prefs[Keys.date] = source.date.toString()
            }
            prefs[Keys.title] = snapshot.title
            snapshot.kind?.let { prefs[Keys.kind] = it.name }
            prefs[Keys.startedAt] = snapshot.startedAt.toEpochMilliseconds()
            prefs[Keys.index] = snapshot.index
            prefs[Keys.phase] = snapshot.phase.name
            when (val clock = snapshot.clock) {
                is StepClock.Running -> prefs[Keys.endsAt] = clock.endsAt.toEpochMilliseconds()
                is StepClock.Paused -> prefs[Keys.paused] = clock.remaining.inWholeMilliseconds
                StepClock.Waiting -> Unit
            }
            prefs[Keys.done] = snapshot.done.sorted().joinToString(",")
            snapshot.outcome?.let { prefs[Keys.outcome] = it.name }
            snapshot.endedAt?.let { prefs[Keys.endedAt] = it.toEpochMilliseconds() }
            snapshot.idleFrom?.let { prefs[Keys.idleFrom] = it.toEpochMilliseconds() }
            snapshot.pausedAt?.let { prefs[Keys.pausedAt] = it.toEpochMilliseconds() }
            prefs[Keys.pausedFor] = snapshot.pausedFor.inWholeMilliseconds
        }
    }

    private object Keys {
        val planId = longPreferencesKey("guided_plan_id")
        val date = stringPreferencesKey("guided_warmup_date")
        val title = stringPreferencesKey("guided_title")
        val kind = stringPreferencesKey("guided_kind")
        val startedAt = longPreferencesKey("guided_started_at_ms")
        val index = intPreferencesKey("guided_index")
        val phase = stringPreferencesKey("guided_phase")
        val endsAt = longPreferencesKey("guided_ends_at_ms")
        val paused = longPreferencesKey("guided_paused_remaining_ms")
        val done = stringPreferencesKey("guided_done")
        val outcome = stringPreferencesKey("guided_outcome")
        val endedAt = longPreferencesKey("guided_ended_at_ms")
        val idleFrom = longPreferencesKey("guided_idle_from_ms")
        val pausedAt = longPreferencesKey("guided_paused_at_ms")
        val pausedFor = longPreferencesKey("guided_paused_for_ms")

        val all: List<Preferences.Key<*>> =
            listOf(
                planId,
                date,
                title,
                kind,
                startedAt,
                index,
                phase,
                endsAt,
                paused,
                done,
                outcome,
                endedAt,
                idleFrom,
                pausedAt,
                pausedFor,
            )
    }
}
