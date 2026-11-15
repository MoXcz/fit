// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

import kotlinx.coroutines.CoroutineDispatcher

data class AppDispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)
