package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow

@Composable
fun QueueScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.playerManager.state.collectAsState()
    val queue = state.queue
    val curIdx = state.queueIndex
    val isQueueShuffled = state.shuffledOrder != null
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // Local mutable snapshot of the queue — updated optimistically during drag
    // so the LazyColumn re-renders in the new order immediately.
    val localQueue = remember { mutableStateListOf<TrackInfo>() }
    var localCurIdx by remember { mutableIntStateOf(0) }

    // Sync local state when external state changes (and not dragging).
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(queue, curIdx) {
        if (!isDragging) {
            localQueue.clear()
            localQueue.addAll(queue)
            localCurIdx = curIdx
        }
    }

    // Drag state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var listTopY by remember { mutableStateOf(0f) }
    var listHeightPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Header ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                    contentDescription = if (isQueueShuffled) "Restore order" else "Shuffle queue",
                    tint = if (isQueueShuffled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (localQueue.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .onGloballyPositioned { coords ->
                    listTopY = coords.positionInRoot().y
                    listHeightPx = coords.size.height.toFloat()
                },
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(localQueue, key = { _, t -> t.id }) { index, track ->
                val currentIndex by rememberUpdatedState(localCurIdx)

                QueueRow(
                    track = track,
                    isCurrent = index == currentIndex,
                    onPlay = { viewModel.playerManager.play(track, localQueue.toList()) },
                    onSwipeAway = {
                        if (index == currentIndex) {
                            // Swiping current track: stop and advance or clear
                            viewModel.playerManager.stopAndRemoveCurrent()
                        } else {
                            val newQueue = localQueue.toMutableList().apply { removeAt(index) }
                            val newCur = when {
                                index < currentIndex -> currentIndex - 1
                                else -> currentIndex
                            }.coerceAtLeast(0)
                            localQueue.clear()
                            localQueue.addAll(newQueue)
                            localCurIdx = newCur
                            viewModel.playerManager.setQueue(newQueue, newCur)
                        }
                    },
                    onDragStart = {
                        isDragging = true
                        draggingIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { delta, pointerYInRoot ->
                        dragOffsetY += delta

                        // Edge auto-scroll
                        val edgeZone = with(density) { 72.dp.toPx() }
                        val relY = pointerYInRoot - listTopY
                        val scrollSpeed = when {
                            relY < edgeZone && relY >= 0 -> -((edgeZone - relY) / edgeZone * 18f)
                            relY > listHeightPx - edgeZone && relY <= listHeightPx ->
                                ((relY - (listHeightPx - edgeZone)) / edgeZone * 18f)
                            else -> 0f
                        }
                        if (scrollSpeed != 0f) listState.dispatchRawDelta(scrollSpeed)

                        // Reorder when drag crosses an item boundary (~68dp)
                        val rowPx = with(density) { 68.dp.toPx() }
                        val steps = (dragOffsetY / rowPx).toInt()
                        if (steps != 0) {
                            val from = draggingIndex ?: return@QueueRow
                            val to = (from + steps).coerceIn(0, localQueue.size - 1)
                            if (to != from) {
                                val item = localQueue.removeAt(from)
                                localQueue.add(to, item)
                                localCurIdx = when {
                                    from == localCurIdx -> to
                                    from < localCurIdx && to >= localCurIdx -> localCurIdx - 1
                                    from > localCurIdx && to <= localCurIdx -> localCurIdx + 1
                                    else -> localCurIdx
                                }
                                draggingIndex = to
                                dragOffsetY -= steps * rowPx
                            }
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        // Commit the reordered queue to the player
                        viewModel.playerManager.setQueue(localQueue.toList(), localCurIdx)
                        draggingIndex = null
                        dragOffsetY = 0f
                    },
                    isDragging = draggingIndex == index,
                    dragOffsetY = if (draggingIndex == index) dragOffsetY else 0f,
                    modifier = if (draggingIndex == index)
                        Modifier.zIndex(1f)
                    else
                        Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                        ),
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
    onDrag: (delta: Float, pointerYInRoot: Float) -> Unit,
    onDragEnd: () -> Unit,
    isDragging: Boolean,
    dragOffsetY: Float,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.40f },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onSwipeAway()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            // Only show background color when actually swiping — avoid
            // red flash when progress is exactly 0 (settled state).
            val fraction = dismissState.progress.coerceIn(0f, 1f)
            val alpha = if (fraction > 0.01f) (fraction * 2f).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
                    ),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .graphicsLayer {
                    if (isDragging) {
                        translationY = dragOffsetY
                        shadowElevation = 16f
                        scaleX = 1.02f
                        scaleY = 1.02f
                        alpha = 0.95f
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackRow(
                track = track,
                modifier = Modifier.weight(1f),
                onClick = onPlay,
            )
            var handleRootY by remember { mutableStateOf(0f) }
            IconButton(
                onClick = {},
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        handleRootY = coords.positionInRoot().y + coords.size.height / 2f
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, drag ->
                                change.consume()
                                handleRootY += drag.y
                                onDrag(drag.y, handleRootY)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = if (isCurrent)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
