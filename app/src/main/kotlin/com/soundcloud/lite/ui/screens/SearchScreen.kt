package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow

@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onOpenRelated: (Long) -> Unit = {},
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val listState = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 5 && info.totalItemsCount > 0
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) viewModel.loadMoreSearch() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            singleLine = true,
            placeholder = { Text("Search tracks…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )

        if (isSearching && results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.id }) { track ->
                    SearchResultRow(
                        track = track,
                        onPlay = { viewModel.playSearchResult(track) },
                        onOpenRelated = { onOpenRelated(track.id) },
                        onAddToPlaylist = { pid -> viewModel.addToPlaylist(pid, track) },
                        playlistOptions = playlists.map { it.id to it.title },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    track: TrackInfo,
    onPlay: () -> Unit,
    onOpenRelated: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    playlistOptions: List<Pair<String, String>>,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackRow(
            track = track,
            modifier = Modifier.padding(end = 4.dp),
            onClick = onPlay,
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = { menu = false; onPlay() },
                )
                DropdownMenuItem(
                    text = { Text("Related tracks") },
                    onClick = { menu = false; onOpenRelated() },
                )
                if (playlistOptions.isNotEmpty()) {
                    Text(
                        text = "Add to playlist",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    playlistOptions.forEach { (pid, title) ->
                        DropdownMenuItem(
                            text = { Text(title) },
                            onClick = { menu = false; onAddToPlaylist(pid) },
                        )
                    }
                } else {
                    DropdownMenuItem(
                        text = { Text("Add to playlist (none yet)") },
                        enabled = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}
