package com.soundcloud.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.soundcloud.lite.ui.theme.LocalSCTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * App-wide [HazeState] used by [LiquidGlassCapsule]. Whatever content
 * we want the capsule to refract must be marked with
 * `Modifier.hazeSource(LocalHazeState.current)` somewhere up the tree.
 *
 * In practice we mark:
 *  - the whole `AppNavHost` (so the capsule refracts the scrolling
 *    list / album art behind the bar)
 *  - the row of bottom-nav icons themselves (so the icons distort as
 *    the capsule slides over them — that's the iPhone "Liquid Glass"
 *    signature)
 *
 * The state is provided in [MainActivity]; a default (empty) is
 * returned for previews / tests that don't wire it up.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * The active-tab indicator pill at the bottom of the screen. Renders
 * as a real piece of frosted glass — backdrop blur + tint + a subtle
 * top-edge highlight whose intensity is driven by the user's
 * `glassHighlight` slider (0..1).
 *
 * Implementation uses [Haze](https://github.com/chrisbanes/haze)
 * 1.7.2, which:
 *  - On API 32+ uses `RenderEffect.createBlurEffect` (GPU)
 *  - On lower API falls back to a snapshot+CPU-blur pipeline
 *  - Automatically refraction-tracks position changes
 *
 * Falls back to a plain translucent box if no [LocalHazeState] is
 * provided (defensive — should never happen in the real app).
 */
@Composable
fun LiquidGlassCapsule(
    modifier: Modifier = Modifier,
    shape: Shape,
    /** 0..1 — extra brightness on the top edge for a "lit" glass look.
     *  Multiplied by the global `glassHighlight` slider. */
    highlight: Float = 1.0f,
    /** Base capsule tint; alpha is multiplied by the effective blur so the
     *  pill fades out cleanly when blur is dialled down. */
    tintColor: Color = Color.White.copy(alpha = 0.18f),
) {
    val theme = LocalSCTheme.current
    val hazeState = LocalHazeState.current

    val effectiveHighlight = (highlight * theme.glassHighlight).coerceIn(0f, 1f)
    // Driven directly by user's Settings → Glass surfaces → Blur slider.
    val blurAmount = theme.glassBlur.coerceIn(0f, 1f)
    // Blur radius scales from 4 dp (subtle, "the pill is barely a lens")
    // to 28 dp (fully iOS-Liquid-Glass "everything mushed into frosted").
    val blurRadiusDp = (4f + 24f * blurAmount)
    val tintAlpha = (0.10f + 0.18f * blurAmount).coerceIn(0f, 1f)

    if (hazeState == null) {
        // Defensive fallback: tinted plain box. Shouldn't be hit in app.
        Box(
            modifier = modifier
                .clip(shape)
                .background(tintColor),
        )
        return
    }

    // Haze 1.5.4 throws IllegalArgumentException("backgroundColor not
    // specified") when drawing with Color.Unspecified. Transparent gives
    // the same visual result (no opaque backdrop layer) without crashing.
    val style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(
            HazeTint(tintColor.copy(alpha = tintAlpha)),
        ),
        blurRadius = blurRadiusDp.dp,
        noiseFactor = 0f,
    )

    Box(
        modifier = modifier
            .clip(shape)
            .hazeEffect(state = hazeState, style = style),
    ) {
        // A soft top-edge specular highlight that gives the pill its
        // "polished bead" look. Drawn ABOVE the haze blur as a thin
        // gradient overlay; alpha = 0 fully removes it.
        if (effectiveHighlight > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to Color.White.copy(
                                alpha = 0.35f * effectiveHighlight,
                            ),
                            0.45f to Color.Transparent,
                            1f to Color.White.copy(
                                alpha = 0.06f * effectiveHighlight,
                            ),
                        ),
                    ),
            )
        }
    }
}
