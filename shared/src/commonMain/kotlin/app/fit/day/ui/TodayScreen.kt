// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayId
import app.fit.day.DayMark
import app.fit.day.WorkoutDay
import app.fit.guided.GuidedLog
import app.fit.history.ui.DayMarks
import app.fit.history.ui.RecoveryDash
import app.fit.recovery.ui.logLine
import app.fit.resources.Res
import app.fit.resources.action_add_exercise
import app.fit.resources.action_resume
import app.fit.resources.action_review
import app.fit.resources.action_start
import app.fit.resources.action_summary
import app.fit.resources.action_use_routine
import app.fit.resources.bodyweight_deleted
import app.fit.resources.bodyweight_was
import app.fit.resources.calendar_open
import app.fit.resources.day_added
import app.fit.resources.day_collapsed_section
import app.fit.resources.day_deleted
import app.fit.resources.day_done_count
import app.fit.resources.day_drag
import app.fit.resources.day_empty
import app.fit.resources.day_meta_estimate
import app.fit.resources.day_next
import app.fit.resources.day_next_set
import app.fit.resources.day_options
import app.fit.resources.day_other_routines
import app.fit.resources.day_plan
import app.fit.resources.day_previous
import app.fit.resources.day_remove_block
import app.fit.resources.day_removed
import app.fit.resources.day_removed_many
import app.fit.resources.day_rest
import app.fit.resources.day_rest_over
import app.fit.resources.day_resting
import app.fit.resources.day_routines
import app.fit.resources.day_sets_of
import app.fit.resources.day_unnamed
import app.fit.resources.deload_week
import app.fit.resources.details_saved
import app.fit.resources.editor_move_down
import app.fit.resources.editor_move_up
import app.fit.resources.exercise_count
import app.fit.resources.ic_add
import app.fit.resources.ic_calendar_month
import app.fit.resources.ic_check
import app.fit.resources.ic_drag_indicator
import app.fit.resources.ic_flag
import app.fit.resources.ic_keyboard_arrow_left
import app.fit.resources.ic_keyboard_arrow_right
import app.fit.resources.ic_more_vert
import app.fit.resources.ic_play_large
import app.fit.resources.options_finish
import app.fit.resources.options_reorder
import app.fit.resources.status_completed
import app.fit.resources.status_in_progress_clock
import app.fit.resources.status_logged
import app.fit.resources.status_planned
import app.fit.resources.undo_failed
import app.fit.resources.week_n
import app.fit.resources.workout_all_done
import app.fit.routines.MAIN_SECTION
import app.fit.routines.WARMUP_SECTION
import app.fit.routines.inSectionOrder
import app.fit.routines.ui.DAY_PICKER
import app.fit.routines.ui.ExercisePickerSheet
import app.fit.routines.ui.ExercisePickerViewModel
import app.fit.routines.ui.routineSummary
import app.fit.ui.BackButton
import app.fit.ui.BackGuard
import app.fit.ui.Pill
import app.fit.ui.ProgressRing
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.SwipeToRemove
import app.fit.ui.TopBarAction
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.dragHandle
import app.fit.ui.formatClock
import app.fit.ui.formatDate
import app.fit.ui.formatEstimate
import app.fit.ui.formatNumber
import app.fit.ui.rememberDragReorder
import app.fit.ui.reorderable
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import app.fit.ui.weekdayLetter
import app.fit.workout.WorkoutEngine
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun TodayRoute(
    onOpenBlock: (BlockId) -> Unit,
    // Bumped when another screen put a workout on today, so the screen shows today ([showDate] when
    // History opened another day).
    showToday: Int,
    modifier: Modifier = Modifier,
    showDate: LocalDate? = null,
    onOpenSummary: (LocalDate) -> Unit = {},
    // The launcher shortcut: start or resume today's workout once, then acknowledge.
    startWorkout: Boolean = false,
    onStartWorkoutSeen: () -> Unit = {},
    // Reorder mode is a sub-screen: the app hides its toolbar.
    onReorder: (Boolean) -> Unit = {},
    // The warm-up runs on the guided player.
    onOpenPlayer: () -> Unit = {},
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(startWorkout) {
        if (startWorkout) {
            onStartWorkoutSeen()
            viewModel.onAction(TodayAction.StartFromShortcut)
        }
    }
    // Saved with the entry, so coming back from a day's exercise doesn't jump to today again.
    var shownToday by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(showToday) {
        if (showToday > shownToday) {
            shownToday = showToday
            viewModel.onAction(showDate?.let { TodayAction.Select(it) } ?: TodayAction.ShowToday)
        }
    }
    LaunchedEffect(state.summary) {
        state.summary?.let {
            viewModel.onAction(TodayAction.SummarySeen)
            onOpenSummary(it)
        }
    }
    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.onAction(TodayAction.OpenedSeen)
            onOpenBlock(it)
        }
    }
    LaunchedEffect(state.player) {
        if (state.player) {
            viewModel.onAction(TodayAction.PlayerSeen)
            onOpenPlayer()
        }
    }
    val reorder = state.mode == TodayMode.Reorder
    val reportReorder by rememberUpdatedState(onReorder)
    LaunchedEffect(reorder) { reportReorder(reorder) }
    DisposableEffect(Unit) { onDispose { reportReorder(false) } }
    TodayScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        picker = { DayPicker(date = state.date, onAction = viewModel::onAction) },
    )
}

