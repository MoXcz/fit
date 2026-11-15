// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    // The day's workout. Home: the other tabs sit on top of it.
    @Serializable
    data object Today : Route

    // Routines, exercises, lessons and programs, switched at the top.
    @Serializable
    data object Train : Route

    // Recovery sessions by body area.
    @Serializable
    data object Recover : Route

    // History, body weight, settings, import and export.
    @Serializable
    data object More : Route

    // The program editor. Sits on top of Train.
    @Serializable
    data class Program(
        val id: Long,
    ) : Route

    // Body weight readings. Sits on top of More.
    @Serializable
    data object BodyWeight : Route

    // The routine editor. Sits on top of Train.
    @Serializable
    data class Routine(
        val id: Long,
    ) : Route

    @Serializable
    data class Exercise(
        val id: Long,
    ) : Route

    // A lesson's page. Sits on top of Train or an exercise's page.
    @Serializable
    data class Lesson(
        val id: Long,
    ) : Route

    @Serializable
    data class ExerciseEdit(
        val id: Long,
    ) : Route

    // The focus view: a day's workout one exercise at a time, opened at this one. Sits on top of
    // Today and hides the bar.
    @Serializable
    data class Workout(
        val blockId: Long,
    ) : Route

    // The guided player: the running recovery session, lesson drill or warm-up, one step at a time.
    // Hides the bar.
    @Serializable
    data object Player : Route

    // A recovery session's page. Sits on top of Recover.
    @Serializable
    data class RecoverySession(
        val id: Long,
    ) : Route

    // Sits on top of More.
    @Serializable
    data object Settings : Route

    // Import content: choose or receive a file, preview it, import it. Sits on top of More. [file]
    // is the inbox id of a file shared with the app, empty when opened from More.
    @Serializable
    data class Import(
        val file: String,
    ) : Route

    // Export and restore a backup, and the wipes. Sits on top of More.
    @Serializable
    data object Data : Route

    // The user guide. Sits on top of More.
    @Serializable
    data object Help : Route

    // Version, licence and credits. Sits on top of More.
    @Serializable
    data object About : Route

    // The calendar, counts and past workouts. Sits on top of More.
    @Serializable
    data object History : Route

    // A day's result, records, notes and fatigue; [date] is ISO. Sits on top of Today or History.
    @Serializable
    data class Summary(
        val date: String,
    ) : Route
}

// The four tabs: the only screens that show the toolbar.
val topLevelRoutes: List<Route> = listOf(Route.Today, Route.Train, Route.Recover, Route.More)

internal fun NavKey?.isTopLevel(): Boolean = this in topLevelRoutes

// Today stays at the bottom of the stack, so Back from any other tab returns to it and Back from
// Today leaves the app.
internal fun MutableList<NavKey>.selectTopLevel(route: Route) {
    if (lastOrNull() == route) return
    retainAll { it == Route.Today }
    if (route != Route.Today) add(route)
}

// A lesson and its exercise link to each other: opening the one the top screen was opened from goes
// back to it, so the two never pile up and Back leaves them.
internal fun MutableList<NavKey>.openOrReturn(route: Route) {
    if (getOrNull(lastIndex - 1) == route) removeLastOrNull() else add(route)
}
