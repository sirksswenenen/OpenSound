package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.ui.MainViewModel
import com.soundcloud.lite.ui.components.TrackRow

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenPlaylist: (String) -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
) {
    val trending by viewModel.trending.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val isLoading by viewModel.isLoadingTrending.collectAsState()
    val state = rememberLazyListState()

    LaunchedEffect(Unit) { viewModel.loadTrending() }

    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 5 && info.totalItemsCount > 0
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) viewModel.loadMoreTrending() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (playlists.isNotEmpty()) {
            Text(
                text = "Your playlists",
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(playlists, key = { it.id }) { pl ->
                    PlaylistTile(pl) { onOpenPlaylist(pl.id) }
                }
            }
        }
        Text(
            text = "Trending",
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trending.isEmpty() && isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (trending.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Couldn't load trending. Pull down to refresh, or check OAuth token in Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(trending, key = { it.id }) { t ->
                    TrackRow(
                        track = t,
                        onClick = { viewModel.playTrack(t, trending) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val art = playlist.artworkUrl ?: playlist.tracks.firstOrNull()?.artworkUrl
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = playlist.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                ) {
                    Text(
                        text = playlist.title.take(2).uppercase(),
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                }
            }
        }
        Text(
            text = playlist.title,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${playlist.tracks.size} tracks",
            modifier = Modifier.padding(top = 2.dp, start = 2.dp, bottom = 4.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
