package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val localQueue = remember { mutableStateListOf<TrackInfo>() }
    var localCurId by remember { mutableLongStateOf(-1L) }

    // draggingId triggers recomposition (needed to show/hide visual).
    var draggingId by remember { mutableStateOf<Long?>(null) }
    // dragVisualOffsetY triggers recomposition (needed to move the row visually).
    var dragVisualOffsetY by remember { mutableFloatStateOf(0f) }

    // These are plain FloatArrays — written on every drag event but never
    // trigger recomposition, so gesture handlers never restart mid-drag.
    val pointerRootYRef = remember { FloatArray(1) { 0f } }
    val listTopYRef   = remember { FloatArray(1) { 0f } }
    val listBottomYRef = remember { FloatArray(1) { 0f } }

    // Single continuous scroll coroutine — reads plain floats every 16 ms.
    LaunchedEffect(Unit) {
        while (true) {
            if (draggingId != null) {
                val py  = pointerRootYRef[0]
                val top = listTopYRef[0]
                val bot = listBottomYRef[0]
                val edgeZone = with(density) { 150.dp.toPx() }
                val maxSpeed = 55f
                val relTop = py - top
                val relBot = bot - py
                val speed = when {
                    relTop in 0f..edgeZone -> -((1f - relTop / edgeZone) * maxSpeed)
                    relBot in 0f..edgeZone ->  (1f - relBot / edgeZone) * maxSpeed
                    else -> 0f
                }
                if (abs(speed) > 0.5f) listState.scrollBy(speed)
            }
            delay(16L)
        }
    }

    // Sync from player state when not dragging
    LaunchedEffect(playerQueue, playerCurIdx) {
        if (draggingId == null) {
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
        // Header
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
            IconButton(onClick = { viewModel.playerManager.shuffleQueue() }, enabled = localQueue.size > 1) {
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

        val rowHeightPx = with(density) { 70.dp.toPx() }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp)
                .onGloballyPositioned { coords ->
                    // Plain float write — no recomposition
                    listTopYRef[0]    = coords.positionInRoot().y
                    listBottomYRef[0] = listTopYRef[0] + coords.size.height
                },
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(localQueue, key = { _, t -> t.id }) { index, track ->
                val isCurrent     = track.id == localCurId
                val isThisDragging = track.id == draggingId

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

                SwipeToDismissBox(
                    state = if (isThisDragging) rememberSwipeToDismissBoxState() else dismissState,
                    modifier = if (isThisDragging)
                        Modifier.zIndex(10f)
                    else
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    backgroundContent = {
                        val fraction = dismissState.progress.coerceIn(0f, 1f)
                        val alpha = if (!isThisDragging && fraction > 0.02f)
                            (fraction * 2f).coerceAtMost(1f) else 0f
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .then(
                                if (isThisDragging)
                                    Modifier.graphicsLayer {
                                        translationY   = dragVisualOffsetY
                                        shadowElevation = 24f
                                        scaleX = 1.03f; scaleY = 1.03f
                                    }
                                else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(
                            track = track,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.playerManager.play(track, localQueue.toList()) },
                        )

                        // Drag handle — immediate drag, no long press
                        // handleRootY is a plain FloatArray so writes don't cause recomposition.
                        val handleRootYRef = remember { FloatArray(1) { 0f } }
                        Icon(
                            imageVector   = Icons.Filled.DragHandle,
                            contentDescription = "Drag to reorder",
                            tint = if (isCurrent) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 14.dp)
                                .onGloballyPositioned { coords ->
                                    // Always update — even during another item's drag,
                                    // so we have fresh position when THIS item starts dragging.
                                    handleRootYRef[0] =
                                        coords.positionInRoot().y + coords.size.height / 2f
                                }
                                .pointerInput(track.id) {
                                    detectDragGestures(
                                        onDragStart = { localOffset ->
                                            draggingId        = track.id
                                            dragVisualOffsetY = 0f
                                            // Initialise pointer position from the handle's
                                            // current root Y (no recomposition-based stale value).
                                            pointerRootYRef[0] = handleRootYRef[0] + localOffset.y
                                        },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            dragVisualOffsetY   += drag.y
                                            pointerRootYRef[0]  += drag.y

                                            // Reorder when visual displacement exceeds one row
                                            val from = localQueue.indexOfFirst { it.id == draggingId }
                                            if (from >= 0) {
                                                val steps = (dragVisualOffsetY / rowHeightPx).toInt()
                                                if (steps != 0) {
                                                    val to = (from + steps).coerceIn(0, localQueue.size - 1)
                                                    if (to != from) {
                                                        localQueue.add(to, localQueue.removeAt(from))
                                                        dragVisualOffsetY -= steps * rowHeightPx
                                                    }
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val ni = localQueue.indexOfFirst { it.id == localCurId }
                                                .coerceAtLeast(0)
                                            viewModel.playerManager.setQueue(localQueue.toList(), ni)
                                            draggingId        = null
                                            dragVisualOffsetY = 0f
                                        },
                                        onDragCancel = {
                                            draggingId        = null
                                            dragVisualOffsetY = 0f
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}
