package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.ui.MainViewModel

@Composable
fun PlaylistsScreen(
    viewModel: MainViewModel,
    onOpenPlaylist: (String) -> Unit = {},
) {
    val playlists by viewModel.playlists.collectAsState()
    val importInProgress by viewModel.importInProgress.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var importUrl by remember { mutableStateOf("") }
    var confirmDeletePlaylist by remember { mutableStateOf<Playlist?>(null) }
    val clipboard = LocalClipboardManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (playlists.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "No playlists yet.\nTap + to create or ↓ to import.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            // Grid of artwork cards, same visual language as the
            // "Your playlists" rail on the Home screen. Adaptive
            // columns so the layout adapts to phone width (~2 cols
            // typical, 3+ on tablets / landscape).
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 144.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(playlists, key = { it.id }) { p ->
                    PlaylistGridCard(
                        playlist = p,
                        onClick = { onOpenPlaylist(p.id) },
                        onLongPress = { confirmDeletePlaylist = p },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 96.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    importUrl = clipboard.getText()?.text.orEmpty()
                    showImport = true
                },
            ) {
                Icon(Icons.Filled.Download, contentDescription = "Import playlist")
            }
            FloatingActionButton(
                onClick = { newTitle = ""; showCreate = true },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create playlist")
            }
        }
    }

    // ── Confirm delete dialog ────────────────────────────────────────────────
    confirmDeletePlaylist?.let { pl ->
        AlertDialog(
            onDismissRequest = { confirmDeletePlaylist = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${pl.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(pl.id)
                        confirmDeletePlaylist = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePlaylist = null }) { Text("Cancel") }
            },
        )
    }

    // ── Create playlist dialog ───────────────────────────────────────────────
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("Title") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createPlaylist(newTitle.trim())
                    showCreate = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("Cancel") }
            },
        )
    }

    // ── Import playlist dialog ───────────────────────────────────────────────
    if (showImport) {
        AlertDialog(
            onDismissRequest = { if (!importInProgress) showImport = false },
            title = { Text("Import playlist") },
            text = {
                Column {
                    Text(
                        text = "Paste a SoundCloud playlist URL (e.g. /user/sets/my-set).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            placeholder = { Text("https://…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            importUrl = clipboard.getText()?.text.orEmpty()
                        }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                        }
                    }
                    if (importInProgress) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text(
                                text = "Importing tracks…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importInProgress && importUrl.isNotBlank(),
                    onClick = {
                        viewModel.importPlaylistFromUrl(importUrl.trim())
                        showImport = false
                    },
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(enabled = !importInProgress, onClick = { showImport = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Home-screen-style playlist tile used in the Playlists screen grid.
 * Square artwork on top (or first letter of the title as a fallback),
 * title + track count below. Tap opens, long-press triggers the
 * delete-confirmation dialog — we dropped the swipe-to-dismiss row
 * pattern because the user found the old text-only rows ugly and
 * asked for a card layout matching the Home screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistGridCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val art = playlist.artworkUrl
                ?: playlist.tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Text(
                    text = playlist.title.take(1).uppercase(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = playlist.title,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "${playlist.tracks.size} tracks",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}
