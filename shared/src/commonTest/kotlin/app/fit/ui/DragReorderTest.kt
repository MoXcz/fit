// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragReorderTest {
    // Two sections: Warm-up (w1, w2) and Main (a, b, c), rows 100 px tall with a 2 px gap.
    private val rows = listOf("w1", "w2", "a", "b", "c").mapIndexed { i, key -> RowSpan(key, i * 102, 100) }
    private val main = listOf("a", "b", "c")

    @Test
    fun `a drop past two rows moves two places, and the rows it passed make room until it arrives`() {
        val drag = DragReorder()
        drag.begin("a", main, rows)
        drag.dragTo(160f)
        assertEquals(-102f, drag.translation("b"))
        drag.dragTo(260f)
        assertEquals(-102f, drag.translation("c"))
        assertEquals("a" to 2, drag.end())
        // Still the old order: everything stays where it was dropped.
        drag.follow(listOf("w1", "w2", "a", "b", "c"))
        assertEquals(204f, drag.translation("a"))
        // The new order arrived: no offsets left.
        drag.follow(listOf("w1", "w2", "b", "c", "a"))
        assertEquals(0f, drag.translation("a"))
        assertEquals(0f, drag.translation("b"))
        assertNull(drag.dragged)
    }

    @Test
    fun `a short drag is no move and leaves nothing behind`() {
        val drag = DragReorder()
        drag.begin("b", main, rows)
        drag.dragTo(30f)
        assertNull(drag.end())
        assertNull(drag.dragged)
        assertEquals(0f, drag.translation("b"))
    }

    @Test
    fun `a cancelled drag moves nothing`() {
        val drag = DragReorder()
        drag.begin("c", main, rows)
        drag.dragTo(-150f)
        drag.cancel()
        assertNull(drag.dragged)
        assertEquals(0f, drag.translation("b"))
        assertNull(drag.end())
    }

    @Test
    fun `a row stays in its section, however far it's dragged`() {
        val drag = DragReorder()
        drag.begin("b", main, rows)
        drag.dragTo(-1000f)
        // Stopped at Main's top: never over the warm-up rows.
        assertEquals(-102f, drag.translation("b"))
        assertEquals(0f, drag.translation("w2"))
        assertEquals("b" to -1, drag.end())
    }

    @Test
    fun `a change other than the drop clears the offsets too`() {
        val drag = DragReorder()
        drag.begin("a", main, rows)
        drag.dragTo(160f)
        drag.end()
        drag.follow(listOf("w1", "w2", "a", "c"))
        assertNull(drag.dragged)
    }
}
