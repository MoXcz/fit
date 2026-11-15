// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Stands for 0.0.5's Routines route, which 0.0.6 removed.
@Serializable
@SerialName("app.fit.ui.navigation.Route.Routines")
private data object OldRoutines : NavKey

class BackStackTest {
    private val oldConfig =
        SavedStateConfiguration {
            serializersModule =
                SerializersModule {
                    polymorphic(NavKey::class) {
                        subclass(Route.Today::class, Route.Today.serializer())
                        subclass(Route.Train::class, Route.Train.serializer())
                        subclass(OldRoutines::class, OldRoutines.serializer())
                    }
                }
        }

    @Test
    fun `a saved back stack with a removed route comes back as Today`() {
        val saved = encodeToSavedState(BackStackOrHome(), NavBackStack<NavKey>(Route.Today, Route.Train, OldRoutines), oldConfig)
        assertEquals(listOf<NavKey>(Route.Today), decodeFromSavedState(BackStackOrHome(), saved, navConfig).toList())
    }

    // One of each route, found by reflection, so a route added later is covered without touching this.
    private fun everyRoute(): List<Route> =
        Route::class.java.permittedSubclasses.map { type ->
            val instance = type.declaredFields.firstOrNull { it.name == "INSTANCE" }?.get(null)
            instance as? Route ?: run {
                val constructor = type.declaredConstructors.single { !it.isSynthetic && it.parameterCount > 0 }
                val args =
                    constructor.parameterTypes.map<Class<*>, Any> {
                        when (it) {
                            Long::class.javaPrimitiveType -> 4L
                            String::class.java -> "2026-11-09"
                            else -> error("give BackStackTest a value for ${type.simpleName}'s ${it.simpleName}")
                        }
                    }
                constructor.newInstance(*args.toTypedArray()) as Route
            }
        }

    @Test
    fun `a readable back stack comes back as it was, whichever routes it holds`() {
        val routes = everyRoute()
        assertTrue(routes.containsAll(listOf(Route.Today, Route.Lesson(4), Route.Summary("2026-11-09"))), "$routes")
        routes.forEach { route ->
            val stack = NavBackStack<NavKey>(Route.Today, route)
            val saved = encodeToSavedState(BackStackOrHome(), stack, navConfig)
            assertEquals(stack.toList(), decodeFromSavedState(BackStackOrHome(), saved, navConfig).toList())
        }
    }
}
