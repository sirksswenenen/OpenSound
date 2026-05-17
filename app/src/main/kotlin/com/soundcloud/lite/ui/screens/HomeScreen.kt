package com.soundcloud.lite.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
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
    val downloads by viewModel.downloads.collectAsState()
    val isLoading by viewModel.isLoadingTrending.collectAsState()
    val personalised by viewModel.hasRecommendationSeeds.collectAsState()
    val state = rememberLazyListState()

    // Reload the feed whenever the library snapshot shifts (e.g. user
    // downloaded a new track or added one to a playlist) so the
    // recommendation seed set stays current. The viewModel itself
    // dedups internally so this is cheap on no-op changes.
    LaunchedEffect(downloads.size, playlists.sumOf { it.tracks.size }) {
        viewModel.loadTrending()
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 5 && info.totalItemsCount > 0
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) viewModel.loadMoreTrending() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- Playlists row (shown only if any playlists exist) ----
        if (playlists.isNotEmpty()) {
            Text(
                text = "Playlists",
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(playlists, key = { it.id }) { pl ->
                    PlaylistCard(playlist = pl, onClick = { onOpenPlaylist(pl.id) })
                }
            }
        }

        // ---- Trending / For-You header ----
        // Title flips to "For You" the moment the user has at least
        // one downloaded or playlist track - the feed underneath is
        // already a personalised similar-tracks stream by that point.
        Text(
            text = if (personalised) "For You" else "Trending",
            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
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
                    text = if (personalised) {
                        "Couldn't load recommendations. Check OAuth token in Settings."
                    } else {
                        "Couldn't load trending. Check OAuth token in Settings."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(trending, key = { it.id }) { t ->
                    TrackRow(
                        track = t,
                        onClick = { viewModel.playTrack(t, trending) },
                    )
                }
                if (isLoading && trending.isNotEmpty()) {
                    item("loader") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (playlist.artworkUrl != null) {
                AsyncImage(
                    model = playlist.artworkUrl,
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                // First-letter monogram fallback
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
            fontSize = 13.sp,
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
