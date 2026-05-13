package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(playlists, key = { it.id }) { p ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        positionalThreshold = { total -> total * 0.4f },
                    )
                    LaunchedEffect(dismissState.currentValue) {
                        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                            confirmDeletePlaylist = p
                            dismissState.reset()
                        }
                    }

                    SwipeToDismissBox(
                        state = dismissState,
                        modifier = Modifier.animateItem(),
                        backgroundContent = {
                            val fraction = dismissState.progress.coerceIn(0f, 1f)
                            val alpha = if (fraction > 0.02f) (fraction * 2f).coerceAtMost(1f) else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer.copy(alpha = alpha)
                                    )
                                    .padding(horizontal = 20.dp),
                                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                                    Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                if (alpha > 0.1f) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = alpha),
                                    )
                                }
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onOpenPlaylist(p.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = p.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 16.sp,
                                )
                                Text(
                                    text = "${p.tracks.size} tracks",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            IconButton(onClick = { confirmDeletePlaylist = p }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete playlist",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
