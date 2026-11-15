// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MovedTest {
    @Test
    fun `an item moves several places and stops at either end`() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), list.moved(2, -2))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 9))
        assertEquals(list, list.moved(1, 0))
        assertEquals(list, list.moved(7, 1))
    }
}
