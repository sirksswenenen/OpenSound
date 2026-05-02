package com.soundcloud.lite.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow
import kotlinx.coroutines.launch

@Composable
fun QueueScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.playerManager.state.collectAsState()
    val queue = state.queue
    val curIdx = state.queueIndex
    val listState = rememberLazyListState()

    // Drag-reorder state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Queue",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (queue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
                QueueRow(
                    track = track,
                    isCurrent = index == curIdx,
                    onPlay = {
                        viewModel.playerManager.play(track, queue)
                    },
                    onSwipeAway = {
                        if (index != curIdx) {
                            val next = queue.toMutableList().apply { removeAt(index) }
                            val newCur = if (index < curIdx) curIdx - 1 else curIdx
                            viewModel.playerManager.setQueue(next, newCur.coerceAtLeast(0))
                        }
                    },
                    onDragStart = {
                        draggingIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { delta ->
                        dragOffsetY += delta
                        // Reorder when drag crosses an item boundary (~60dp)
                        val rowPx = with(density) { 64.dp.toPx() }
                        val moved = (dragOffsetY / rowPx).toInt()
                        if (moved != 0) {
                            val from = draggingIndex ?: return@QueueRow
                            val to = (from + moved).coerceIn(0, queue.size - 1)
                            if (to != from) {
                                val next = queue.toMutableList().apply {
                                    val item = removeAt(from)
                                    add(to, item)
                                }
                                val newCur = when {
                                    from == curIdx -> to
                                    from < curIdx && to >= curIdx -> curIdx - 1
                                    from > curIdx && to <= curIdx -> curIdx + 1
                                    else -> curIdx
                                }
                                viewModel.playerManager.setQueue(next, newCur)
                                draggingIndex = to
                                dragOffsetY -= moved * rowPx
                            }
                        }
                    },
                    onDragEnd = {
                        draggingIndex = null
                        dragOffsetY = 0f
                    },
                    isDragging = draggingIndex == index,
                    dragOffsetY = if (draggingIndex == index) dragOffsetY else 0f,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: TrackInfo,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onSwipeAway: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    isDragging: Boolean,
    dragOffsetY: Float,
) {
    // Swipe-to-dismiss with 50% threshold (Bug E from earlier — Material3
    // default is ~56dp which fires after a tiny flick).
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.5f },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onSwipeAway()
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    if (isDragging) {
                        translationY = dragOffsetY
                        alpha = 0.85f
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackRow(
                track = track,
                modifier = Modifier
                    .weight(1f),
                onClick = onPlay,
            )
            // Drag handle: long-press anywhere on it to start a reorder
            IconButton(
                onClick = {},
                modifier = Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { _, drag -> onDrag(drag.y) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
