package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcloud.lite.data.DownloadStatus
import com.soundcloud.lite.data.DownloadedTrack
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow

@Composable
fun DownloadsScreen(viewModel: MainViewModel) {
    val downloads by viewModel.downloads.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Downloads",
            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No downloads yet.\nLong-press a track to download it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(downloads, key = { it.track.id }) { dt ->
                DownloadRow(
                    dt = dt,
                    onPlay = {
                        if (dt.status == DownloadStatus.DONE) {
                            viewModel.playerManager.play(dt.track, downloads.filter { it.status == DownloadStatus.DONE }.map { it.track })
                        }
                    },
                    onDelete = { viewModel.removeDownload(dt.track.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    dt: DownloadedTrack,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { total -> total * 0.4f },
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            onDelete()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val fraction = dismissState.progress.coerceIn(0f, 1f)
            val alpha = if (fraction > 0.02f) (fraction * 2f).coerceAtMost(1f) else 0f
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = alpha))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                    Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (alpha > 0.1f) Icon(Icons.Filled.Delete, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha))
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackRow(
                track = dt.track,
                modifier = Modifier.weight(1f),
                onClick = onPlay,
            )
            when (dt.status) {
                DownloadStatus.DOWNLOADING -> {
                    CircularProgressIndicator(
                        progress = { dt.progress / 100f },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                DownloadStatus.FAILED -> {
                    Text(
                        text = "Failed",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                else -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
