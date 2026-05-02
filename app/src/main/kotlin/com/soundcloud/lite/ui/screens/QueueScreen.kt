package com.soundcloud.lite.ui.screens

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val isQueueShuffled = state.shuffledOrder != null
    val listState = rememberLazyListState()

    // Drag-reorder state
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // Tracks the top-Y and height of the LazyColumn for edge auto-scroll
    var listTopY by remember { mutableStateOf(0f) }
    var listHeightPx by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                modifier = Modifier
                    .padding(start = 4.dp)
                    .weight(1f),
            )
            // Shuffle queue button: first tap shuffles, second restores original order
            IconButton(
                onClick = { viewModel.playerManager.shuffleQueue() },
                enabled = queue.size > 1,
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

        if (queue.isEmpty()) {
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
                    onDrag = { delta, pointerYInRoot ->
                        dragOffsetY += delta

                        // --- Edge auto-scroll ---
                        val edgeZone = with(density) { 72.dp.toPx() }
                        val relY = pointerYInRoot - listTopY
                        val scrollSpeed = when {
                            relY < edgeZone && relY >= 0 ->
                                -((edgeZone - relY) / edgeZone * 18f)
                            relY > listHeightPx - edgeZone && relY <= listHeightPx ->
                                ((relY - (listHeightPx - edgeZone)) / edgeZone * 18f)
                            else -> 0f
                        }
                        if (scrollSpeed != 0f) {
                            listState.dispatchRawDelta(scrollSpeed)
                        }

                        // --- Reorder when drag crosses an item boundary (~64dp) ---
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
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = spring(stiffness = 400f, dampingRatio = 0.8f),
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
    // Swipe-to-dismiss: require 65% drag before committing — prevents
    // accidental removal from a tiny flick (was 50% before).
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.65f },
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
            // Show a delete indicator only on the side the user is swiping
            // toward, constrained to the far edge so it never peeks under
            // the track artwork.
            val alignment = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.CenterEnd
            }
            val fraction = (dismissState.progress).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(
                            alpha = (fraction * 1.5f).coerceIn(0f, 1f)
                        )
                    ),
                contentAlignment = alignment,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    if (isDragging) {
                        translationY = dragOffsetY
                        shadowElevation = 12f
                        scaleX = 1.02f
                        scaleY = 1.02f
                        alpha = 0.92f
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackRow(
                track = track,
                modifier = Modifier.weight(1f),
                onClick = onPlay,
            )
            // Drag handle — tracks pointer Y in root coords for auto-scroll
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
