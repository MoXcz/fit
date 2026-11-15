// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.exercises.ui.label
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedState
import app.fit.resources.Res
import app.fit.resources.ic_play_large
import app.fit.resources.recover_empty
import app.fit.resources.recover_pinned
import app.fit.resources.recover_resume
import app.fit.resources.recover_running
import app.fit.resources.recover_sessions
import app.fit.resources.recover_start
import app.fit.resources.recover_start_named
import app.fit.resources.recover_title
import app.fit.resources.workout_position
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RecoverRoute(
    onOpenSession: (GuidedPlanId) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecoverViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        if (state.opened) {
            viewModel.onAction(RecoverAction.OpenedSeen)
            onOpenPlayer()
        }
    }
    RecoverScreen(state = state, onAction = viewModel::onAction, onOpenSession = onOpenSession, modifier = modifier)
}

// Pinned sessions first, then a body area to pick and its sessions, each one tap from starting.
@Composable
fun RecoverScreen(
    state: RecoverUiState,
    onAction: (RecoverAction) -> Unit,
    onOpenSession: (GuidedPlanId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        ScreenTopBar(title = { ScreenTitle(stringResource(Res.string.recover_title)) })
        if (state.loading) return@Column
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (state.plans.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.recover_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                    )
                }
            }
            state.running?.let { running ->
                item(key = "running") { RunningCard(running, onResume = { onAction(RecoverAction.Resume) }) }
            }
            if (state.pinned.isNotEmpty()) {
                item(
                    key = "pinned-title",
                ) { SectionTitle(stringResource(Res.string.recover_pinned), modifier = Modifier.padding(start = 16.dp)) }
                state.pinned.forEach { plan ->
                    item(key = "pinned:${plan.id.value}") {
                        PinnedCard(plan, onStart = { onAction(RecoverAction.Start(plan.id)) }, onOpen = { onOpenSession(plan.id) })
                    }
                }
            }
            if (state.areas.isNotEmpty()) {
                item(key = "areas") { AreaChips(state, onAction) }
            }
            if (state.shown.isNotEmpty()) {
                item(key = "sessions-title") {
                    SectionTitle(
                        state.area?.let { stringResource(it.label) } ?: stringResource(Res.string.recover_sessions),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                itemsIndexed(state.shown, key = { _, plan -> "plan:${plan.id.value}" }) { index, plan ->
                    SessionRow(
                        plan = plan,
                        index = index,
                        count = state.shown.size,
                        onStart = { onAction(RecoverAction.Start(plan.id)) },
                        onOpen = { onOpenSession(plan.id) },
                    )
                }
            }
        }
    }
}

// The session that's running, first, so leaving the player never loses it.
@Composable
private fun RunningCard(
    running: GuidedState,
    onResume: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val snapshot = running.snapshot ?: return
    val count = running.run?.items?.size ?: 0
    Surface(
        onClick = onResume,
        shape = RoundedCornerShape(28.dp),
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOf(
                        stringResource(Res.string.recover_running),
                        stringResource(Res.string.workout_position, minOf(snapshot.index + 1, count), count),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(snapshot.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = onResume,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                modifier = Modifier.height(48.dp),
            ) { Text(stringResource(Res.string.recover_resume)) }
        }
    }
}

// The hero card, one per pinned session.
@Composable
private fun PinnedCard(
    plan: GuidedPlan,
    onStart: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
    ) {
        Column(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                plan.title,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = LocalAppFonts.current.title,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(planMeta(plan), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onStart,
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
            ) {
                Icon(painterResource(Res.drawable.ic_play_large), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(
                    stringResource(Res.string.recover_start),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
        }
    }
}

// Large chips, only for areas some session helps.
@Composable
private fun AreaChips(
    state: RecoverUiState,
    onAction: (RecoverAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        state.areas.forEach { area ->
            val selected = area == state.area
            Text(
                stringResource(area.label),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) colors.onPrimary else colors.onSurface,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) colors.primary else colors.surfaceContainerHigh)
                        .selectable(selected = selected, role = Role.Tab) { onAction(RecoverAction.SelectArea(area)) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SessionRow(
    plan: GuidedPlan,
    index: Int,
    count: Int,
    onStart: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp),
            ) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        plan.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(planMeta(plan), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                }
                FilledTonalIconButton(onClick = onStart, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(Res.drawable.ic_play_large),
                        contentDescription = stringResource(Res.string.recover_start_named, plan.title),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
