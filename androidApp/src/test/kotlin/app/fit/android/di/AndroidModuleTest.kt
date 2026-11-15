// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.di

import android.content.Context
import app.fit.di.sharedModules
import app.fit.routines.ui.PickerTarget
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test

class AndroidModuleTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `shared and Android modules have all their dependencies`() {
        // Context comes from androidContext(); ids, dates and the picker's target are parameters; the
        // time zone is a factory of the shared core module.
        module { includes(sharedModules + androidModule) }.verify(
            extraTypes = listOf(Context::class, CoroutineDispatcher::class, Long::class, String::class, PickerTarget::class),
        )
    }
}
