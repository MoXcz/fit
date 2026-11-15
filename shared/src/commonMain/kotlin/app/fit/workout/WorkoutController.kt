// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.core.SessionTimers
import app.fit.core.TimerOwner
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.day.today
import app.fit.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The one owner of the running workout: its day, the rest, the hold and the focus view's position.
 * The UI, the foreground service and the wakeup only read [state] or call these functions.
 *
 * Rest and hold start only on today's unfinished workout, after working sets; on any other day a
 * tick just records the set. A rest or hold that's running finishes normally, past midnight too.
 * Rest belongs to the session: moving between exercises never touches it. One hold at a time.
 * Only one session times at once ([SessionTimers]): a guided session that starts or resumes ends the
 * rest and pauses the hold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutController(
    private val days: DayRepository,
    private val settings: SettingsRepository,
    private val store: WorkoutStateStore,
    private val wakeups: WakeupScheduler,
    private val alerts: WorkoutAlerts,
    private val timers: SessionTimers,
    private val clock: Clock,
    private val zone: TimeZone,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(WorkoutState(now = clock.now()))
    private var alarm = true

    // The snapshot's day, kept fresh for the notification and to drop timers it no longer has.
    private var day: WorkoutDay? = null
    private var saved: WorkoutSnapshot? = null

    // Set and cleared under the mutex, so a rest can't be left without one.
    private var ticker: Job? = null
    private var idleWatch: Job? = null
    private var idleWatchAt: Instant? = null

    val state: StateFlow<WorkoutState> = mutableState.asStateFlow()

    init {
        scope.launch {
            // A failed restore must neither hang waiters nor stop the collectors: start fresh.
            try {
                mutex.withLock { restore() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                restored.complete(Unit)
            }
            launch {
                retrying {
                    timers.owner.collect { owner ->
                        if (owner == TimerOwner.Guided) mutex.withLock { giveWay() }
                    }
                }
            }
            launch {
                retrying {
                    settings.settings.map { it.alarm }.distinctUntilChanged().collect { on ->
                        mutex.withLock {
                            alarm = on
                            publish(current())
                        }
                    }
                }
            }
            retrying {
                mutableState
                    .map { it.snapshot.date }
                    .distinctUntilChanged()
                    .flatMapLatest { date -> if (date == null) flowOf(null) else days.day(date) }
                    .collect { changed ->
                        mutex.withLock {
                            day = changed
                            publish(reconciled(current(), changed))
                        }
                    }
            }
        }
    }

    /**
     * Remembers the exercise open in the focus view, for Resume; only today's workout has one. A rest
     * or hold still running on an earlier day keeps that day until it ends.
     */
    suspend fun open(
        date: LocalDate,
        blockId: BlockId,
    ) = command {
        if (date != clock.today(zone)) return@command
        if (current().date != date) {
            expireLocked()
            // A rest that rang is over; anything else still running keeps its day.
            val settled = current().let { if (it.rest?.rang == true) it.copy(rest = null) else it }
            if (settled.timing) {
                publish(settled)
                return@command
            }
            publish(WorkoutSnapshot(date = date))
        }
        publish(current().copy(blockId = blockId))
    }

    /** The exercise last open on [date], if any. */
    suspend fun position(date: LocalDate): BlockId? {
        restored.await()
        return current().takeIf { it.date == date }?.blockId
    }

    /**
     * Ticks or unticks a set. On today's unfinished workout a working set's tick starts the rest
     * (none for 0 s), unless a hold runs; ticking the held set completes the hold. Unticking the set
     * that started the rest stops it. A tick on a set already done changes nothing.
     */
    suspend fun setDone(
        blockId: BlockId,
        setId: SetId,
        done: Boolean,
    ) = command {
        // A hold that already ran out is logged first, with its end time.
        expireLocked()
        val day = days.dayOfBlock(blockId).first() ?: return@command
        val block = day.blocks.firstOrNull { it.id == blockId } ?: return@command
        val set = block.sets.firstOrNull { it.id == setId } ?: return@command
        if (set.done == done) return@command
        val hold = current().hold
        if (done && hold?.setId == setId) {
            completeHold(hold, day, block, set)
            return@command
        }
        days.setDone(setId, done)
        val snapshot = current()
        when {
            !done -> {
                if (snapshot.rest?.setId == setId) {
                    clearAlert()
                    publish(snapshot.copy(rest = null))
                }
            }

            !runsTimers(day) || set.warmup || snapshot.hold != null -> {
                Unit
            }

            else -> {
                clearAlert()
                publish(onDay(current(), day.date).copy(rest = WorkoutEngine.rest(block, setId, clock.now())))
            }
        }
    }

    /** Starts the countdown of a timed set; any rest is over. Refused while another hold runs. */
    suspend fun startHold(
        blockId: BlockId,
        setId: SetId,
        length: Duration,
    ) = command {
        val day = days.dayOfBlock(blockId).first() ?: return@command
        if (!runsTimers(day) || length <= Duration.ZERO || current().hold != null) return@command
        if (day.blocks.none { block -> block.id == blockId && block.sets.any { it.id == setId && !it.done } }) return@command
        expireLocked()
        clearAlert()
        val hold = WorkoutEngine.startHold(setId, blockId, length, clock.now())
        publish(onDay(current(), day.date).copy(rest = null, hold = hold))
    }

    suspend fun pauseHold() = changeHold { WorkoutEngine.pause(it, clock.now()) }

    suspend fun resumeHold() = changeHold { WorkoutEngine.resume(it, clock.now()) }

    /** Stops the countdown and logs nothing. */
    suspend fun stopHold() = changeHold { null }

    /** Complete set during a hold: logs the time actually held, then rests. */
    suspend fun completeHold() =
        command {
            expireLocked()
            val hold = current().hold ?: return@command
            val day = days.dayOfBlock(hold.blockId).first()
            val block = day?.blocks?.firstOrNull { it.id == hold.blockId }
            val set = block?.sets?.firstOrNull { it.id == hold.setId }
            // Its set was logged some other way (the warm-up runner): nothing to complete, no rest.
            if (set == null || set.done) {
                publish(current().copy(hold = null))
                return@command
            }
            completeHold(hold, day, block, set)
        }

    /** +15 s on the rest. */
    suspend fun addRest() =
        command {
            val snapshot = current()
            val rest = snapshot.rest ?: return@command
            val longer = WorkoutEngine.addRest(rest, clock.now())
            if (rest.rang && !longer.rang) clearAlert()
            publish(snapshot.copy(rest = longer))
        }

    suspend fun skipRest() =
        command {
            val snapshot = current()
            if (snapshot.rest == null) return@command
            clearAlert()
            publish(snapshot.copy(rest = null))
        }

    /** Forgets the workout, its rest and its hold, logging nothing: its data is being wiped or replaced. */
    suspend fun reset() =
        command {
            clearAlert()
            day = null
            publish(WorkoutSnapshot())
        }

    /**
     * Ends whatever is due: logs a hold that ran out (with its end time) and starts its rest, rings
     * a rest that reached 0, stops one past the top of its range. An end noticed more than a minute
     * late is shown without ringing. Idempotent: the ticker, the wakeup, resume and restore can all
     * call it, and only the first call after an end does anything.
     */
    suspend fun expireIfDue(): Boolean {
        restored.await()
        return mutex.withLock { expireLocked() || refreshIdle() }
    }

    // A guided session took the timers: a rest ends (it can't pause), a running hold pauses.
    private suspend fun giveWay() {
        expireLocked()
        val snapshot = current()
        if (!snapshot.ticks) return
        if (snapshot.rest != null) clearAlert()
        publish(snapshot.copy(rest = null, hold = snapshot.hold?.let { WorkoutEngine.pause(it, clock.now()) }))
    }

    // The workout may have gone idle without any other change.
    private suspend fun refreshIdle(): Boolean {
        if (running(current()) == mutableState.value.running) return false
        publish(current())
        return true
    }

    private fun running(snapshot: WorkoutSnapshot): Boolean {
        val day = day?.takeIf { it.date == snapshot.date } ?: return false
        val idleAt = WorkoutEngine.idleAt(day) ?: return false
        return day.started && clock.now() < idleAt
    }

    private suspend fun expireLocked(): Boolean {
        val (snapshot, ring) = expired(current(), clock.now())
        if (snapshot == current()) return false
        publish(snapshot)
        ring?.let { sound(it) }
        return true
    }

    // What's due in [from] at [now], and the one ring it calls for (the latest end, if recent).
    private suspend fun expired(
        from: WorkoutSnapshot,
        now: Instant,
    ): Pair<WorkoutSnapshot, (() -> Unit)?> {
        var snapshot = from
        var ring: (() -> Unit)? = null
        val hold = snapshot.hold
        if (hold != null && WorkoutEngine.holdDue(hold, now)) {
            val endsAt = (hold.run as HoldRun.Running).endsAt
            // It ran its whole length: pauses moved the end on.
            days.logHold(hold.setId, hold.length, hold.startedAt, endsAt)
            val day = days.dayOfBlock(hold.blockId).first()
            val block = day?.blocks?.firstOrNull { it.id == hold.blockId }
            val set = block?.sets?.firstOrNull { it.id == hold.setId }
            val rest = if (day != null && block != null && set != null) restAfter(day, block, set, endsAt) else null
            snapshot = snapshot.copy(hold = null, rest = rest)
            val name =
                block
                    ?.exercise
                    ?.fields
                    ?.name
                    .orEmpty()
            ring = if (recent(endsAt, now)) ({ alerts.holdEnded(name) }) else null
        }
        val rest = snapshot.rest
        if (rest != null) {
            var after: Rest? = rest
            if (WorkoutEngine.ringDue(rest, now)) {
                after = rest.copy(rang = true)
                val next = nextFor(snapshot)?.exercise?.fields?.name
                ring = if (recent(rest.endsAt, now)) ({ alerts.restEnded(next) }) else null
            }
            // A rest pushed past its max by +15 s rings and stops at once.
            if (WorkoutEngine.stopDue(after, now)) after = null
            // A rest still counting over when the workout goes idle was forgotten: it's over.
            val idleAt = day?.takeIf { it.date == snapshot.date }?.let(WorkoutEngine::idleAt)
            if (after?.rang == true && idleAt != null && now >= idleAt) after = null
            snapshot = snapshot.copy(rest = after)
        }
        return snapshot to ring
    }

    // Expiry is applied before the first publish, so a rest that ended while the process was dead
    // never shows, or starts the service, on a cold start.
    private suspend fun restore() {
        alarm = settings.settings.first().alarm
        val stored = store.load() ?: WorkoutSnapshot()
        saved = stored
        day = stored.date?.let { days.day(it).first() }
        val (snapshot, ring) = expired(reconciled(stored, day), clock.now())
        publish(snapshot)
        ring?.let { sound(it) }
    }

    private suspend fun completeHold(
        hold: Hold,
        day: WorkoutDay,
        block: Block,
        set: SetEntry,
    ) {
        val now = clock.now()
        days.logHold(hold.setId, WorkoutEngine.held(hold, now).toWholeSeconds(), hold.startedAt, now)
        clearAlert()
        publish(current().copy(hold = null, rest = restAfter(day, block, set, now)))
    }

    // Only working sets on today's unfinished workout rest.
    private fun restAfter(
        day: WorkoutDay,
        block: Block,
        set: SetEntry,
        at: Instant,
    ): Rest? = if (runsTimers(day) && set.working) WorkoutEngine.rest(block, set.id, at) else null

    // A hold already past its end is ended first, so it can't become Paused(0).
    private suspend fun changeHold(change: (Hold) -> Hold?) =
        command {
            expireLocked()
            val snapshot = current()
            val hold = snapshot.hold ?: return@command
            publish(snapshot.copy(hold = change(hold)))
        }

    private fun runsTimers(day: WorkoutDay): Boolean = day.date == clock.today(zone) && !day.finished

    private fun recent(
        end: Instant,
        now: Instant,
    ): Boolean = now - end <= LATE_RING

    // A new rest or hold supersedes the old rest, so starting one on a new day starts the day clean.
    private fun onDay(
        snapshot: WorkoutSnapshot,
        date: LocalDate,
    ): WorkoutSnapshot = if (snapshot.date == date) snapshot else WorkoutSnapshot(date = date)

    // A deleted or finished day has no timers, and a hold goes with its set. A paused hold also goes once
    // its set is done another way (the warm-up runner logged it while the hold gave way); a running one
    // whose set is done is a replay, which expiry ends with its rest.
    private fun reconciled(
        snapshot: WorkoutSnapshot,
        day: WorkoutDay?,
    ): WorkoutSnapshot {
        if (snapshot.date == null || !snapshot.timing) return snapshot
        if (day == null || day.finished) return snapshot.copy(rest = null, hold = null)
        val hold = snapshot.hold
        val set = hold?.let { day.blocks.flatMap { it.sets }.firstOrNull { it.id == hold.setId } }
        if (hold != null && (set == null || (set.done && hold.run is HoldRun.Paused))) return snapshot.copy(hold = null)
        return snapshot
    }

    private fun nextFor(snapshot: WorkoutSnapshot): Block? {
        val day = day?.takeIf { it.date == snapshot.date } ?: return null
        val from = snapshot.rest?.blockId ?: snapshot.hold?.blockId ?: snapshot.blockId
        return WorkoutEngine.nextBlock(day, from, skip = snapshot.hold?.setId)
    }

    private suspend fun <T> command(block: suspend () -> T): T {
        restored.await()
        return mutex.withLock { block() }
    }

    private fun current(): WorkoutSnapshot = mutableState.value.snapshot

    private suspend fun publish(snapshot: WorkoutSnapshot) {
        if (snapshot.ticks && !mutableState.value.snapshot.ticks) timers.claim(TimerOwner.Workout)
        val day = day?.takeIf { it.date == snapshot.date }
        val holding = snapshot.hold?.let { hold -> day?.blocks?.firstOrNull { it.id == hold.blockId } }
        mutableState.value =
            WorkoutState(
                snapshot = snapshot,
                now = clock.now(),
                next = nextFor(snapshot)?.exercise?.fields?.name,
                holding = holding?.exercise?.fields?.name,
                running = running(snapshot),
                startedAt = day?.startedAt,
                name = day?.name.orEmpty(),
            )
        // With the alarm off no rest or hold end is scheduled: the ticker and the next resume still end
        // them. The idle deadline only stops the service, never rings, so it's always scheduled.
        val now = clock.now()
        val idleAt = day?.let(WorkoutEngine::idleAt)?.takeIf { it > now && day.started }
        val wakeup = listOfNotNull(WorkoutEngine.nextWakeup(snapshot)?.takeIf { alarm }, idleAt).minOrNull()
        if (wakeup != null) wakeups.scheduleWorkoutWakeup(wakeup) else wakeups.cancelWorkoutWakeup()
        if (snapshot.ticks) ensureTicking()
        watchIdle(idleAt)
        if (snapshot != saved) {
            // A failed save is tried again with the next change.
            survive(Unit) {
                store.save(snapshot)
                saved = snapshot
            }
        }
    }

    // The platform's alerts can fail; a missed beep must not stop the workout.
    private fun sound(ring: () -> Unit) {
        if (!alarm) return
        try {
            ring()
        } catch (_: RuntimeException) {
        }
    }

    private fun clearAlert() {
        try {
            alerts.clear()
        } catch (_: RuntimeException) {
        }
    }

    // The loop only republishes the time, on the countdown's whole seconds, and ends what's due. It
    // decides to stop under the mutex, where publish starts it, so a rest is never left frozen.
    private fun ensureTicking() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (isActive) {
                    val snapshot =
                        mutex.withLock {
                            current().takeIf { it.ticks } ?: run {
                                ticker = null
                                null
                            }
                        } ?: break
                    val now = clock.now()
                    val due = WorkoutEngine.nextWakeup(snapshot)
                    if (due != null && now >= due) {
                        // A failed end (storage) is tried again on the next second.
                        if (survive(false) { expireIfDue() }) continue
                    }
                    mutableState.update { if (it.snapshot == snapshot) it.copy(now = now) else it }
                    val anchor = (snapshot.hold?.run as? HoldRun.Running)?.endsAt ?: snapshot.rest?.endsAt ?: now
                    val toNextSecond = (anchor - now).inWholeMilliseconds.mod(MILLIS_PER_SECOND)
                    delay(if (toNextSecond == 0L) MILLIS_PER_SECOND else toNextSecond)
                }
            }
    }

    // With the screen on and nothing timed there's no ticker, so a job wakes at the idle deadline; the
    // wakeup alarm covers the screen being off.
    private fun watchIdle(at: Instant?) {
        if (at == idleWatchAt) return
        idleWatch?.cancel()
        idleWatchAt = at
        idleWatch =
            at?.let {
                scope.launch {
                    delay(it - clock.now())
                    survive(false) { expireIfDue() }
                }
            }
    }

    // A collector that fails (storage) starts again a second later instead of ending.
    private suspend fun retrying(collect: suspend () -> Unit) {
        while (true) {
            if (survive(false) { collect().let { true } }) return
            delay(1.seconds)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // An end noticed later than this (the app was stopped, no alarm fired) is shown without a ring.
        val LATE_RING: Duration = 1.minutes
    }
}

// Storage can fail at any time; that must neither crash the app nor stop the timers.
private inline fun <T> survive(
    fallback: T,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

// A rest counts on screen until it's gone; a paused hold doesn't move.
private val WorkoutSnapshot.ticks: Boolean get() = rest != null || hold?.run is HoldRun.Running

// Sets store whole seconds.
private fun Duration.toWholeSeconds(): Duration = ((inWholeMilliseconds + 500) / 1_000 * 1_000).milliseconds
