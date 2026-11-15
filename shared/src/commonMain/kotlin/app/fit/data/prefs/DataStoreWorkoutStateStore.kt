// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fit.day.BlockId
import app.fit.day.SetId
import app.fit.workout.Hold
import app.fit.workout.HoldRun
import app.fit.workout.Rest
import app.fit.workout.WorkoutSnapshot
import app.fit.workout.WorkoutStateStore
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

// Keeps the snapshot next to the settings, in the same DataStore file, as epoch millis.
class DataStoreWorkoutStateStore(
    private val dataStore: DataStore<Preferences>,
) : WorkoutStateStore {
    override suspend fun load(): WorkoutSnapshot? {
        val prefs = dataStore.data.first()
        val date = prefs[Keys.date]?.let { LocalDate.parse(it) } ?: return null
        return WorkoutSnapshot(
            date = date,
            blockId = prefs[Keys.blockId]?.let(::BlockId),
            rest = prefs.rest(),
            hold = prefs.hold(),
        )
    }

    private fun Preferences.rest(): Rest? {
        val blockId = this[Keys.restBlockId] ?: return null
        val setId = this[Keys.restSetId] ?: return null
        val endsAt = this[Keys.restEndsAtMillis] ?: return null
        return Rest(
            blockId = BlockId(blockId),
            setId = SetId(setId),
            endsAt = Instant.fromEpochMilliseconds(endsAt),
            maxAt = this[Keys.restMaxAtMillis]?.let(Instant::fromEpochMilliseconds),
            rang = this[Keys.restRang] ?: false,
        )
    }

    private fun Preferences.hold(): Hold? {
        val setId = this[Keys.holdSetId] ?: return null
        val blockId = this[Keys.holdBlockId] ?: return null
        val length = this[Keys.holdLengthMillis] ?: return null
        val startedAt = this[Keys.holdStartedAtMillis] ?: return null
        val endsAt = this[Keys.holdEndsAtMillis]
        val paused = this[Keys.holdPausedRemainingMillis]
        val run =
            when {
                endsAt != null -> HoldRun.Running(Instant.fromEpochMilliseconds(endsAt))
                paused != null -> HoldRun.Paused(paused.milliseconds)
                else -> return null
            }
        return Hold(SetId(setId), BlockId(blockId), length.milliseconds, Instant.fromEpochMilliseconds(startedAt), run)
    }

    override suspend fun save(snapshot: WorkoutSnapshot) {
        dataStore.edit { prefs ->
            Keys.all.forEach { prefs.remove(it) }
            val date = snapshot.date ?: return@edit
            prefs[Keys.date] = date.toString()
            prefs.putOrSkip(Keys.blockId, snapshot.blockId?.value)
            snapshot.rest?.let { rest ->
                prefs[Keys.restBlockId] = rest.blockId.value
                prefs[Keys.restSetId] = rest.setId.value
                prefs[Keys.restEndsAtMillis] = rest.endsAt.toEpochMilliseconds()
                prefs.putOrSkip(Keys.restMaxAtMillis, rest.maxAt?.toEpochMilliseconds())
                prefs[Keys.restRang] = rest.rang
            }
            snapshot.hold?.let { hold ->
                prefs[Keys.holdSetId] = hold.setId.value
                prefs[Keys.holdBlockId] = hold.blockId.value
                prefs[Keys.holdLengthMillis] = hold.length.inWholeMilliseconds
                prefs[Keys.holdStartedAtMillis] = hold.startedAt.toEpochMilliseconds()
                when (val run = hold.run) {
                    is HoldRun.Running -> prefs[Keys.holdEndsAtMillis] = run.endsAt.toEpochMilliseconds()
                    is HoldRun.Paused -> prefs[Keys.holdPausedRemainingMillis] = run.remaining.inWholeMilliseconds
                }
            }
        }
    }

    private fun MutablePreferences.putOrSkip(
        key: Preferences.Key<Long>,
        value: Long?,
    ) {
        if (value != null) this[key] = value
    }

    private object Keys {
        val date = stringPreferencesKey("workout_date")
        val blockId = longPreferencesKey("workout_block_id")
        val restBlockId = longPreferencesKey("workout_rest_block_id")
        val restSetId = longPreferencesKey("workout_rest_set_id")
        val restEndsAtMillis = longPreferencesKey("workout_rest_ends_at_ms")
        val restMaxAtMillis = longPreferencesKey("workout_rest_max_at_ms")
        val restRang = booleanPreferencesKey("workout_rest_rang")
        val holdSetId = longPreferencesKey("workout_hold_set_id")
        val holdBlockId = longPreferencesKey("workout_hold_block_id")
        val holdLengthMillis = longPreferencesKey("workout_hold_length_ms")
        val holdStartedAtMillis = longPreferencesKey("workout_hold_started_at_ms")
        val holdEndsAtMillis = longPreferencesKey("workout_hold_ends_at_ms")
        val holdPausedRemainingMillis = longPreferencesKey("workout_hold_paused_remaining_ms")

        val all: List<Preferences.Key<*>> =
            listOf(
                date,
                blockId,
                restBlockId,
                restSetId,
                restEndsAtMillis,
                restMaxAtMillis,
                restRang,
                holdSetId,
                holdBlockId,
                holdLengthMillis,
                holdStartedAtMillis,
                holdEndsAtMillis,
                holdPausedRemainingMillis,
            )
    }
}
