package com.soundcloud.lite.ui.components

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.soundcloud.lite.ui.theme.LocalSCTheme

/**
 * AGSL refraction shader vendored from
 * https://github.com/Kyant0/AndroidLiquidGlass (Apache 2.0).
 *
 * The shader treats its bound `content` sampler (a GraphicsLayer
 * holding the backdrop pixels) as a flat plane and bends sampling
 * coordinates near the rounded-rect's edges to produce the iOS
 * "Liquid Glass" lens distortion — content close to the rim is
 * pulled outwards (refracted), content near the centre passes
 * through unchanged.
 *
 * Uniforms:
 *  - `size` — pixel dimensions of the lens
 *  - `offset` — translation of (0,0) inside the source layer
 *               (always (0,0) for us; the caller is responsible
 *                for translating the source layer before draw)
 *  - `cornerRadii` — TL, TR, BR, BL radii in pixels
 *  - `refractionHeight` — thickness (px) of the refracting rim
 *  - `refractionAmount` — how far rim coords are pushed outwards
 *                          (px). Higher = stronger lens.
 *  - `depthEffect` — 0..1, blends the rim normal with the radial
 *                     normal for a fish-eye feel; 0 is plain rim.
 *
 * Requires Android 13 (API 33, TIRAMISU) and a working AGSL stack.
 */
private const val RefractionShaderSrc = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(
        gradSdRoundedRect(centeredCoord, halfSize, gradRadius)
            + depthEffect * normalize(centeredCoord)
    );

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

/**
 * iOS-style "Liquid Glass" capsule. Draws the supplied
 * [backdropLayer] (translated so that the source pixels at
 * [backdropOffset] land at the capsule's origin) through an AGSL
 * refraction shader, producing a lens that visibly distorts
 * whatever was rendered into the backdrop layer.
 *
 * Falls back to a soft translucent surface on pre-Tiramisu devices
 * or when no backdrop is supplied.
 */
@Composable
fun LiquidGlassCapsule(
    modifier: Modifier = Modifier,
    cornerRadiusDp: androidx.compose.ui.unit.Dp = 24.dp,
    backdropLayer: GraphicsLayer? = null,
    /**
     * Offset, in pixels, of the capsule's top-left inside the
     * backdrop layer's coordinate space. Read on every draw so an
     * animated capsule position stays in sync with the refraction.
     */
    backdropOffset: () -> Offset = { Offset.Zero },
    /**
     * 0..1 gate on the AGSL refraction. At 0 the rim distortion is
     * effectively disabled and the capsule shows the captured
     * backdrop straight through; at 1 the full lens runs. Callers
     * typically ramp this 0 → 1 → 0 during a move animation so the
     * iOS "liquid" warp only fires while the pill is sliding and
     * the underlying icons read crisply when it is at rest.
     */
    motionAmount: Float = 1f,
) {
    val theme = LocalSCTheme.current
    val density = LocalDensity.current

    // 0..1 user setting controlling how aggressive the refraction
    // is. We always want SOME lens, otherwise the pill turns into a
    // plain tinted box - so we clamp the lower end above zero.
    val refractAmount = theme.glassBlur.coerceIn(0f, 1f)
    // Bumped from the previous (8+22) / (10+28) defaults: under the
    // motion gate the visible warp lives for ~200ms during a slide
    // and was reading as barely-there on screen. Increasing both the
    // band height and the displacement makes the lens visibly distort
    // the icon row when the pill is in flight without affecting the
    // rest state (motion=0 still gives zero warp).
    val refractionHeightPx = with(density) {
        (12.dp + 32.dp * refractAmount).toPx()
    }
    val refractionAmountPx = with(density) {
        (14.dp + 42.dp * refractAmount).toPx()
    }

    val highlightStrength = (theme.glassHighlight).coerceIn(0f, 1f)
    val tintStrength = (theme.glassTint).coerceIn(0f, 1f)

    val shape = RoundedCornerShape(cornerRadiusDp)

    val canRefract =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdropLayer != null

    if (!canRefract) {
        // No GPU shader path available — give the user a still
        // recognisable frosted pill instead of nothing.
        Box(
            modifier = modifier
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f + 0.10f * tintStrength)),
        ) {
            CapsuleHighlight(highlightStrength)
        }
        return
    }

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val cornerRadiusPx = with(density) { cornerRadiusDp.toPx() }

    // Bucket motion to the nearest 0.02 so we don't churn a new
    // RenderEffect on every single frame for sub-pixel motion
    // changes. Reading the motion here makes this composable
    // recompose during the slide animation, but only this small
    // subtree (the capsule), and only while motion is non-zero.
    val gatedMotion = (motionAmount.coerceIn(0f, 1f) * 50f).toInt() / 50f
    val effectiveRefraction = -refractionAmountPx * gatedMotion

    val renderEffect = remember(sizePx, cornerRadiusPx, refractionHeightPx, effectiveRefraction) {
        if (sizePx.width <= 0 || sizePx.height <= 0) {
            null
        } else {
            val shader = RuntimeShader(RefractionShaderSrc)
            shader.setFloatUniform(
                "size",
                sizePx.width.toFloat(),
                sizePx.height.toFloat(),
            )
            shader.setFloatUniform("offset", 0f, 0f)
            shader.setFloatUniform(
                "cornerRadii",
                cornerRadiusPx,
                cornerRadiusPx,
                cornerRadiusPx,
                cornerRadiusPx,
            )
            shader.setFloatUniform("refractionHeight", refractionHeightPx)
            // Shader pushes coords outwards along the gradient when
            // `refractionAmount` is negative - matching how the
            // upstream library invokes it. We multiply by motion
            // so at rest the displacement is exactly 0 (no warp).
            shader.setFloatUniform("refractionAmount", effectiveRefraction)
            shader.setFloatUniform("depthEffect", 0f)
            AndroidRenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }

    Box(modifier = modifier.clip(shape)) {
        // Refraction lens - drawBehind paints the backdrop layer
        // INTO this Box's graphicsLayer; the layer's renderEffect
        // then refracts those pixels via the AGSL shader before
        // they hit the screen.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { sizePx = it }
                .graphicsLayer {
                    this.renderEffect = renderEffect
                    // Offscreen so the renderEffect sees the layer's
                    // pixels (drawBehind contents) as `content`.
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawBehind {
                    val off = backdropOffset()
                    // The shader samples `content` at coord =
                    // capsule pixel. So we need the backdrop's
                    // pixel at (off.x + capsule_x, off.y +
                    // capsule_y) to land at (capsule_x, capsule_y).
                    // Achieved by translating the backdrop layer
                    // by -off before drawing it.
                    withTransform({ translate(-off.x, -off.y) }) {
                        drawLayer(backdropLayer)
                    }
                },
        )
        // Glossy rim highlight rendered OUTSIDE the refraction
        // layer so it stays a crisp specular gradient instead of
        // being warped by the shader.
        CapsuleHighlight(highlightStrength)
    }
}

/**
 * Thin specular highlight along the top edge of the capsule.
 * Drawn ABOVE the refraction so the pill always reads as a
 * glossy 3-D bead and not a flat lens.
 */
@Composable
private fun CapsuleHighlight(strength: Float) {
    if (strength <= 0.01f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.30f * strength),
                    0.35f to Color.White.copy(alpha = 0.10f * strength),
                    1f to Color.Transparent,
                ),
            ),
    )
}
