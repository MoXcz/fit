// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.content.ArchiveEntry
import app.fit.content.ArchiveLimits
import app.fit.content.ArchiveResult
import app.fit.content.Archives
import app.fit.content.RecoveryDraft
import app.fit.content.Source
import app.fit.core.BodyArea
import app.fit.core.Documents
import app.fit.core.OpenedDocument
import app.fit.core.SaveResult
import app.fit.core.SessionTimers
import app.fit.day.DayRepository
import app.fit.exercises.Sides
import app.fit.settings.SettingsRepository
import app.fit.workout.WakeupScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class FakeGuidedRepository(
    initial: List<GuidedPlan> = emptyList(),
    private val clock: Clock? = null,
) : GuidedRepository {
    val all = MutableStateFlow(initial)
    val logged = MutableStateFlow<List<GuidedLog>>(emptyList())
    val synced = mutableListOf<List<RecoveryDraft>>()

    override fun plans(kind: GuidedKind): Flow<List<GuidedPlan>> =
        all.map { list -> list.filter { it.kind == kind }.sortedBy { it.title.lowercase() } }

    override fun plan(id: GuidedPlanId): Flow<GuidedPlan?> = all.map { list -> list.firstOrNull { it.id == id } }

    override val pinned: Flow<List<GuidedPlan>> =
        plans(GuidedKind.Recovery).map { list -> list.filter { it.pinned }.sortedByDescending { it.pinnedAt } }

    override suspend fun setPinned(
        id: GuidedPlanId,
        pinned: Boolean,
    ) {
        val at = if (pinned) requireNotNull(clock).now() else null
        all.update { list -> list.map { if (it.id == id) it.copy(pinnedAt = at) else it } }
    }

    override suspend fun log(log: GuidedLog) {
        logged.update { list -> if (list.any { it.startedAt == log.startedAt }) list else list + log }
    }

    override fun logs(
        kind: GuidedKind,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<GuidedLog>> = logs(kind).map { list -> list.filter { it.day in from..to } }

    override fun logs(kind: GuidedKind): Flow<List<GuidedLog>> =
        logged.map { list -> list.filter { it.kind == kind }.sortedBy { it.startedAt } }

    private class Deleted(
        val plan: GuidedPlan,
    ) : DeletedPlan {
        override val title: String get() = plan.title
    }

    // As the database does: the logs stay, without their plan.
    override suspend fun delete(id: GuidedPlanId): DeletedPlan? {
        val plan = all.value.firstOrNull { it.id == id } ?: return null
        all.update { list -> list - plan }
        unlinked += id to logged.value.filter { it.planId == id }.map { it.startedAt }
        logged.update { list -> list.map { if (it.planId == id) it.copy(planId = null) else it } }
        return Deleted(plan)
    }

    override suspend fun restore(deleted: DeletedPlan): Boolean {
        val plan = (deleted as? Deleted)?.plan ?: return false
        if (all.value.any { it.id == plan.id }) return false
        all.update { it + plan }
        val starts = unlinked.lastOrNull { it.first == plan.id }?.second.orEmpty()
        logged.update { list -> list.map { if (it.planId == null && it.startedAt in starts) it.copy(planId = plan.id) else it } }
        return true
    }

    private val unlinked = mutableListOf<Pair<GuidedPlanId, List<kotlin.time.Instant>>>()

    override suspend fun syncBuiltIn(drafts: List<RecoveryDraft>) {
        synced += drafts
    }
}

class FakeGuidedStateStore(
    var stored: GuidedSnapshot? = null,
) : GuidedStateStore {
    var saves = 0

    override suspend fun load(): GuidedSnapshot? = stored

    override suspend fun save(snapshot: GuidedSnapshot?) {
        stored = snapshot
        saves++
    }
}

class FakeGuidedAlerts : GuidedAlerts {
    var rung = 0

    override fun stepEnded() {
        rung++
    }
}

class FakeLauncherShortcuts : LauncherShortcuts {
    val published = mutableListOf<List<PinnedShortcut>>()

    // Refuses while set, as a rate-limited launcher does.
    var refusing = false

    override fun setPinned(sessions: List<PinnedShortcut>): Boolean {
        if (refusing) return false
        published += sessions
        return true
    }
}

// A "zip" for tests: the first bytes of a real one, then each entry's name and length on a line
// before its bytes. Unpacking counts sizes as the real one does.
class FakeArchives : Archives {
    override fun read(
        bytes: ByteArray,
        limits: ArchiveLimits,
    ): ArchiveResult {
        if (!bytes.startsWith(MAGIC)) return ArchiveResult.NotAnArchive
        val entries = mutableListOf<ArchiveEntry>()
        var at = MAGIC.size
        var total = 0L
        while (at < bytes.size) {
            val end = (at until bytes.size).first { bytes[it] == '\n'.code.toByte() }
            val header = bytes.copyOfRange(at, end).decodeToString()
            val name = header.substringBeforeLast('|')
            val size = header.substringAfterLast('|').toInt()
            if (entries.size >= limits.entries) return ArchiveResult.TooManyEntries
            if (size > limits.entryBytes) return ArchiveResult.TooLarge(name)
            total += size
            if (total > limits.totalBytes) return ArchiveResult.TooLarge(null)
            entries += ArchiveEntry(name, bytes.copyOfRange(end + 1, end + 1 + size))
            at = end + 1 + size
        }
        return ArchiveResult.Entries(entries)
    }

    override fun write(entries: List<ArchiveEntry>): ByteArray =
        entries.fold(MAGIC) { out, entry -> out + "${entry.name}|${entry.bytes.size}\n".encodeToByteArray() + entry.bytes }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        val MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

        fun zip(vararg files: Pair<String, String>): ByteArray =
            FakeArchives().write(files.map { (name, text) -> ArchiveEntry(name, text.encodeToByteArray()) })
    }
}

