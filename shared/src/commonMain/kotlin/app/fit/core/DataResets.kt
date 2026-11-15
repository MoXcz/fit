// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Counts the wipes and restores of this process. What a screen kept from before one (an Undo offer
// holding rows and their ids) must not be written back after it.
class DataResets {
    private val count = MutableStateFlow(0)

    val epoch: StateFlow<Int> = count.asStateFlow()

    fun bump() = count.update { it + 1 }
}
