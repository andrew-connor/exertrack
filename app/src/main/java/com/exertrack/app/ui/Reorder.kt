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
import androidx.compose.runtime.withFrameNanos
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
 * On release the item glides into its slot. Only items carrying [reorderable] take part; other
 * items (headers, footers) are neither draggable nor drop targets. A long press that is released
 * without moving does nothing, not even the item's own click.
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
        /** How long a requested move may take to show up in the layout before we stop waiting for it. */
        const val MOVE_SETTLE_MS = 160L
        /** Duration of the glide into place after the finger lifts. */
        const val DROP_MS = 180L
    }

    /** Keys of items that currently carry [reorderable]. Not observed; read during gestures only. */
    internal val keys = HashSet<Any>()

    /** The key being dragged (or still gliding into its slot after a drop), or null. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    private var draggedDistance by mutableFloatStateOf(0f)
    private var initialOffset = 0
    /**
     * Where the finger took hold, measured from the top of the item's laid-out slot. Pointer
     * positions arrive in the item's own coordinates, and that frame jumps by a whole slot every
     * time a move lands, so the card's place is always recomputed from the finger's position
     * within the list rather than accumulated from deltas.
     */
    private var grabY = 0f
    private var scrollSpeed = 0f
    private var autoScroll: Job? = null
    /** Runs while a released item glides into its slot. */
    private var drop: Job? = null
    /**
     * Layout index the dragged item will occupy once the last [onMove] has been recomposed and
     * measured, with the time it was requested. Until the layout shows it (or the wait runs
     * out) no further move is requested, so two pointer events in one frame cannot ask for the
     * same move twice and swap the items straight back.
     */
    private var pendingIndex: Int? = null
    private var pendingSince = 0L

    fun isDragging(key: Any): Boolean = draggingKey == key

    /** Vertical translation, in px, to draw [key] at this frame. Zero for everything but the dragged item. */
    fun translationFor(key: Any): Float {
        if (key != draggingKey) return 0f
        val info = itemInfo(key) ?: return 0f
        return draggedDistance + initialOffset - info.offset
    }

    /**
     * Begin dragging [key]. False when another drag is in progress or the item is not laid out.
     * An item still gliding home after a drop can be picked up again from where it is drawn.
     */
    internal fun start(key: Any, pressY: Float): Boolean {
        val info = itemInfo(key) ?: return false
        val current = draggingKey
        if (current != null) {
            if (drop == null) return false
            drop?.cancel()
            drop = null
            if (current == key) {
                // Picked up again mid-glide: keep the card where it is drawn, so the grab point
                // is taken relative to the drawn position rather than the laid-out slot.
                val translation = draggedDistance + initialOffset - info.offset
                grabY = pressY - translation
                initialOffset = info.offset
                draggedDistance = translation
                pendingIndex = null
                return true
            }
            draggingKey = null
        }
        draggingKey = key
        initialOffset = info.offset
        grabY = pressY
        draggedDistance = 0f
        pendingIndex = null
        return true
    }

    /** The finger is at [localY] in the item's current coordinate frame. */
    internal fun drag(key: Any, localY: Float) {
        if (draggingKey != key || drop != null) return
        val info = itemInfo(key) ?: return
        // Finger position within the list, then the card's top from the grab point.
        val fingerInList = localY + info.offset
        draggedDistance = fingerInList - grabY - initialOffset
        settle()
    }

    /** The finger lifted (or the gesture was cancelled): glide the item into its slot, then let go of it. */
    internal fun end(key: Any) {
        if (draggingKey != key || drop != null) return
        stopAutoScroll()
        drop = scope.launch {
            try {
                val startDistance = draggedDistance
                val startedAt = withFrameNanos { it }
                var t = 0f
                while (t < 1f) {
                    val now = withFrameNanos { it }
                    t = ((now - startedAt) / 1_000_000f / DROP_MS).coerceIn(0f, 1f)
                    // The slot is re-read every frame so a move that lands mid-glide is followed.
                    val slot = itemInfo(key)?.let { (it.offset - initialOffset).toFloat() } ?: break
                    val eased = 1f - (1f - t) * (1f - t)
                    draggedDistance = startDistance + (slot - startDistance) * eased
                }
            } finally {
                if (draggingKey == key) {
                    draggingKey = null
                    draggedDistance = 0f
                    pendingIndex = null
                }
                drop = null
            }
        }
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
        val info = itemInfo(key) ?: run {
            // The item is no longer laid out (its slot scrolled out of the viewport): nothing to
            // track, so do not run the list on to its end.
            stopAutoScroll()
            return
        }
        val top = initialOffset + draggedDistance
        val bottom = top + info.size
        val centre = top + info.size / 2f
        val displacement = top - info.offset

        val waiting = pendingIndex
        if (waiting != null) {
            if (info.index == waiting || System.currentTimeMillis() - pendingSince > MOVE_SETTLE_MS) pendingIndex = null
        }
        if (pendingIndex == null) {
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
            if (target != null) {
                pendingIndex = target.index
                pendingSince = System.currentTimeMillis()
                onMove(key, target.key)
            }
        }
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
            while (isActive && draggingKey != null && drop == null && scrollSpeed != 0f) {
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
            if (!state.start(key, press.position.y)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            try {
                while (true) {
                    // The initial pass runs before the item's own click handler sees the event,
                    // so consuming here keeps a long press (moved or not) from also counting as a tap.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        break
                    }
                    if (change.positionChangedIgnoreConsumed()) {
                        change.consume()
                        state.drag(key, change.position.y)
                    }
                }
            } finally {
                // Also reached when the gesture is cancelled from outside (the item left the
                // viewport, the screen was left): the drag must never stay stuck on.
                state.end(key)
            }
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
