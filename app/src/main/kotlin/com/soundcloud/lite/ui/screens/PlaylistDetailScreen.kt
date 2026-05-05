package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow

@Composable
fun PlaylistDetailScreen(
    viewModel: MainViewModel,
    playlistId: String,
    onBack: () -> Unit,
) {
    val playlists by viewModel.playlists.collectAsState()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val downloads by viewModel.downloads.collectAsState()
    if (playlist == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Playlist not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
                Text(
                    text = playlist.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${playlist.tracks.size} tracks",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            if (playlist.tracks.isNotEmpty()) {
                // Play all
                IconButton(onClick = {
                    viewModel.playerManager.play(playlist.tracks.first(), playlist.tracks)
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
                // Batch download — downloads all un-downloaded playable tracks
                val anyNotDownloaded = playlist.tracks.any {
                    !it.isUnplayable && !downloads.any { d -> d.track.id == it.id && d.status == com.soundcloud.lite.data.DownloadStatus.DONE }
                }
                val batchDownloading = downloads.any { it.status == com.soundcloud.lite.data.DownloadStatus.DOWNLOADING }
                if (batchDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 8.dp).size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (anyNotDownloaded) {
                    IconButton(onClick = { viewModel.downloadPlaylist(playlist.id) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Download all",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    // All downloaded
                    Icon(Icons.Filled.DownloadDone, contentDescription = "All downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        if (playlist.tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Empty.\nUse the search screen to add tracks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(playlist.tracks, key = { it.id }) { t ->
                    val isDownloaded = downloads.any { it.track.id == t.id && it.status == com.soundcloud.lite.data.DownloadStatus.DONE }
                    val isDownloading = downloads.any { it.track.id == t.id && it.status == com.soundcloud.lite.data.DownloadStatus.DOWNLOADING }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackRow(
                            track = t,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.playerManager.play(t, playlist.tracks) },
                        )
                        if (!t.isUnplayable) {
                            when {
                                isDownloading -> {
                                    val dt = downloads.first { it.track.id == t.id }
                                    CircularProgressIndicator(
                                        progress = { dt.progress / 100f },
                                        modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                isDownloaded -> IconButton(onClick = { viewModel.removeDownload(t.id) }) {
                                    Icon(Icons.Filled.DownloadDone, contentDescription = "Remove download",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                else -> IconButton(onClick = { viewModel.downloadTrack(t) }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download")
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.removeFromPlaylist(playlist.id, t.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove from playlist")
                        }
                    }
                }
            }
        }
    }
}
