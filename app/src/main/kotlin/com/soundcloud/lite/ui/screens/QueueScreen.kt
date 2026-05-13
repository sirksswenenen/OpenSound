package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Queue editor screen.
 *
 * Drag-reorder is implemented via [rememberDragDropState] which:
 *   - Reads live row geometry from [LazyListState.layoutInfo] instead of
 *     bookkeeping pixel offsets manually.
 *   - Picks a target row by hit-testing the dragged item's running centre
 *     against the actual displayed rows (handles variable item heights and
 *     items that slide in/out of the viewport).
 *   - Cooperates with `Modifier.animateItem()` so unaffected rows glide to
 *     their new positions without snapping.
 *   - Auto-scrolls when the dragged row gets within an edge band.
 *
 * Long-press anywhere on a row starts the drag; the visible drag handle
 * works the same way for discoverability.
 */
@Composable
fun QueueScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.playerManager.state.collectAsState()
    val playerQueue = state.queue
    val playerCurIdx = state.queueIndex
    val isQueueShuffled = state.shuffledOrder != null
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val localQueue = remember { mutableStateListOf<TrackInfo>() }
    var localCurId by remember { mutableLongStateOf(-1L) }

    // Pull from the player whenever it changes — unless the user is mid-drag.
    val dragState = rememberDragDropState(
        listState = listState,
        scope = scope,
        onMove = { from, to ->
            if (from in localQueue.indices && to in localQueue.indices) {
                localQueue.add(to, localQueue.removeAt(from))
            }
        },
        onDragEnd = {
            val ni = localQueue.indexOfFirst { it.id == localCurId }.coerceAtLeast(0)
            viewModel.playerManager.setQueue(localQueue.toList(), ni)
        },
    )

    LaunchedEffect(playerQueue, playerCurIdx) {
        if (!dragState.isDragging) {
            val seen = mutableSetOf<Long>()
            val deduped = playerQueue.mapIndexed { idx, t ->
                var id = t.id
                while (!seen.add(id)) id = id xor ((idx + 1L) * -7046029254386353131L)
                if (id == t.id) t else t.copy(id = id)
            }
            localQueue.clear()
            localQueue.addAll(deduped)
            localCurId = deduped.getOrNull(playerCurIdx)?.id ?: -1L
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Queue",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(
                onClick = { viewModel.playerManager.shuffleQueue() },
                enabled = localQueue.size > 1,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = if (isQueueShuffled) "Restore order" else "Shuffle",
                    tint = if (isQueueShuffled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (localQueue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .dragContainer(dragState),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(localQueue, key = { _, t -> t.id }) { index, track ->
                val isCurrent = track.id == localCurId
                val isThisDragging = dragState.draggingItemKey == track.id

                val dismissState = rememberSwipeToDismissBoxState(
                    positionalThreshold = { total -> total * 0.40f },
                )
                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                        if (isCurrent) {
                            viewModel.playerManager.stopAndRemoveCurrent()
                        } else {
                            val nq = localQueue.toMutableList().also { it.removeAt(index) }
                            val ni = nq.indexOfFirst { it.id == localCurId }.coerceAtLeast(0)
                            localQueue.clear(); localQueue.addAll(nq)
                            viewModel.playerManager.setQueue(nq, ni)
                        }
                        dismissState.reset()
                    }
                }

                val rowModifier = if (isThisDragging) {
                    Modifier
                        .zIndex(10f)
                        .graphicsLayer {
                            translationY = dragState.draggingItemOffset
                            shadowElevation = 24f
                            scaleX = 1.03f
                            scaleY = 1.03f
                        }
                } else {
                    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                }

                SwipeToDismissBox(
                    state = if (isThisDragging)
                        rememberSwipeToDismissBoxState() else dismissState,
                    modifier = rowModifier,
                    enableDismissFromStartToEnd = !isThisDragging,
                    enableDismissFromEndToStart = !isThisDragging,
                    backgroundContent = {
                        val fraction = dismissState.progress.coerceIn(0f, 1f)
                        val alpha = if (!isThisDragging && fraction > 0.02f)
                            (fraction * 2f).coerceAtMost(1f) else 0f
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
                                ),
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(
                            track = track,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.playerManager.play(track, localQueue.toList())
                            },
                        )
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .dragHandle(dragState, track.id),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Snapshot of an item's settled position in the [LazyColumn], captured at
 * drag-start so we can hit-test against the rest of the list as we move.
 */
private data class ItemRef(
    val key: Any,
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * Self-contained drag-and-drop state machine for a [LazyColumn]. Drives:
 *  - a translucent translation of the dragged row,
 *  - swaps via [onMove] when the dragged row's centre crosses another row,
 *  - auto-scroll near the viewport edges.
 *
 * Intentionally framework-agnostic of any specific data model — callers just
 * pass `onMove(from, to)` and update their own backing list.
 */
private class DragDropState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDragEnd: () -> Unit,
) {
    var draggingItemKey: Any? by mutableStateOf(null)
        private set

    /** Pixels the dragged row is translated relative to its current LazyColumn slot. */
    var draggingItemOffset: Float by mutableStateOf(0f)
        private set

    val isDragging: Boolean get() = draggingItemKey != null

    private var initial: ItemRef? = null
    private var autoscrollJob: Job? = null

    fun onStart(offsetY: Float) {
        // Resolve which item the long-press happened on.
        val info = listState.layoutInfo
        val viewportStart = info.viewportStartOffset
        val target = info.visibleItemsInfo.firstOrNull { item ->
            val itemTop = item.offset
            val itemBottom = item.offset + item.size
            offsetY.toInt() in (itemTop - viewportStart)..(itemBottom - viewportStart)
        } ?: return
        initial = ItemRef(target.key ?: return, target.index, target.offset, target.size)
        draggingItemKey = target.key
        draggingItemOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        val anchor = initial ?: return
        draggingItemOffset += deltaY

        // Project the dragged row's centre into LazyColumn coordinates so we
        // can ask layoutInfo where it currently is relative to settled rows.
        val info = listState.layoutInfo
        val draggedCentre = anchor.offset + anchor.size / 2 + draggingItemOffset.toInt()

        val targetItem = info.visibleItemsInfo.firstOrNull { other ->
            if (other.key == draggingItemKey) return@firstOrNull false
            draggedCentre in other.offset..(other.offset + other.size)
        }
        if (targetItem != null) {
            val fromIndex = info.visibleItemsInfo.firstOrNull { it.key == draggingItemKey }?.index
                ?: return
            onMove(fromIndex, targetItem.index)
            // After the swap, the dragged row's "settled" slot moved by one
            // row's height; subtract that from the visual offset so the row
            // stays under the finger.
            draggingItemOffset -= (targetItem.offset - anchor.offset).toFloat()
            initial = anchor.copy(index = targetItem.index, offset = targetItem.offset)
        }

        maybeAutoscroll(info, draggedCentre)
    }

    private fun maybeAutoscroll(info: LazyListLayoutInfo, draggedCentre: Int) {
        val viewportTop = info.viewportStartOffset
        val viewportBottom = info.viewportEndOffset
        val edgeBand = (viewportBottom - viewportTop) / 5
        val speedTop = (draggedCentre - (viewportTop + edgeBand)).toFloat()
        val speedBottom = (draggedCentre - (viewportBottom - edgeBand)).toFloat()
        val needsScroll = when {
            speedTop < 0f -> speedTop / edgeBand * 30f
            speedBottom > 0f -> speedBottom / edgeBand * 30f
            else -> 0f
        }
        if (needsScroll != 0f) {
            if (autoscrollJob?.isActive != true) {
                autoscrollJob = scope.launch {
                    while (isDragging) {
                        val info2 = listState.layoutInfo
                        val anchor = initial ?: break
                        val centre = anchor.offset + anchor.size / 2 + draggingItemOffset.toInt()
                        val top = info2.viewportStartOffset
                        val bottom = info2.viewportEndOffset
                        val band = (bottom - top) / 5
                        val s = when {
                            centre < top + band -> (centre - (top + band)).toFloat() / band * 30f
                            centre > bottom - band -> (centre - (bottom - band)).toFloat() / band * 30f
                            else -> 0f
                        }
                        if (s == 0f) break
                        listState.scrollBy(s)
                        delay(16L)
                    }
                }
            }
        } else {
            autoscrollJob?.cancel()
            autoscrollJob = null
        }
    }

    fun onEnd() {
        if (draggingItemKey != null) onDragEnd()
        draggingItemKey = null
        draggingItemOffset = 0f
        initial = null
        autoscrollJob?.cancel()
        autoscrollJob = null
    }
}

@Composable
private fun rememberDragDropState(
    listState: LazyListState,
    scope: CoroutineScope,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: () -> Unit,
): DragDropState {
    return remember(listState, scope) {
        DragDropState(listState, scope, onMove, onDragEnd)
    }
}

/** Long-press anywhere on the row container starts a drag. */
private fun Modifier.dragContainer(state: DragDropState): Modifier = this.pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset -> state.onStart(offset.y) },
        onDrag = { change, drag ->
            change.consume()
            state.onDrag(drag.y)
        },
        onDragEnd = { state.onEnd() },
        onDragCancel = { state.onEnd() },
    )
}

/** Drag handle: starts immediately on press (no long-press required). */
private fun Modifier.dragHandle(state: DragDropState, key: Any): Modifier = this.pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { offset -> state.onStart(offset.y) },
        onDrag = { change, drag ->
            change.consume()
            state.onDrag(drag.y)
        },
        onDragEnd = { state.onEnd() },
        onDragCancel = { state.onEnd() },
    )
}
