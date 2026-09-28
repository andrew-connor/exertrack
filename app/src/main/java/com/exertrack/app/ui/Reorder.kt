package com.exertrack.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Press-and-hold drag reordering for a LazyColumn, with no library. Usage:
 *
 *     val listState = rememberLazyListState()
 *     val reorder = rememberReorderState(listState) { fromKey, toKey -> move the item with fromKey to toKey's place }
 *     LazyColumn(state = listState) {
 *         items(rows, key = { it.id }) { row ->
 *             Card(Modifier.reorderable(reorder, row.id).then(if (reorder.isDragging(row.id)) Modifier else Modifier.animateItem()))
 *         }
 *     }
 *
 * The dragged item follows the finger (a translation on top of its laid-out position). Items it
 * passes are moved through [onMove] as soon as the dragged item's centre crosses their centre,
 * and the list scrolls by itself while the item is held near the edge it is being moved towards.
 * Only items carrying [reorderable] take part; other items (headers, footers) are neither
 * draggable nor drop targets. A long press that is released without moving does nothing, not
 * even the item's own click.
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
) {
    private companion object {
        /** Distance from a viewport edge, in px, within which dragging starts to scroll the list. */
        const val EDGE_PX = 140f
        /** Fastest automatic scroll, in px per frame. */
        const val MAX_SCROLL_PX = 28f
    }

    /** Keys of items that currently carry [reorderable]. Not observed; read during gestures only. */
    internal val keys = HashSet<Any>()

    /** The key being dragged, or null. Observable so the item can restyle itself. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var initialOffset = 0
    private var scrollSpeed = 0f
    private var autoScroll: Job? = null

    fun isDragging(key: Any): Boolean = draggingKey == key

    /** Vertical translation, in px, to draw [key] at this frame. Zero for everything but the dragged item. */
    fun translationFor(key: Any): Float {
        if (key != draggingKey) return 0f
        val info = itemInfo(key) ?: return 0f
        return draggedDistance + initialOffset - info.offset
    }

    /** Begin dragging [key]. False when another drag is already in progress or the item is not laid out. */
    internal fun start(key: Any): Boolean {
        if (draggingKey != null) return false
        val info = itemInfo(key) ?: return false
        draggingKey = key
        initialOffset = info.offset
        draggedDistance = 0f
        return true
    }

    internal fun drag(key: Any, dy: Float) {
        if (draggingKey != key) return
        draggedDistance += dy
        settle()
    }

    internal fun end(key: Any) {
        if (draggingKey != key) return
        draggingKey = null
        draggedDistance = 0f
        stopAutoScroll()
    }

    // ---- internals ----

    private fun itemInfo(key: Any): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /**
     * Move past neighbours the dragged item has crossed and keep the edges scrolling. The
     * direction is where the card is relative to its laid-out slot, which also covers movement
     * caused by auto-scrolling while the finger holds still.
     */
    private fun settle() {
        val key = draggingKey ?: return
        val info = itemInfo(key) ?: return
        val top = initialOffset + draggedDistance
        val bottom = top + info.size
        val centre = top + info.size / 2f
        val displacement = top - info.offset
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            if (other.key == key || other.key !in keys) return@firstOrNull false
            val otherCentre = other.offset + other.size / 2f
            when {
                // Moving down: an item below us whose centre we have passed.
                displacement > 0f -> other.offset > info.offset && centre > otherCentre && centre < other.offset + other.size
                // Moving up: an item above us whose centre we have passed.
                displacement < 0f -> other.offset < info.offset && centre < otherCentre && centre >= other.offset
                else -> false
            }
        }
        if (target != null) onMove(key, target.key)
        updateAutoScroll(top, bottom, displacement)
    }

    private fun updateAutoScroll(top: Float, bottom: Float, displacement: Float) {
        val layout = listState.layoutInfo
        val viewportTop = layout.viewportStartOffset.toFloat()
        val viewportBottom = layout.viewportEndOffset.toFloat()
        scrollSpeed = when {
            // Only towards the edge the item is being moved to, so picking up the bottom card and
            // moving it upwards never scrolls the list away underneath it.
            displacement < 0f && top < viewportTop + EDGE_PX ->
                -((viewportTop + EDGE_PX - top).coerceAtMost(EDGE_PX) / EDGE_PX) * MAX_SCROLL_PX
            displacement > 0f && bottom > viewportBottom - EDGE_PX ->
                ((bottom - (viewportBottom - EDGE_PX)).coerceAtMost(EDGE_PX) / EDGE_PX) * MAX_SCROLL_PX
            else -> 0f
        }
        if (scrollSpeed == 0f) {
            stopAutoScroll()
            return
        }
        if (autoScroll?.isActive == true) return
        autoScroll = scope.launch {
            while (isActive && draggingKey != null && scrollSpeed != 0f) {
                val moved = listState.scrollBy(scrollSpeed)
                if (moved == 0f) break // at the end of the list
                settle()
                delay(16)
            }
        }
    }

    private fun stopAutoScroll() {
        scrollSpeed = 0f
        autoScroll?.cancel()
        autoScroll = null
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val latestOnMove by rememberUpdatedState(onMove)
    return remember(listState, scope) { ReorderState(listState, scope) { from, to -> latestOnMove(from, to) } }
}

/**
 * Makes a LazyColumn item draggable after a long press. Apply it to the item's outermost
 * composable. While [enabled] is false the item neither drags nor acts as a drop target.
 */
@Composable
fun Modifier.reorderable(state: ReorderState, key: Any, enabled: Boolean = true): Modifier {
    DisposableEffect(state, key, enabled) {
        if (enabled) state.keys += key
        onDispose { state.keys -= key }
    }
    val haptics = LocalHapticFeedback.current
    val dragging = state.isDragging(key)
    // The pointer handler sits outside the graphics layer so its coordinates are unaffected by
    // the lift scale below; the layer only changes how the item is drawn.
    val gesture = if (!enabled) Modifier else Modifier.pointerInput(state, key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!state.start(key)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var lastY = press.position.y
            while (true) {
                // The initial pass runs before the item's own click handler sees the event, so
                // consuming here keeps a long press (moved or not) from also counting as a tap.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    change.consume()
                    break
                }
                if (change.positionChangedIgnoreConsumed()) {
                    change.consume()
                    val y = change.position.y
                    state.drag(key, y - lastY)
                    lastY = y
                }
            }
            state.end(key)
        }
    }
    return this
        .zIndex(if (dragging) 1f else 0f)
        .then(gesture)
        .graphicsLayer {
            translationY = state.translationFor(key)
            if (dragging) {
                shadowElevation = 12.dp.toPx()
                scaleX = 1.02f
                scaleY = 1.02f
            }
        }
}
