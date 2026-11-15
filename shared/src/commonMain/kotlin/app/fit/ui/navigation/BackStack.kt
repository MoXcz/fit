// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

// Non-Android targets can't serialize NavKey reflectively, so every route is registered here; saving
// a stack that holds one that isn't throws.
internal val navConfig =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(Route.Today::class, Route.Today.serializer())
                    subclass(Route.Train::class, Route.Train.serializer())
                    subclass(Route.Recover::class, Route.Recover.serializer())
                    subclass(Route.More::class, Route.More.serializer())
                    subclass(Route.Program::class, Route.Program.serializer())
                    subclass(Route.BodyWeight::class, Route.BodyWeight.serializer())
                    subclass(Route.Routine::class, Route.Routine.serializer())
                    subclass(Route.Exercise::class, Route.Exercise.serializer())
                    subclass(Route.Lesson::class, Route.Lesson.serializer())
                    subclass(Route.ExerciseEdit::class, Route.ExerciseEdit.serializer())
                    subclass(Route.Workout::class, Route.Workout.serializer())
                    subclass(Route.Player::class, Route.Player.serializer())
                    subclass(Route.RecoverySession::class, Route.RecoverySession.serializer())
                    subclass(Route.Settings::class, Route.Settings.serializer())
                    subclass(Route.Import::class, Route.Import.serializer())
                    subclass(Route.Data::class, Route.Data.serializer())
                    subclass(Route.Help::class, Route.Help.serializer())
                    subclass(Route.About::class, Route.About.serializer())
                    subclass(Route.History::class, Route.History.serializer())
                    subclass(Route.Summary::class, Route.Summary.serializer())
                }
            }
    }

// The saved back stack, or a fresh one on Today when it can't be read: an update may have removed a
// route it holds (0.0.6 dropped Routines and Exercises), and that must not crash the app on start.
internal class BackStackOrHome(
    private val saved: KSerializer<NavBackStack<NavKey>> = NavBackStackSerializer(PolymorphicSerializer(NavKey::class)),
) : KSerializer<NavBackStack<NavKey>> by saved {
    override fun deserialize(decoder: Decoder): NavBackStack<NavKey> =
        try {
            saved.deserialize(decoder)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: an unknown route, or a route whose fields changed.
            NavBackStack(Route.Today)
        }
}

@Composable
internal fun rememberBackStack(): NavBackStack<NavKey> =
    rememberSerializable(serializer = BackStackOrHome(), configuration = navConfig) { NavBackStack(Route.Today) }
