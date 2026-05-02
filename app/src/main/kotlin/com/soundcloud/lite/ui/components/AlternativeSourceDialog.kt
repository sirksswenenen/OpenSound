package com.soundcloud.lite.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.soundcloud.lite.api.AlternativeSource
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.ui.AltSourceState

/**
 * Placeholder for the upcoming "alternative sources" feature
 * (Cobalt / YouTube fallback for tracks SoundCloud blocks). Right now
 * it just confirms the dialog can be opened and dismissed without
 * crashing.
 */
@Composable
fun AlternativeSourceDialog(
    state: AltSourceState,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlay: (AlternativeSource) -> Unit,
    onReplaceInCurrent: (AlternativeSource) -> Unit,
    onAddToPlaylist: (String, AlternativeSource) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alternative sources") },
        text = {
            Text(
                "Alternative sources for blocked tracks will arrive in a " +
                    "future update. Track: ${state.originalTrack.title}",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