// Keyed by date, so a new day gets a fresh picker.
@Composable
private fun DayPicker(
    date: LocalDate,
    onAction: (TodayAction) -> Unit,
) {
    val picker: ExercisePickerViewModel =
        koinViewModel(key = "day-picker:$date", qualifier = named(DAY_PICKER)) { parametersOf(date.toString()) }
    val state by picker.uiState.collectAsStateWithLifecycle()
    ExercisePickerSheet(
        state = state,
        onAction = picker::onAction,
        onAdded = { onAction(TodayAction.Added(BlockId(it.id), it.name)) },
        onClose = { onAction(TodayAction.ClosePicker) },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TodayScreen(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
    picker: @Composable () -> Unit = {},
) {
    val reorder = state.mode == TodayMode.Reorder
    // Back leaves reorder mode before it leaves the screen; sheets and dialogs close themselves first.
    BackGuard(enabled = reorder) { onAction(TodayAction.SetMode(TodayMode.Normal)) }
    TodaySheets(state = state, onAction = onAction)
    if (state.pickerOpen) picker()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize()) {
            if (reorder) {
                ScreenTopBar(
                    title = { ScreenTitle(stringResource(Res.string.options_reorder)) },
                    navigationIcon = {
                        BackButton(onClick = { onAction(TodayAction.SetMode(TodayMode.Normal)) }, modifier = Modifier.padding(start = 8.dp))
                    },
                )
            } else {
                ScreenTopBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val title = dayTitle(state.date, state.today)
                            val date = formatDate(state.date)
                            ScreenTitle(title)
                            // "Today" and its neighbours also show the date.
                            if (title != date) {
                                Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        TopBarAction(
                            icon = Res.drawable.ic_calendar_month,
                            description = stringResource(Res.string.calendar_open),
                            onClick = { onAction(TodayAction.OpenCalendar) },
                        )
                    },
                )
            }
            if (!state.loading) DayList(state = state, onAction = onAction, modifier = Modifier.weight(1f))
        }
        UndoSnackbar(
            message = state.undo?.let { undoMessage(it) },
            onUndo = { onAction(TodayAction.Undo) },
            onDismiss = { onAction(TodayAction.UndoSeen) },
            modifier = Modifier.align(Alignment.BottomCenter),
            canUndo = state.undo != TodayUndo.Failed && state.undo !is TodayUndo.Saved,
        )
    }
}

@Composable
private fun undoMessage(undo: TodayUndo): String =
    when (undo) {
        is TodayUndo.Added -> {
            stringResource(Res.string.day_added, undo.name)
        }

        is TodayUndo.Removed -> {
            undo.names.singleOrNull()?.let { stringResource(Res.string.day_removed, it) }
                ?: pluralStringResource(Res.plurals.day_removed_many, undo.names.size, undo.names.size)
        }

        is TodayUndo.Deleted -> {
            stringResource(Res.string.day_deleted, formatDate(undo.date))
        }

        TodayUndo.Failed -> {
            stringResource(Res.string.undo_failed)
        }

        is TodayUndo.BodyWeightChanged -> {
            val reading = "${formatNumber(undo.before.weight)} ${undo.before.unit.symbol}"
            stringResource(if (undo.deleted) Res.string.bodyweight_deleted else Res.string.bodyweight_was, reading)
        }

        is TodayUndo.Saved -> {
            stringResource(Res.string.details_saved, undo.name)
        }
    }

