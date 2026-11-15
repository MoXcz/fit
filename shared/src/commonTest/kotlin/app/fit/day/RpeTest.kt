// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RpeTest {
    @Test
    fun `RPE runs from 5 to 10 in steps of a half, and none is allowed`() {
        assertEquals(11, Rpe.values.size)
        assertEquals(5.0, Rpe.values.first())
        assertEquals(10.0, Rpe.values.last())
        assertTrue(Rpe.values.zipWithNext().all { (a, b) -> b - a == 0.5 })
        assertTrue(Rpe.isValid(null))
        assertTrue(Rpe.isValid(8.5))
        assertFalse(Rpe.isValid(8.25))
        assertFalse(Rpe.isValid(4.5))
        assertFalse(Rpe.isValid(10.5))
    }
}
