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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
            // Three stacked effects (in z-order, bottom → top):
            //
            //  1. GlassSurface wraps everything — actually blurs the
            //     app pixels behind the mini-player, applies the
            //     frosted tint, chromatic aberration and tilt-driven
            //     specular reflections (all driven by user settings).
            //     Without this wrap the strip rendered as fully
            //     transparent on screen (v0.5.5 regression).
            //
            //  2. Always-on AGSL rim lens that refracts the *global*
            //     app backdrop (LocalBackdropLayer) so whatever sits
            //     visually behind the mini-player gets distorted at
            //     the rounded edges, in real time, with the
            //     blur/chroma/refraction settings the user picked
            //     in Settings applied to the underlying frosted base.
            //     This is what the user asked for: "refract what's
            //     behind the mini-player, with blur/chroma from
            //     settings affecting it". motionAmount is held at 1
            //     so the lens is permanently visible at the rim, just
            //     like the nav-bar pill when it's being dragged.
            //
            //  3. Strip content (artwork + title + controls).
            //
            //  4. AGSL rim lens that refracts the strip's *own*
            //     drawn content (captured into miniBackdrop). Pulsed
            //     by miniMotion: fires on every track change for
            //     ~600 ms, then decays to 0 — the "wave-from-the-
            //     track" effect the user explicitly asked to keep.
            val miniBackdrop = rememberGraphicsLayer()
            val appBackdrop = LocalBackdropLayer.current
            val appBackdropOrigin = LocalBackdropOrigin.current
            var miniPosInRoot by remember { mutableStateOf(Offset.Zero) }
            // Pixel-space versions of the user's blur and chroma
            // settings, fed directly into the always-on backdrop
            // lens so the AGSL shader frosts + RGB-splits the
            // sampled app pixels.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val theme0 = LocalSCTheme.current
            val blurPx = with(density) {
                (theme0.glassBlur.coerceIn(0f, 1f) * 24.dp.toPx())
                    .coerceAtMost(24.dp.toPx())
            }
            val chromaPx = theme0.glassChroma.coerceIn(0f, 1f) * 18f
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { miniPosInRoot = it.positionInRoot() },
                shape = miniShape,
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Layer 2: always-on backdrop rim lens.
                    // Drawn UNDER the strip content so the labels
                    // remain crisp — the refraction only shows where
                    // the strip content is transparent / behind the
                    // rounded edges of the pill. Blur and chromatic
                    // aberration from the user's Settings are
                    // applied to the captured pixels by chaining a
                    // BlurEffect with the AGSL refraction shader,
                    // and by RGB-splitting in-shader (chromaPx).
                    // The rim band is overridden to a thin strip so
                    // on the short mini-player the distortion stays
                    // pinned to the edges instead of forming a wide
                    // oval blob along the right side.
                    if (appBackdrop != null) {
                        LiquidGlassCapsule(
                            modifier = Modifier.matchParentSize(),
                            cornerRadiusDp = 20.dp,
                            backdropLayer = appBackdrop,
                            motionAmount = 1f,
                            refractionHeightDpOverride = 10.dp,
                            refractionAmountDpOverride = 14.dp,
                            blurRadiusPx = blurPx,
                            chromaShiftPx = chromaPx,
                            backdropOffset = {
                                Offset(
                                    miniPosInRoot.x - appBackdropOrigin.x,
                                    miniPosInRoot.y - appBackdropOrigin.y,
                                )
                            },
                        )
                    }

                    // Layer 3: strip content, also captured into
                    // miniBackdrop so the per-track wave (layer 4)
                    // has something to refract. The recording lets
                    // the wave lens see the labels/artwork; the
                    // direct drawContent() inside record() is what
                    // actually puts them on screen because the
                    // miniBackdrop layer is sampled separately by
                    // the wave overlay above.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawWithContent {
                                miniBackdrop.record { this@drawWithContent.drawContent() }
                                drawLayer(miniBackdrop)
                            }
                    ) { content() }

                    // Layer 4: the per-track wave — short pulse of
                    // rim refraction over the strip's own captured
                    // content (artwork + title). motionAmount
                    // is driven by miniMotion which snaps to 1 on
                    // track change and decays to 0 over ~600 ms.
                    LiquidGlassCapsule(
                        modifier = Modifier.matchParentSize(),
                        cornerRadiusDp = 20.dp,
                        backdropLayer = miniBackdrop,
                        motionAmount = miniMotion.value,
                    )
                }
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
