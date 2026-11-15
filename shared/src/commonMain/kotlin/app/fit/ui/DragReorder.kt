// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import app.fit.core.moved

// A row as laid out when a drag starts.
data class RowSpan(
    val key: Any,
    val top: Int,
    val height: Int,
)

// Drag to reorder in a LazyColumn with Compose foundation alone: the dragged row follows the finger,
// the rows it passes make room, and the move is written once, on release. Rows only move within their
// group (a section), the way the repositories move them.
@Stable
internal class DragReorder {
    var dragged: Any? by mutableStateOf(null)
        private set
    private var offset by mutableFloatStateOf(0f)

    // Set when a drag starts: the group's visible rows in order.
    private var group: List<RowSpan> = emptyList()

    // After a drop: the group's order before and the order it should arrive in.
    private var before: List<Any> = emptyList()
    private var expected: List<Any>? = null

    // [keys]: the dragged row's group in order; [visible]: what the list has laid out.
    fun begin(
        key: Any,
        keys: List<Any>,
        visible: List<RowSpan>,
    ) {
        val byKey = visible.associateBy { it.key }
        group = keys.mapNotNull { byKey[it] }
        before = group.map { it.key }
        expected = null
        offset = 0f
        dragged = key.takeIf { it in before }
    }

    // [by]: how far the finger is from where it went down.
    fun dragTo(by: Float) {
        val key = dragged ?: return
        if (expected != null) return
        val span = span(key)
        val last = group.last()
        offset = by.coerceIn((group.first().top - span.top).toFloat(), (last.top + last.height - span.height - span.top).toFloat())
    }

    private fun span(key: Any) = group.first { it.key == key }

    private val from: Int get() = before.indexOf(dragged)

    // Where the dragged row would land: past every row above whose middle its top has crossed, or
    // below whose middle its bottom has.
    private val to: Int
        get() {
            val key = dragged ?: return -1
            val top = span(key).top + offset
            val bottom = top + span(key).height

            fun middle(row: RowSpan) = row.top + row.height / 2f
            val above = group.take(from).count { top < middle(it) }
            val below = group.drop(from + 1).count { bottom > middle(it) }
            return from - above + below
        }

    // The room a row makes: the dragged row's height plus the gap below it.
    private val room: Float
        get() {
            val key = dragged ?: return 0f
            val gap = if (group.size > 1) group[1].top - group[0].top - group[0].height else 0
            return (span(key).height + gap).toFloat()
        }

    fun translation(key: Any): Float {
        val dragging = dragged ?: return 0f
        if (key == dragging) return offset
        val index = before.indexOf(key)
        if (index < 0) return 0f
        return when {
            from < to && index in (from + 1)..to -> -room
            from > to && index in to until from -> room
            else -> 0f
        }
    }

    // The dragged key and how far it moved (positive is down), or null for no move.
    fun end(): Pair<Any, Int>? {
        val key = dragged ?: return null
        val steps = to - from
        if (steps == 0) {
            cancel()
            return null
        }
        // The rows stay where they were dropped until the list comes back in this order.
        expected = before.moved(from, steps)
        return key to steps
    }

    fun cancel() {
        dragged = null
        offset = 0f
        expected = null
    }

    // Called with the list's order on every composition, before the rows: once the drop has arrived
    // (or the group changed some other way) the rows draw at their new places with nothing left over.
    fun follow(order: List<Any>) {
        expected ?: return
        val now = order.filter { it in before }
        if (now != before) cancel()
    }
}

@Composable
internal fun rememberDragReorder(order: List<Any>): DragReorder {
    val reorder = remember { DragReorder() }
    reorder.follow(order)
    return reorder
}

// On a row: lifts the dragged row above the others and moves the rows it passes.
internal fun Modifier.reorderable(
    reorder: DragReorder,
    key: Any,
): Modifier =
    zIndex(if (reorder.dragged == key) 1f else 0f)
        .graphicsLayer { translationY = reorder.translation(key) }

// On a row's handle: [keys] is the row's group in order; [onMove] gets the key and the steps moved.
@Composable
internal fun Modifier.dragHandle(
    reorder: DragReorder,
    list: LazyListState,
    key: Any,
    keys: List<Any>,
    onMove: (Any, Int) -> Unit,
): Modifier {
    // Where the handle is, so a drag is measured in the window: the handle moves with the row, so its
    // own coordinates would follow the finger and report no movement.
    val handle = remember { HandlePosition() }
    return onGloballyPositioned { handle.coordinates = it }
        .pointerInput(key, keys) {
            var from = 0f

            fun windowY(position: Offset): Float = handle.coordinates?.localToWindow(position)?.y ?: position.y
            detectVerticalDragGestures(
                onDragStart = {
                    from = windowY(it)
                    val visible = list.layoutInfo.visibleItemsInfo.map { item -> RowSpan(item.key, item.offset, item.size) }
                    reorder.begin(key, keys, visible)
                },
                onVerticalDrag = { change, _ ->
                    change.consume()
                    reorder.dragTo(windowY(change.position) - from)
                },
                onDragEnd = { reorder.end()?.let { (moved, steps) -> onMove(moved, steps) } },
                onDragCancel = { reorder.cancel() },
            )
        }
}

private class HandlePosition {
    var coordinates: LayoutCoordinates? = null
}