// Mon–Sun with the selected day filled, between previous and next day.
@Composable
private fun WeekStrip(
    cells: List<WeekCell>,
    onAction: (TodayAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onAction(TodayAction.Previous) }) {
            Icon(painterResource(Res.drawable.ic_keyboard_arrow_left), contentDescription = stringResource(Res.string.day_previous))
        }
        cells.forEach { cell ->
            val label = formatDate(cell.date)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (cell.selected) colors.primaryContainer else Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = label) { onAction(TodayAction.Select(cell.date)) }
                        .padding(vertical = 4.dp),
            ) {
                val on = if (cell.selected) colors.onPrimaryContainer else colors.outline
                Text(weekdayLetter(cell.date), style = MaterialTheme.typography.labelSmall, color = on)
                Text(
                    "${cell.date.day}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (cell.today) FontWeight.Bold else FontWeight.SemiBold,
                    color =
                        when {
                            cell.selected -> colors.onPrimaryContainer
                            cell.today -> colors.primary
                            else -> colors.onSurface
                        },
                    textAlign = TextAlign.Center,
                )
                // A ring for a day with done sets, a filled dot once that day is finished, and a dash for
                // a recovery session.
                DayMarks(
                    finished =
                        when (cell.mark) {
                            DayMark.None -> null
                            DayMark.Trained -> false
                            DayMark.Finished -> true
                        },
                    recovery = cell.recovery,
                    diameter = 6.dp,
                    selected = cell.selected,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = { onAction(TodayAction.Next) }) {
            Icon(painterResource(Res.drawable.ic_keyboard_arrow_right), contentDescription = stringResource(Res.string.day_next))
        }
    }
}

// One card that says what's next, with one big button and the day's options.
@Composable
private fun Hero(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val day = state.day
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 12.dp),
    ) {
        Column(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                (day == null || !state.hasBlocks) && state.planned != null -> {
                    Eyebrow(stringResource(Res.string.status_planned), state)
                    HeroTitle(state.planned.name)
                    Meta(routineSummary(state.planned))
                }

                day == null || !state.hasBlocks -> {
                    // The program says rest, or nothing is planned.
                    ProgramLine(state)
                    HeroTitle(stringResource(if (state.plan != null) Res.string.day_rest else Res.string.day_empty))
                }

                state.status == DayStatus.InProgress -> {
                    ProgramLine(state)
                    InProgress(state = state, day = day)
                }

                else -> {
                    Eyebrow(
                        stringResource(
                            when (state.status) {
                                DayStatus.Completed -> Res.string.status_completed
                                DayStatus.Logged -> Res.string.status_logged
                                else -> Res.string.status_planned
                            },
                        ),
                        state,
                    )
                    HeroTitle(day.name.ifBlank { stringResource(Res.string.day_unnamed) })
                    Meta(heroMeta(state, day))
                }
            }
            HeroButtons(primary = state.primary, onAction = onAction)
        }
    }
}

@Composable
private fun heroMeta(
    state: TodayUiState,
    day: WorkoutDay,
): String =
    when (state.status) {
        DayStatus.Completed -> {
            dayResult(day, formatClock(day.elapsed(state.now)).takeIf { day.started })
        }

        DayStatus.Logged -> {
            listOfNotNull(stringResource(Res.string.day_sets_of, day.doneSets, day.totalSets), volumeText(day.volume)).joinToString(" · ")
        }

        else -> {
            val exercises = pluralStringResource(Res.plurals.exercise_count, day.blocks.size, day.blocks.size)
            stringResource(Res.string.day_meta_estimate, exercises, formatEstimate(day.estimate))
        }
    }

