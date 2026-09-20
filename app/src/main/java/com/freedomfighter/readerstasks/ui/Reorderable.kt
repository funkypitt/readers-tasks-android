package com.freedomfighter.readerstasks.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A lazy list whose first [reorderableCount] rows can be dragged after a long press. A long
 * press that ends without moving is reported through [onLongPress] (a menu, typically).
 * The gesture is handled in the Initial pass so rows and the list's scrolling never steal it.
 * Extra content (a "done" section) is appended through [tail].
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    onLongPress: (T) -> Unit,
    row: @Composable (item: T, dragging: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    tail: LazyListScope.() -> Unit = {}
) {
    val listState: LazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    val colors = LocalColors.current
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf(items) }
    if (dragIndex < 0 && working !== items) working = items

    LazyColumn(
        state = listState,
        userScrollEnabled = dragIndex < 0,
        contentPadding = contentPadding,
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val deadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                while (true) {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    if (remaining <= 0) break
                    val event = withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) } ?: break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
                }
                val info0 = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { down.position.y.toInt() in it.offset..(it.offset + it.size) } ?: return@awaitEachGesture
                if (info0.index >= working.size) return@awaitEachGesture   // tail rows are not draggable
                tick()
                dragIndex = info0.index; dragOffset = 0f
                var moved = false
                var lastY = down.position.y
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val delta = change.position.y - lastY
                    lastY = change.position.y
                    if (!moved && kotlin.math.abs(change.position.y - down.position.y) > slop) moved = true
                    if (!moved) continue
                    dragOffset += delta
                    val info = listState.layoutInfo.visibleItemsInfo
                    val current = info.firstOrNull { it.index == dragIndex } ?: continue
                    val centerY = current.offset + current.size / 2f + dragOffset
                    val target = info.firstOrNull { it.index != dragIndex && it.index < working.size && centerY >= it.offset && centerY < it.offset + it.size }
                    if (target != null) {
                        val list = working.toMutableList()
                        val item = list.removeAt(dragIndex)
                        list.add(target.index, item)
                        // A lazy list holds on to its first visible row by KEY: swap that row and
                        // the list scrolls to follow it, carrying the dragged row off the top —
                        // which is why nothing could be dropped in first place. Pinning the
                        // position by index before the swap keeps the list where it is.
                        val first = listState.firstVisibleItemIndex
                        if (target.index == first || dragIndex == first)
                            listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
                        working = list
                        dragOffset += (current.offset - target.offset)
                        dragIndex = target.index
                    }
                    val edge = 96.dp.toPx(); val step = 18f
                    val top = current.offset + dragOffset; val bottom = top + current.size
                    if (top < listState.layoutInfo.viewportStartOffset + edge && listState.canScrollBackward) { scope.launch { listState.scrollBy(-step) }; dragOffset += step }
                    else if (bottom > listState.layoutInfo.viewportEndOffset - edge && listState.canScrollForward) { scope.launch { listState.scrollBy(step) }; dragOffset -= step }
                }
                val liftedIndex = dragIndex
                dragIndex = -1; dragOffset = 0f
                if (!moved) { working.getOrNull(liftedIndex)?.let(onLongPress) }
                else if (working != items) onReorder(working)
            }
        }
    ) {
        itemsIndexed(working, key = { _, it -> key(it) }) { index, item ->
            val dragging = index == dragIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .background(if (dragging) colors.fg.copy(alpha = 0.12f) else colors.bg)
            ) { row(item, dragging) }
        }
        tail()
    }
}
