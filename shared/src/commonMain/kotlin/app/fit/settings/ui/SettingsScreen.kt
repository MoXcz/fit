// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.core.WeightUnit
import app.fit.resources.Res
import app.fit.resources.settings_alarm
import app.fit.resources.settings_alarm_summary
import app.fit.resources.settings_auto_advance
import app.fit.resources.settings_auto_advance_summary
import app.fit.resources.settings_bar
import app.fit.resources.settings_bar_kg
import app.fit.resources.settings_bar_lb
import app.fit.resources.settings_keep_screen_on
import app.fit.resources.settings_keep_screen_on_summary
import app.fit.resources.settings_title
import app.fit.resources.settings_unit
import app.fit.resources.settings_unit_summary
import app.fit.resources.settings_units
import app.fit.resources.settings_workout
import app.fit.settings.canStepBar
import app.fit.ui.BackButton
import app.fit.ui.ChoiceRow
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.Stepper
import app.fit.ui.formatNumber
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    // Background first, so it also fills the space the caller pads out for the bottom bar.
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.settings_title)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SectionTitle(stringResource(Res.string.settings_workout))
            SwitchRow(
                label = stringResource(Res.string.settings_alarm),
                supporting = stringResource(Res.string.settings_alarm_summary),
                checked = settings.alarm,
                onChange = { onAction(SettingsAction.SetAlarm(it)) },
                index = 0,
                count = 3,
            )
            SwitchRow(
                label = stringResource(Res.string.settings_keep_screen_on),
                supporting = stringResource(Res.string.settings_keep_screen_on_summary),
                checked = settings.keepScreenOn,
                onChange = { onAction(SettingsAction.SetKeepScreenOn(it)) },
                index = 1,
                count = 3,
            )
            SwitchRow(
                label = stringResource(Res.string.settings_auto_advance),
                supporting = stringResource(Res.string.settings_auto_advance_summary),
                checked = settings.autoAdvance,
                onChange = { onAction(SettingsAction.SetAutoAdvance(it)) },
                index = 2,
                count = 3,
            )

            SectionTitle(stringResource(Res.string.settings_units))
            ChoiceRow(
                label = stringResource(Res.string.settings_unit),
                supporting = stringResource(Res.string.settings_unit_summary),
                options = WeightUnit.entries,
                selected = settings.weightUnit,
                optionLabel = { it.symbol },
                onSelect = { onAction(SettingsAction.SetUnit(it)) },
                index = 0,
                count = 1,
            )

            SectionTitle(stringResource(Res.string.settings_bar))
            listOf(WeightUnit.Kg to Res.string.settings_bar_kg, WeightUnit.Lb to Res.string.settings_bar_lb).forEachIndexed {
                index,
                (unit, label),
                ->
                ListItem(
                    modifier = Modifier.clip(segmentShape(index, 2)),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    headlineContent = { Text(stringResource(label)) },
                    trailingContent = {
                        Stepper(
                            value = "${formatNumber(settings.bar(unit))} ${unit.symbol}",
                            canDecrease = settings.canStepBar(unit, up = false),
                            canIncrease = settings.canStepBar(unit, up = true),
                            onStep = { up -> onAction(SettingsAction.StepBar(unit, up)) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    ListItem(
        modifier = Modifier.clip(segmentShape(index, count)).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        headlineContent = { Text(label) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    FitTheme { SettingsScreen(state = SettingsUiState(loading = false), onAction = {}, onBack = {}) }
}