// The day's progress as a small ring, the exercise to do and its next set, and a running rest.
@Composable
private fun InProgress(
    state: TodayUiState,
    day: WorkoutDay,
) {
    val colors = MaterialTheme.colorScheme
    val current = state.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            ProgressRing(
                progress = { if (day.totalSets == 0) 0f else day.doneSets.toFloat() / day.totalSets },
                wavy = false,
                color = colors.primary,
                trackColor = colors.onPrimaryContainer.copy(alpha = 0.2f),
                strokeWidth = 6.dp,
                modifier = Modifier.size(64.dp),
            )
            Text(
                stringResource(Res.string.day_done_count, day.doneSets, day.totalSets),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(stringResource(Res.string.status_in_progress_clock, formatClock(day.elapsed(state.now))))
            HeroTitle(current?.exercise?.fields?.name ?: stringResource(Res.string.workout_all_done), small = true)
            val next = current?.nextSet
            Meta(
                if (current != null && next != null) {
                    stringResource(Res.string.day_next_set, setLabel(current, next), setValues(current, next))
                } else {
                    dayResult(day, null)
                },
            )
        }
    }
    state.rest?.let { rest ->
        val left = WorkoutEngine.restLeft(rest, state.now)
        val over = rest.rang || left == Duration.ZERO
        Surface(shape = CircleShape, color = colors.tertiary, contentColor = colors.onTertiary, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                if (over) {
                    stringResource(Res.string.day_rest_over, formatClock(WorkoutEngine.restOver(rest, state.now)))
                } else {
                    stringResource(Res.string.day_resting, formatClock(left + 999.milliseconds))
                },
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// "Week 6" and a "Deload week" pill, when the active program has weeks.
@Composable
private fun ProgramLine(state: TodayUiState) {
    val week = state.programWeek
    if (week == null && !state.deload) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        week?.let { Text(stringResource(Res.string.week_n, it), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
        if (state.deload) Pill(stringResource(Res.string.deload_week), tertiary = true)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
}

// "Planned · Week 6" and a Deload week pill on one line.
@Composable
private fun Eyebrow(
    text: String,
    state: TodayUiState,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(listOfNotNull(text, state.programWeek?.let { stringResource(Res.string.week_n, it) }).joinToString(" · "))
        if (state.deload) Pill(stringResource(Res.string.deload_week), tertiary = true, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun HeroTitle(
    text: String,
    small: Boolean = false,
) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        fontFamily = LocalAppFonts.current.title,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Meta(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
}

@Composable
private fun HeroButtons(
    primary: PrimaryAction,
    onAction: (TodayAction) -> Unit,
) {
    val (label, action) =
        when (primary) {
            PrimaryAction.Start -> Res.string.action_start to TodayAction.Start
            PrimaryAction.Resume -> Res.string.action_resume to TodayAction.Resume
            PrimaryAction.Finish -> Res.string.options_finish to TodayAction.Finish
            PrimaryAction.Summary -> Res.string.action_summary to TodayAction.OpenSummary
            PrimaryAction.Review -> Res.string.action_review to TodayAction.Review
            PrimaryAction.StartPlan -> Res.string.action_start to TodayAction.StartPlan
            PrimaryAction.UsePlan -> Res.string.action_use_routine to TodayAction.UsePlan
            PrimaryAction.AddExercise -> Res.string.action_add_exercise to TodayAction.OpenPicker
        }
    val icon =
        when (primary) {
            PrimaryAction.Start, PrimaryAction.Resume, PrimaryAction.StartPlan -> Res.drawable.ic_play_large
            PrimaryAction.Finish -> Res.drawable.ic_flag
            PrimaryAction.AddExercise -> Res.drawable.ic_add
            PrimaryAction.Summary, PrimaryAction.Review, PrimaryAction.UsePlan -> null
        }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(
            onClick = { onAction(action) },
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
            modifier = Modifier.weight(1f).height(64.dp),
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(24.dp)) }
            Text(
                stringResource(label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = if (icon != null) ButtonDefaults.IconSpacing else 0.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { onAction(TodayAction.OpenOptions) },
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.size(64.dp),
        ) {
            Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.day_options))
        }
    }
}

// The plan: dense, read-only rows by section, the current one marked and a warm-up section as one row.
// In reorder mode each row has a handle to drag and swipes away.
@Composable
private fun DayList(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val day = state.day
    val blocks = inSectionOrder(day?.blocks.orEmpty()) { it.section }
    val sections = blocks.groupBy { it.section }
    val reorder = state.mode == TodayMode.Reorder
    val list = rememberLazyListState()
    val drag = rememberDragReorder(blocks.map { it.id.value })
    val started = state.status == DayStatus.InProgress || state.status == DayStatus.Completed || state.status == DayStatus.Logged
    val current = state.current.takeIf { state.status == DayStatus.InProgress }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = UndoBarSpace)) {
        // Always there (empty while reordering), so leaving reorder mode shows the hero again.
        item(key = "week") { if (!reorder) WeekStrip(cells = state.week, onAction = onAction) }
        item(key = "hero") { if (!reorder) Hero(state = state, onAction = onAction) }
        if (!state.hasBlocks && !state.loading) emptyDay(state, onAction)
        if (state.hasBlocks && !reorder) {
            item(key = "plan") {
                // The hero names the exercise while a workout runs, so the list names the day.
                val name = day?.name?.takeIf { current != null && it.isNotBlank() }
                SectionTitle(name ?: stringResource(Res.string.day_plan), modifier = Modifier.padding(start = 4.dp))
            }
        }
        sections.forEach { (section, group) ->
            if (section == WARMUP_SECTION && !reorder) {
                val runs = state.date == state.today && day?.finished == false
                item(key = "section:$section") {
                    CollapsedSection(name = section, blocks = group, started = started, runsOnPlayer = runs, onAction = onAction)
                }
                return@forEach
            }
            item(key = "section:$section") {
                // As in the old app: Main only gets a header next to other sections.
                if (section != MAIN_SECTION || sections.size > 1) SectionTitle(section, modifier = Modifier.padding(start = 4.dp))
            }
            val keys = group.map { it.id.value }
            itemsIndexed(group, key = { _, block -> block.id.value }) { index, block ->
                val row: @Composable () -> Unit = {
                    PlanRow(
                        block = block,
                        index = index,
                        count = group.size,
                        current = block.id == current?.id,
                        started = started,
                        reorder = reorder,
                        onAction = onAction,
                        handle =
                            Modifier.dragHandle(drag, list, block.id.value, keys) { key, by ->
                                onAction(TodayAction.Move(BlockId(key as Long), by))
                            },
                    )
                }
                Box(Modifier.reorderable(drag, block.id.value).padding(horizontal = 12.dp)) {
                    if (reorder) {
                        SwipeToRemove(
                            label = stringResource(Res.string.day_remove_block),
                            onRemove = { onAction(TodayAction.Remove(block.id)) },
                            shape = listRowShape(index, group.size),
                        ) { row() }
                    } else {
                        row()
                    }
                }
            }
        }
        if (!reorder) {
            itemsIndexed(state.recovery, key = { _, log -> "recovery:${log.startedAt.toEpochMilliseconds()}" }) { _, log ->
                RecoveryLine(log)
            }
        }
    }
}

const val RECOVERY_LINE_TAG = "recovery-line"

// A recovery session done that day, quietly under the workout: "Wrist reset · 8 min".
@Composable
internal fun RecoveryLine(log: GuidedLog) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp).testTag(RECOVERY_LINE_TAG),
    ) {
        RecoveryDash(selected = false)
        Text(logLine(log), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun PlanRow(
    block: Block,
    index: Int,
    count: Int,
    current: Boolean,
    started: Boolean,
    reorder: Boolean,
    onAction: (TodayAction) -> Unit,
    handle: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val working = block.workingSets
    val done = block.sets.isNotEmpty() && !block.hasSetsLeft
    val doneCount = working.count { it.done }
    val up = stringResource(Res.string.editor_move_up)
    val down = stringResource(Res.string.editor_move_down)
    Surface(
        onClick = { onAction(TodayAction.Open(block.id)) },
        enabled = !reorder,
        shape = listRowShape(index, count),
        color = if (current) colors.surfaceBright else colors.surfaceContainerHigh,
        modifier =
            Modifier.fillMaxWidth().semantics {
                if (reorder) {
                    customActions =
                        listOfNotNull(
                            CustomAccessibilityAction(up) { true.also { onAction(TodayAction.Move(block.id, -1)) } }.takeIf { index > 0 },
                            CustomAccessibilityAction(down) {
                                true.also { onAction(TodayAction.Move(block.id, 1)) }
                            }.takeIf { index < count - 1 },
                        )
                }
            },
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = if (reorder) 4.dp else 16.dp),
            ) {
                when {
                    reorder -> {}

                    current -> {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.primary),
                        )
                    }

                    done -> {
                        Icon(
                            painterResource(Res.drawable.ic_check),
                            contentDescription = null,
                            tint = colors.outline,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    block.exercise.fields.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                    color = if (done && !reorder) colors.outline else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                Text(
                    if (started && (doneCount > 0 || current)) {
                        stringResource(Res.string.day_done_count, doneCount, working.size)
                    } else {
                        planLine(block)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (done && !reorder) colors.outline else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                if (reorder) {
                    val drag = stringResource(Res.string.day_drag)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(48.dp).then(handle).semantics { contentDescription = drag },
                    ) {
                        Icon(painterResource(Res.drawable.ic_drag_indicator), contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// Under an empty day's hero: the other routines, one tap each to apply, and Add exercise when the hero
// offers the plan instead.
private fun LazyListScope.emptyDay(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
) {
    val rows = state.otherRoutines
    val addRow = state.planned != null
    val count = rows.size + if (addRow) 1 else 0
    if (count == 0) return
    item(key = "routines") {
        SectionTitle(
            stringResource(if (state.planned != null) Res.string.day_other_routines else Res.string.day_routines),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    itemsIndexed(rows, key = { _, row -> "routine:${row.id.value}" }) { index, row ->
        EmptyDayRow(
            title = row.name,
            trailing = routineSummary(row),
            index = index,
            count = count,
            onClick = { onAction(TodayAction.ApplyRoutine(row.id)) },
        )
    }
    if (addRow) {
        item(key = "add-exercise") {
            EmptyDayRow(
                title = stringResource(Res.string.action_add_exercise),
                trailing = null,
                index = count - 1,
                count = count,
                icon = Res.drawable.ic_add,
                onClick = { onAction(TodayAction.OpenPicker) },
            )
        }
    }
}

@Composable
private fun EmptyDayRow(
    title: String,
    trailing: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    icon: DrawableResource? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp),
            ) {
                icon?.let {
                    Icon(
                        painterResource(it),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                trailing?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// "Warm-up · 8 exercises" as one row. Today it runs on the player; another day it opens the first
// exercise in it with sets left.
@Composable
private fun CollapsedSection(
    name: String,
    blocks: List<Block>,
    started: Boolean,
    runsOnPlayer: Boolean,
    onAction: (TodayAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sets = blocks.flatMap { it.sets }
    val done = blocks.none { it.hasSetsLeft }
    val doneCount = sets.count { it.done }
    val target = blocks.firstOrNull { it.hasSetsLeft } ?: blocks.first()
    val estimate = blocks.fold(Duration.ZERO) { total, b -> total + (b.duration + b.rest) * b.sets.size }
    Surface(
        onClick = { onAction(if (runsOnPlayer) TodayAction.Warmup else TodayAction.Open(target.id)) },
        shape = listRowShape(0, 1),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
        ) {
            if (done) {
                Icon(
                    painterResource(Res.drawable.ic_check),
                    contentDescription = null,
                    tint = colors.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                stringResource(
                    Res.string.day_collapsed_section,
                    name,
                    pluralStringResource(Res.plurals.exercise_count, blocks.size, blocks.size),
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (done) colors.outline else colors.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            Text(
                if (started && doneCount > 0) stringResource(Res.string.day_done_count, doneCount, sets.size) else formatEstimate(estimate),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", fontSize = 14.sp),
                color = if (done) colors.outline else colors.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun TodayScreenPreview() {
    val today = LocalDate(2026, 11, 9)
    FitTheme {
        TodayScreen(
            state =
                TodayUiState(
                    loading = false,
                    date = today,
                    today = today,
                    day = WorkoutDay(DayId(1), today, name = "Legs"),
                ),
            onAction = {},
        )
    }
}
