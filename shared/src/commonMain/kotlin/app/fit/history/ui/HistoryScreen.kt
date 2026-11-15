// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.WorkoutDay
import app.fit.day.ui.RecoveryLine
import app.fit.day.ui.dayResult
import app.fit.history.HistoryStats
import app.fit.resources.Res
import app.fit.resources.day_unnamed
import app.fit.resources.history_day_nothing
import app.fit.resources.history_fatigue
import app.fit.resources.history_last_seven
import app.fit.resources.history_list
import app.fit.resources.history_none
import app.fit.resources.history_open_day
import app.fit.resources.history_streak
import app.fit.resources.history_title
import app.fit.resources.history_workouts
import app.fit.ui.BackButton
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.formatClock
import app.fit.ui.formatDate
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HistoryRoute(
    onOpenDay: (LocalDate) -> Unit,
    onOpenSummary: (LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenDay = onOpenDay,
        onOpenSummary = onOpenSummary,
        onBack = onBack,
        modifier = modifier,
    )
}

// The counts, the month, and every workout newest first. The streak is one number among three:
// shown, never pushed.
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenSummary: (LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.history_title)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        if (state.loading) return@Column
        val list = rememberLazyListState()
        // A tapped day is brought into view, or on a small phone the tap seems to do nothing.
        var tapped by remember { mutableStateOf<LocalDate?>(null) }
        LaunchedEffect(tapped, state.picked) {
            if (tapped != state.picked.date) return@LaunchedEffect
            // After the list has laid the day's block out at its new size.
            withFrameNanos {}
            val info = list.layoutInfo
            val clear = info.viewportEndOffset - info.afterContentPadding
            val block = info.visibleItemsInfo.firstOrNull { it.key == PICKED_DAY_TAG }
            if (block == null) {
                list.animateScrollToItem(PICKED_DAY_INDEX, -clear / 2)
            } else {
                val over = block.offset + block.size - clear
                if (over > 0) list.animateScrollBy(over.toFloat())
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        ) {
            item(key = "stats") { Stats(state.stats) }
            item(key = "calendar") {
                Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceBright, modifier = Modifier.padding(top = 12.dp)) {
                    MonthCalendar(
                        month = state.month,
                        weeks = state.weeks,
                        onPrevious = { onAction(HistoryAction.ShowMonth(-1)) },
                        onNext = { onAction(HistoryAction.ShowMonth(1)) },
                        onSelect = {
                            tapped = it
                            onAction(HistoryAction.Pick(it))
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
            item(key = PICKED_DAY_TAG) {
                Picked(picked = state.picked, today = state.today, onOpenDay = onOpenDay, onOpenSummary = onOpenSummary)
            }
            item(key = "list") { SectionTitle(stringResource(Res.string.history_list), modifier = Modifier.padding(start = 4.dp)) }
            if (state.days.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(Res.string.history_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            itemsIndexed(state.days, key = { _, day -> day.date.toString() }) { index, day ->
                DayRow(
                    day = day,
                    date = formatDate(day.date, state.today),
                    index = index,
                    count = state.days.size,
                    onClick = { onOpenSummary(day.date) },
                )
            }
        }
    }
}

@Composable
private fun Stats(stats: HistoryStats) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // A streak of 0 isn't shown: there's nothing to keep, and nothing to say about it.
        val tiles =
            listOfNotNull(
                stats.workouts to Res.string.history_workouts,
                stats.lastSevenDays to Res.string.history_last_seven,
                (stats.streak to Res.string.history_streak).takeIf { stats.streak > 0 },
            )
        tiles.forEachIndexed { index, (value, label) ->
            val outer = 20.dp
            val inner = 4.dp
            Surface(
                shape =
                    RoundedCornerShape(
                        topStart = if (index == 0) outer else inner,
                        bottomStart = if (index == 0) outer else inner,
                        topEnd = if (index == tiles.lastIndex) outer else inner,
                        bottomEnd = if (index == tiles.lastIndex) outer else inner,
                    ),
                color = colors.surfaceBright,
                modifier = Modifier.weight(1f),
            ) {
                val text = stringResource(label)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp).semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        "$value",
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                        fontFamily = LocalAppFonts.current.title,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

const val PICKED_DAY_TAG = "picked-day"

// After the stats and the calendar.
private const val PICKED_DAY_INDEX = 2

// The day picked in the calendar; a day with nothing says only that (no word about missing it).
@Composable
private fun Picked(
    picked: PickedDay,
    today: LocalDate,
    onOpenDay: (LocalDate) -> Unit,
    onOpenSummary: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag(PICKED_DAY_TAG)) {
        SectionTitle(formatDate(picked.date, today), modifier = Modifier.padding(start = 4.dp))
        val rows = 1 + picked.records.size
        picked.workout?.let { day ->
            DayRow(day = day, date = null, index = 0, count = rows, onClick = { onOpenSummary(day.date) })
            picked.records.forEachIndexed { index, record -> RecordRow(record, index + 1, rows) }
        }
        picked.recovery.forEach { RecoveryLine(it) }
        if (picked.workout == null && picked.recovery.isEmpty()) {
            Text(
                stringResource(Res.string.history_day_nothing),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        FilledTonalButton(
            onClick = { onOpenDay(picked.date) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
        ) { Text(stringResource(Res.string.history_open_day)) }
    }
}

// Name, date and result; the fatigue on the right when it was entered.
@Composable
private fun DayRow(
    day: WorkoutDay,
    date: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = listRowShape(index, count), color = colors.surfaceContainerHigh) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        day.name.ifBlank { stringResource(Res.string.day_unnamed) },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(date, dayResult(day, day.duration?.let(::formatClock))).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                day.fatigue?.let {
                    Text(
                        stringResource(Res.string.history_fatigue, it),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun HistoryScreenPreview() {
    FitTheme {
        HistoryScreen(
            state = HistoryUiState(loading = false, stats = HistoryStats(12, 3, 2)),
            onAction = {},
            onOpenDay = {},
            onOpenSummary = {},
            onBack = {},
        )
    }
}
