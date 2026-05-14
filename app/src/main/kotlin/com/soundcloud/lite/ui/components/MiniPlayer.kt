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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.soundcloud.lite.player.PlayerState
import com.soundcloud.lite.ui.theme.LocalSCTheme

/**
 * Bottom-docked "now playing" strip. Three rendering modes, picked
 * from the theme toggles:
 *
 *  1. **Glassmorphism + Liquid Glass** -> the strip is drawn behind
 *     the same AGSL refraction capsule that powers the nav-bar
 *     active-tab pill, so the artwork / labels underneath visibly
 *     warp at the rim. The warp is *motion-gated*: a fresh impulse
 *     fires every time `state.currentTrack.id` changes (i.e. a new
 *     track starts) and decays to 0 over ~600 ms, mirroring the
 *     "icon distorts only while moving" behaviour the user asked
 *     for on the nav bar.
 *
 *  2. **Glassmorphism only** -> standard [GlassSurface] panel
 *     (blur + tilt reflections), no rim refraction. Toggling
 *     "Liquid glass" off in Settings now actually removes the
 *     refraction from the mini-player; previously the strip
 *     re-used GlassSurface unconditionally and the user setting
 *     had no visible effect here.
 *
 *  3. **All glass effects off** -> opaque themed surface.
 */
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

    val miniShape = RoundedCornerShape(20.dp)

    // Motion gate for the refraction. snap to 1 the instant a new
    // track starts playing, then decay over ~600 ms so the rim
    // warp pulses briefly on track change and otherwise the strip
    // reads as a clean lens with no distortion.
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

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
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

                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }

    when {
        glassOn && liquidOn -> {
            // Capture the strip's content into a private GraphicsLayer
            // and pipe it through the LiquidGlassCapsule shader, so
            // the rim distortion on the rounded-rect actually warps
            // what's drawn inside the strip (artwork edge, text near
            // the edges). The backdrop offset is (0,0) because we
            // sample from the strip's own layer, not the global
            // app backdrop -- so the lens never re-uses pixels from
            // outside its bounds.
            val miniBackdrop = rememberGraphicsLayer()

            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Layer 1 (offscreen): draw the strip content into
                // miniBackdrop AND to the screen, so the capsule
                // has something to refract AND the user can still
                // see the controls.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithContent {
                            miniBackdrop.record { this@drawWithContent.drawContent() }
                            drawLayer(miniBackdrop)
                        }
                ) { content() }

                // Layer 2: the lens. Sits ON TOP of the rendered
                // strip, so the rim warp visibly distorts the
                // artwork / labels along the rounded edges while
                // the motion gate is non-zero.
                LiquidGlassCapsule(
                    modifier = Modifier.matchParentSize(),
                    cornerRadiusDp = 20.dp,
                    backdropLayer = miniBackdrop,
                    motionAmount = miniMotion.value,
                )
            }
        }
        glassOn -> {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = miniShape,
            ) { content() }
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(miniShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) { content() }
        }
    }
}
