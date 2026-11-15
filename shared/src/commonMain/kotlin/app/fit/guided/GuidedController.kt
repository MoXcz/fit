// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.core.SessionTimers
import app.fit.core.TimerOwner
import app.fit.day.DayRepository
import app.fit.day.localDate
import app.fit.day.today
import app.fit.routines.WARMUP_SECTION
import app.fit.settings.SettingsRepository
import app.fit.workout.WakeupScheduler
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The one owner of the running guided session (a recovery session, or a day's warm-up section): its
 * place in the run and its countdown. The player, the foreground service and the wakeup only read
 * [state] or call these functions.
 *
 * One session at a time: starting another ends the current one. A plan's run is logged when it ends,
 * completed or stopped; a warm-up ticks each set as its item is done instead, and belongs to today's
 * unfinished workout. A session left still for [GuidedEngine.IDLE] ends itself. Only one session
 * times at once ([SessionTimers]): a workout rest or hold that starts pauses this one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidedController(
    private val plans: GuidedRepository,
    private val days: DayRepository,
    private val settings: SettingsRepository,
    private val store: GuidedStateStore,
    private val wakeups: WakeupScheduler,
    private val alerts: GuidedAlerts,
    private val timers: SessionTimers,
    private val clock: Clock,
    private val zone: TimeZone,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(GuidedState(now = clock.now()))
    private var alarm = true
    private var autoAdvance = true
    private var saved: GuidedSnapshot? = null
    private var ticker: Job? = null
    private var idleWatch: Job? = null
    private var idleWatchAt: Instant? = null

    // The title of a session with progress that the last start ended, until the player has said so.
    private var replaced: String? = null

    val state: StateFlow<GuidedState> = mutableState.asStateFlow()

    init {
        scope.launch {
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
                    // The alarm decides whether a wakeup is scheduled, so a change republishes.
                    settings.settings
                        .distinctUntilChanged {
                            a,
                            b,
                            ->
                            a.alarm == b.alarm && a.autoAdvance == b.autoAdvance
                        }.collect { values ->
                            mutex.withLock {
                                alarm = values.alarm
                                autoAdvance = values.autoAdvance
                                publish(current(), run())
                            }
                        }
                }
            }
            // Finishing (or deleting) the day ends its warm-up.
            launch {
                retrying {
                    mutableState
                        .map { (it.snapshot?.source as? GuidedSource.Warmup)?.date }
                        .distinctUntilChanged()
                        .flatMapLatest { date -> if (date == null) flowOf(null) else days.day(date).map { date to it } }
                        .collect { pair ->
                            val (date, day) = pair ?: return@collect
                            if (day == null || day.finished) mutex.withLock { dropWarmup(date) }
                        }
                }
            }
            // A workout rest or hold that starts pauses this session.
            retrying {
                timers.owner.collect { owner ->
                    if (owner == TimerOwner.Workout) {
                        mutex.withLock {
                            val snapshot = current() ?: return@withLock
                            if (snapshot.timing) {
                                expireLocked()
                                current()?.let { publish(GuidedEngine.pause(it, clock.now()), run()) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Starts [id]; if it's already running, nothing changes. False when there's no such plan. */
    suspend fun startPlan(id: GuidedPlanId): Boolean =
        command {
            val source = GuidedSource.Plan(id)
            if (state.value.active && current()?.source == source) return@command true
            val plan = plans.plan(id).first()?.takeIf { it.steps.isNotEmpty() } ?: return@command false
            replace()
            val run = GuidedRun.of(plan)
            publish(GuidedEngine.start(source, run, plan.kind, clock.now()), run)
            true
        }

    /**
     * Runs [date]'s warm-up section from its first item still to do; if it's already running, nothing
     * changes. False when the day has no warm-up.
     */
    suspend fun startWarmup(date: LocalDate): Boolean =
        command {
            val source = GuidedSource.Warmup(date)
            if (state.value.active && current()?.source == source) return@command true
            val run = warmupRun(date)?.takeIf { it.items.isNotEmpty() } ?: return@command false
            replace()
            val from = run.items.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: 0
            publish(GuidedEngine.start(source, run, kind = null, now = clock.now(), from = from), run)
            true
        }

    suspend fun pause() = change { snapshot, _ -> GuidedEngine.pause(snapshot, clock.now()) }

    suspend fun resume() = change { snapshot, _ -> GuidedEngine.resume(snapshot, clock.now()) }

    /** Skips the pause, or the item without logging it. */
    suspend fun next() = change { snapshot, run -> GuidedEngine.next(snapshot, run, clock.now()) }

    suspend fun previous() = change { snapshot, run -> GuidedEngine.previous(snapshot, run, clock.now()) }

    /** Done on a counted item. */
    suspend fun done() =
        command {
            expireLocked()
            val snapshot = current() ?: return@command
            val run = run() ?: return@command
            val change = GuidedEngine.done(snapshot, run, clock.now())
            handle(change.events, run)
            publish(change.snapshot, run)
        }

    /** Ends the session now; a plan's run is logged as stopped. */
    suspend fun end() = change { snapshot, _ -> GuidedEngine.end(snapshot, clock.now()) }

    /**
     * Ends [id]'s session, if it's the one running, before its plan is deleted: logged as stopped
     * like any other end, then gone.
     */
    suspend fun close(id: GuidedPlanId) =
        command {
            val snapshot = current() ?: return@command
            if (snapshot.source != GuidedSource.Plan(id)) return@command
            if (!snapshot.finished) publish(GuidedEngine.end(snapshot, clock.now()), run())
            publish(null, null)
        }

    /** Drops the session without logging it: its data is being wiped or replaced. */
    suspend fun reset() =
        command {
            replaced = null
            publish(null, null)
        }

    /** The player saw how the run ended; the session goes. */
    suspend fun dismiss() =
        command {
            if (current()?.finished == true) publish(null, null)
        }

    /** The player said which session the last start ended. */
    suspend fun replacedSeen() =
        command {
            if (replaced == null) return@command
            replaced = null
            publish(current(), run())
        }

    /**
     * Ends whatever is due: a pause runs into its item, a timed item that ran out moves on (or waits at
     * 0 with auto-advance off), and the run finishes after its last item. Rings once for the latest end,
     * if it's recent. Idempotent: the ticker, the wakeup and resume can all call it.
     */
    suspend fun expireIfDue(): Boolean {
        restored.await()
        return mutex.withLock { expireLocked() }
    }

    private suspend fun expireLocked(): Boolean {
        val snapshot = current() ?: return false
        val run = run() ?: return false
        val change = GuidedEngine.expire(snapshot, run, clock.now(), autoAdvance)
        if (change.snapshot == snapshot) return false
        handle(change.events, run)
        publish(change.snapshot, run)
        return true
    }

    // A warm-up logs each set as its item is done; a timed end beeps, unless it's long past.
    private suspend fun handle(
        events: List<GuidedEvent>,
        run: GuidedRun,
    ) {
        events.forEach { event ->
            val set = run.items.getOrNull(event.index)?.let { item -> item.setId?.let { it to item } } ?: return@forEach
            survive(Unit) {
                when (event) {
                    is GuidedEvent.Ended -> {
                        val length = set.second.length ?: Duration.ZERO
                        days.logHold(set.first, length, event.at - length, event.at)
                    }

                    is GuidedEvent.Done -> {
                        days.setDone(set.first, true)
                    }
                }
            }
        }
        val end = events.filterIsInstance<GuidedEvent.Ended>().lastOrNull() ?: return
        if (alarm && clock.now() - end.at <= LATE_RING) {
            try {
                alerts.stepEnded()
            } catch (_: RuntimeException) {
            }
        }
    }

    private suspend fun restore() {
        val values = settings.settings.first()
        alarm = values.alarm
        autoAdvance = values.autoAdvance
        val stored = store.load()
        saved = stored
        val run = stored?.let { runFor(it.source) }
        if (stored == null || run == null || (!stored.finished && stored.index !in run.items.indices) || stale(stored.source)) {
            publish(null, null)
            return
        }
        val change = GuidedEngine.expire(stored, run, clock.now(), autoAdvance)
        handle(change.events, run)
        publish(change.snapshot, run)
    }

    // A warm-up belongs to today's unfinished workout: on another day, or once it's finished, nothing
    // more of it is logged.
    private suspend fun stale(source: GuidedSource): Boolean {
        if (source !is GuidedSource.Warmup) return false
        if (source.date != clock.today(zone)) return true
        return days.day(source.date).first()?.finished != false
    }

    private suspend fun dropWarmup(date: LocalDate) {
        val snapshot = current() ?: return
        if (snapshot.source == GuidedSource.Warmup(date) && !snapshot.finished) publish(null, null)
    }

    private suspend fun runFor(source: GuidedSource): GuidedRun? =
        when (source) {
            is GuidedSource.Plan -> plans.plan(source.id).first()?.let(GuidedRun::of)
            is GuidedSource.Warmup -> warmupRun(source.date)
        }

    private suspend fun warmupRun(date: LocalDate): GuidedRun? = days.day(date).first()?.let { GuidedRun.warmup(it, WARMUP_SECTION) }

    // A session that's still going ends before another starts; a plan's is logged as stopped. One
    // with progress is named, so the player can say it ended.
    private suspend fun replace() {
        val snapshot = current() ?: return
        if (snapshot.finished) return
        if (snapshot.done.isNotEmpty() || snapshot.index > 0) replaced = snapshot.title
        publish(GuidedEngine.end(snapshot, clock.now()), run())
    }

    private suspend fun change(change: (GuidedSnapshot, GuidedRun) -> GuidedSnapshot) =
        command {
            expireLocked()
            val snapshot = current() ?: return@command
            val run = run() ?: return@command
            publish(change(snapshot, run), run)
        }

    private suspend fun <T> command(block: suspend () -> T): T {
        restored.await()
        return mutex.withLock { block() }
    }

    private fun current(): GuidedSnapshot? = mutableState.value.snapshot

    private fun run(): GuidedRun? = mutableState.value.run

    private suspend fun publish(
        snapshot: GuidedSnapshot?,
        run: GuidedRun?,
    ) {
        val before = mutableState.value.snapshot
        mutableState.value = GuidedState(snapshot, run.takeIf { snapshot != null }, clock.now(), replaced)
        if (snapshot?.timing == true && before?.timing != true) timers.claim(TimerOwner.Guided)
        // With the alarm off no end is scheduled: the ticker and the next resume still move it on. The
        // idle deadline only ends the session, never rings, so it's always scheduled.
        val idleAt = snapshot?.let(GuidedEngine::idleAt)
        val wakeup = listOfNotNull(snapshot?.let(GuidedEngine::nextEnd)?.takeIf { alarm }, idleAt).minOrNull()
        if (wakeup != null) wakeups.scheduleGuidedWakeup(wakeup) else wakeups.cancelGuidedWakeup()
        if (snapshot?.timing == true) ensureTicking()
        watchIdle(idleAt)
        // A run restored already ended was logged before.
        if (snapshot != null && snapshot.finished && before?.finished != true && saved?.finished != true) {
            GuidedEngine.log(snapshot, snapshot.startedAt.localDate(zone))?.let { log -> survive(Unit) { plans.log(log) } }
        }
        if (snapshot != saved) {
            survive(Unit) {
                store.save(snapshot)
                saved = snapshot
            }
        }
    }

    // Republishes the time on the countdown's whole seconds and ends what's due.
    private fun ensureTicking() {
        if (ticker?.isActive == true) return
        ticker =
            scope.launch {
                while (isActive) {
                    val snapshot =
                        mutex.withLock {
                            current()?.takeIf { it.timing } ?: run {
                                ticker = null
                                null
                            }
                        } ?: break
                    val now = clock.now()
                    val due = GuidedEngine.nextEnd(snapshot)
                    if (due != null && now >= due) {
                        if (survive(false) { expireIfDue() }) continue
                    }
                    mutableState.update { if (it.snapshot == snapshot) it.copy(now = now) else it }
                    val anchor = due ?: now
                    val toNextSecond = (anchor - now).inWholeMilliseconds.mod(MILLIS_PER_SECOND)
                    delay(if (toNextSecond == 0L) MILLIS_PER_SECOND else toNextSecond)
                }
            }
    }

    // With the screen on and nothing counting down there's no ticker, so a job wakes at the idle
    // deadline; the wakeup alarm covers the screen being off.
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

    private suspend fun retrying(collect: suspend () -> Unit) {
        while (true) {
            if (survive(false) { collect().let { true } }) return
            delay(1.seconds)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        // An end noticed later than this (the app was stopped, no alarm fired) moves on without a beep.
        val LATE_RING: Duration = 1.minutes
    }
}

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
