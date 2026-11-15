// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {
    @Test
    fun `selecting a tab puts it on top of Today`() {
        val stack = mutableListOf<NavKey>(Route.Today)
        stack.selectTopLevel(Route.Train)
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.Train), stack)
    }

    @Test
    fun `switching between tabs keeps only Today below`() {
        val stack = mutableListOf<NavKey>(Route.Today, Route.Train)
        stack.selectTopLevel(Route.Recover)
        stack.selectTopLevel(Route.More)
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.More), stack)
    }

    @Test
    fun `selecting Today goes back to home`() {
        val stack = mutableListOf<NavKey>(Route.Today, Route.Recover)
        stack.selectTopLevel(Route.Today)
        assertEquals<List<NavKey>>(listOf(Route.Today), stack)
    }

    @Test
    fun `selecting the current tab changes nothing`() {
        val stack = mutableListOf<NavKey>(Route.Today, Route.Train)
        stack.selectTopLevel(Route.Train)
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.Train), stack)
    }

    @Test
    fun `a lesson opened from the exercise it was opened from goes back instead of piling up`() {
        val stack = mutableListOf<NavKey>(Route.Today, Route.Train, Route.Lesson(1), Route.Exercise(5))
        stack.openOrReturn(Route.Lesson(1))
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.Train, Route.Lesson(1)), stack)
        stack.openOrReturn(Route.Exercise(5))
        stack.openOrReturn(Route.Lesson(1))
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.Train, Route.Lesson(1)), stack)
        // Another lesson is a new page.
        stack.openOrReturn(Route.Exercise(5))
        stack.openOrReturn(Route.Lesson(2))
        assertEquals<List<NavKey>>(listOf(Route.Today, Route.Train, Route.Lesson(1), Route.Exercise(5), Route.Lesson(2)), stack)
    }

    @Test
    fun `only the four tabs show the toolbar`() {
        assertEquals(listOf(true, true, true, true), topLevelRoutes.map { it.isTopLevel() })
        listOf(Route.Workout(1), Route.Routine(1), Route.Exercise(1), Route.ExerciseEdit(1), Route.Settings, null).forEach {
            assertEquals(false, it.isTopLevel(), "$it")
        }
    }
}
