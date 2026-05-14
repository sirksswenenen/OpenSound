package com.soundcloud.lite.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.soundcloud.lite.player.PlayerState
import com.soundcloud.lite.ui.theme.LocalSCTheme

@Composable
fun MiniPlayer(
    state: PlayerState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit
) {
    val track = state.currentTrack ?: return

    val theme = LocalSCTheme.current
    val glassOn = theme.glassEnabled
    val liquidOn = theme.liquidGlass

    // Motion factor for LiquidGlass refraction. Mirrors the bottom-nav
    // pill behavior: 0 at rest, snaps to 1 on a "movement" event (here
    // the only movement is a new track loading) and decays back to 0
    // over ~600 ms. So the rim distortion + chromatic aberration only
    // appear briefly when the track changes, not constantly.
    val miniMotion = remember { Animatable(0f) }
    LaunchedEffect(track.id) {
        miniMotion.snapTo(1f)
        miniMotion.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = 600,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    val miniShape = RoundedCornerShape(theme.glassRadius.coerceAtLeast(0f).dp)

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Progress bar
            val progress = if (state.duration > 0) {
                state.position.toFloat() / state.duration.toFloat()
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Artwork
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (track.artworkUrl != null) {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.Center),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artistName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    when {
        // Liquid Glass on + glassmorphism on: render the same AGSL
        // refraction shader the bottom-nav capsule uses, with motion
        // gated so the rim warp only flashes briefly on track change
        // and the panel reads as clean glass at rest.
        glassOn && liquidOn -> {
            Box(modifier = Modifier.fillMaxWidth()) {
                LiquidGlassCapsule(
                    modifier = Modifier.matchParentSize(),
                    shape = miniShape,
                    motionAmount = miniMotion.value,
                )
                content()
            }
        }
        // Liquid Glass off, glassmorphism on: standard frosted-glass
        // surface (GlassSurface handles its own GPU blur path).
        glassOn -> {
            GlassSurface(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        // Glassmorphism off entirely: flat themed surface so the strip
        // is still visible against the gradient background.
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(miniShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                content()
            }
        }
    }
}
