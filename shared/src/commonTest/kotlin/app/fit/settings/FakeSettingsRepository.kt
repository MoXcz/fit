// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings

import app.fit.core.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class FakeSettingsRepository(
    initial: Settings = Settings(),
) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    override suspend fun setWeightUnit(unit: WeightUnit) {
        settings.update { it.copy(weightUnit = unit) }
    }

    override suspend fun setBarWeight(
        unit: WeightUnit,
        weight: Double,
    ) {
        settings.update { if (unit == WeightUnit.Kg) it.copy(barKg = weight) else it.copy(barLb = weight) }
    }

    override suspend fun setAlarm(on: Boolean) {
        settings.update { it.copy(alarm = on) }
    }

    override suspend fun setKeepScreenOn(on: Boolean) {
        settings.update { it.copy(keepScreenOn = on) }
    }

    override suspend fun setAutoAdvance(on: Boolean) {
        settings.update { it.copy(autoAdvance = on) }
    }

    override suspend fun replace(settings: Settings) {
        this.settings.value = settings
    }
}