class FakeDocuments : Documents {
    // What the picker gives next; Cancelled once taken.
    var next: OpenedDocument = OpenedDocument.Cancelled
    val saved = mutableListOf<Pair<String, ByteArray>>()

    // What "save as" ends in: Cancelled when the user backs out, Failed when the file can't be written.
    var saving = SaveResult.Saved
    var opens = 0

    // Set to keep the picker open until it's completed.
    var held: CompletableDeferred<OpenedDocument>? = null

    override suspend fun open(): OpenedDocument {
        opens++
        held?.let { return it.await() }
        return next.also { next = OpenedDocument.Cancelled }
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
    ): SaveResult {
        if (saving == SaveResult.Saved) saved += name to bytes
        return saving
    }
}

class FakeContentImages(
    private val files: Map<String, ByteArray> = emptyMap(),
) : ContentImages {
    override suspend fun load(path: String): ByteArray? = files[path]
}

// A small session: a counted step, a timed step on each side for two rounds, a timed step. 5 s pauses.
fun recoveryPlan(
    id: Long = 1,
    title: String = "Wrist reset",
    transition: Int = 5,
    areas: List<BodyArea> = listOf(BodyArea.Wrists, BodyArea.Forearms),
    steps: List<GuidedStep> =
        listOf(
            GuidedStep("Wrist bends", reps = 10, cue = "Bend up, then down"),
            GuidedStep("Flexor stretch", duration = 30.seconds, sides = Sides.Each, rounds = 2),
            GuidedStep("Shake out", duration = 15.seconds),
        ),
    pinnedAt: kotlin.time.Instant? = null,
) = GuidedPlan(
    id = GuidedPlanId(id),
    contentId = "plan-$id",
    kind = GuidedKind.Recovery,
    title = title,
    areas = areas,
    minutes = 4,
    transition = transition.seconds,
    notes = "Stop if it hurts.",
    steps = steps,
    sources = listOf(Source("NHS", "https://www.nhs.uk/")),
    pinnedAt = pinnedAt,
)

fun guidedController(
    plans: GuidedRepository,
    days: DayRepository,
    settings: SettingsRepository,
    store: GuidedStateStore,
    wakeups: WakeupScheduler,
    alerts: GuidedAlerts,
    timers: SessionTimers,
    clock: Clock,
    scope: CoroutineScope,
) = GuidedController(plans, days, settings, store, wakeups, alerts, timers, clock, TimeZone.UTC, scope)

// A push-up drill: a timed wall push-up, then incline push-ups for two rounds. 20 s pauses.
fun practicePlan(
    id: Long = 10,
    title: String = "Push-up",
) = GuidedPlan(
    id = GuidedPlanId(id),
    contentId = null,
    kind = GuidedKind.Practice,
    title = title,
    minutes = 0,
    transition = 20.seconds,
    steps =
        listOf(
            GuidedStep("Wall push-up", duration = 40.seconds),
            GuidedStep("Incline push-up", reps = 6, rounds = 2),
        ),
)
