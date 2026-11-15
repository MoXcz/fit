// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.history.ui.HistoryViewModel
import app.fit.history.ui.SummaryViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val historyModule =
    module {
        viewModelOf(::HistoryViewModel)
        // The day's ISO date is a parameter.
        viewModelOf(::SummaryViewModel)
    }
