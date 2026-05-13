package com.soundcloud.lite.ui.components

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import com.soundcloud.lite.ui.theme.LocalSCTheme

/**
 * The AGSL fragment shader that implements iOS-style "Liquid Glass"
 * refraction.
 *
 * - **Lensing**: pixels near the rim of the capsule sample the backdrop
 *   from further outside, giving the impression of a curved-glass bead
 *   bending the content behind it. The displacement falls off quadratically
 *   with distance to centre so the middle of the panel shows the backdrop
 *   unwarped (clear glass) while the edges show a visible refraction lip.
 * - **Chromatic aberration**: per-channel offset along the lens gradient
 *   produces the colourful "rainbow" sliver at the rim that's the visual
 *   signature of iOS 16+ Liquid Glass.
 * - **Specular crescent**: a soft crescent of brightness near the top
 *   edge of the capsule, scaled by the `highlight` uniform — drives the
 *   "lit-from-above" look. Setting `highlight` to 0 from Settings turns
 *   it off completely.
 * - **Side-edge tint**: subtle white falloff at the very left and right
 *   ends of the capsule that reads as the lit rim of the glass bead.
 */
private const val LIQUID_GLASS_AGSL = """
    uniform shader backdrop;
    uniform float2 size;
    uniform float refraction;
    uniform float chroma;
    uniform float highlight;

    half4 main(float2 fragCoord) {
        // Centered normalized coords in (-1, 1).
        float2 uv = (fragCoord / size) * 2.0 - 1.0;

        // Radial distance with a slight x-bias so the rim displacement
        // distributes evenly along the capsule's long axis. A pure
        // length() would put all the refraction at the rounded ends.
        float aspect = size.x / max(size.y, 1.0);
        float2 c = float2(uv.x * 0.55, uv.y);
        float r = length(c);
        float r2 = r * r;
        float disp = refraction * r2 * 0.55;

        // Outward unit vector for lensing.
        float2 dir = c / max(r, 1e-4);
        float2 displaced = fragCoord + dir * disp * min(size.x, size.y);

        // Chromatic aberration: small per-channel offset along the
        // refraction direction. Strength scales both with the chroma
        // uniform and with how close we are to the rim — so the
        // centre stays neutral and the colour fringes ride the edge.
        float ca = chroma * 4.0 * r;
        half4 cr = backdrop.eval(displaced + dir * ca);
        half4 cg = backdrop.eval(displaced);
        half4 cb = backdrop.eval(displaced - dir * ca);
        half3 color = half3(cr.r, cg.g, cb.b);

        // Top specular crescent — bright lip near the top of the
        // capsule, tapering off downwards. uv.y is -1 at the top.
        float topArc = pow(max(0.0, -uv.y - 0.45), 1.6);
        color += half3(topArc * highlight * 0.85);

        // Side rim lighting at the very ends of the capsule.
        float sideRim = pow(abs(uv.x), 6.0);
        color += half3(sideRim * highlight * 0.30);

        return half4(color, 1.0);
    }
"""

/**
 * iOS-style Liquid Glass capsule. Renders the slice of the captured
 * (and pre-blurred) backdrop bitmap that lies behind this composable,
 * pushed through an AGSL [RuntimeShader] that adds curved-lens
 * refraction + chromatic aberration + a top specular crescent.
 *
 * Falls back to a plain semi-transparent tinted box on Android < 13
 * (where [RuntimeShader] isn't available). Set `highlight` to 0 to
 * suppress the rim lighting entirely.
 */
@Composable
fun LiquidGlassCapsule(
    modifier: Modifier = Modifier,
    shape: Shape,
    refractionAmount: Float = 0.8f,
    chroma: Float = 0.30f,
    highlight: Float = 0.45f,
    tintColor: Color = Color.White.copy(alpha = 0.12f),
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Pre-API 33 fallback — just a soft translucent tinted box.
        Box(
            modifier = modifier
                .clip(shape)
                .background(tintColor),
        )
        return
    }
    LiquidGlassCapsuleApi33(
        modifier = modifier,
        shape = shape,
        refractionAmount = refractionAmount,
        chroma = chroma,
        highlight = highlight,
        tintColor = tintColor,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LiquidGlassCapsuleApi33(
    modifier: Modifier,
    shape: Shape,
    refractionAmount: Float,
    chroma: Float,
    highlight: Float,
    tintColor: Color,
) {
    val blurredBackdrop = LocalBlurredBackdrop.current
    val backdropScale = LocalBlurredBackdropScale.current
    val backdropOrigin = LocalBackdropOrigin.current
    val theme = LocalSCTheme.current
    var panelPos by remember { mutableStateOf(Offset.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    val shader = remember { RuntimeShader(LIQUID_GLASS_AGSL) }
    val androidPaint = remember { AndroidPaint().apply { isAntiAlias = true } }

    // Scale the user's `glassHighlight` setting into the shader. 0 from
    // settings = no specular at all, matching the "fully off" request.
    val effectiveHighlight = highlight * theme.glassHighlight.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned {
                panelPos = it.positionInRoot()
                panelSize = it.size
            }
            .drawWithCache {
                val composeBmp = blurredBackdrop
                onDrawWithContent {
                    val w = size.width
                    val h = size.height
                    if (w < 1f || h < 1f) {
                        drawContent()
                        return@onDrawWithContent
                    }
                    if (composeBmp != null) {
                        val androidBmp = composeBmp.asAndroidBitmap()
                        // Build a BitmapShader whose local matrix maps the
                        // shader's (0, 0) origin to the bitmap-space pixel
                        // currently behind this panel's top-left corner.
                        val bmpShader = BitmapShader(
                            androidBmp,
                            AndroidShader.TileMode.CLAMP,
                            AndroidShader.TileMode.CLAMP,
                        )
                        // The captured backdrop bitmap covers the
                        // recorded area which starts at backdropOrigin
                        // in root coords (typically just below the
                        // status bar), and was scaled down by
                        // backdropScale. We want the slice that sits
                        // BEHIND this panel — so subtract the origin
                        // from the panel's root position before
                        // scaling into bitmap space.
                        val relX = panelPos.x - backdropOrigin.x
                        val relY = panelPos.y - backdropOrigin.y
                        val m = Matrix().apply {
                            // First scale panel-space (px) UP by
                            // 1 / backdropScale so that 1 px in the
                            // bitmap maps to 1 / scale px on the panel.
                            postScale(
                                1f / backdropScale,
                                1f / backdropScale,
                            )
                            // Then translate the bitmap so the pixel
                            // that lives at (relX, relY) in panel
                            // root-space lands at (0, 0) of the panel.
                            postTranslate(-relX, -relY)
                        }
                        bmpShader.setLocalMatrix(m)
                        shader.setInputShader("backdrop", bmpShader)
                        shader.setFloatUniform("size", w, h)
                        shader.setFloatUniform("refraction", refractionAmount)
                        shader.setFloatUniform("chroma", chroma)
                        shader.setFloatUniform("highlight", effectiveHighlight)
                        androidPaint.shader = shader
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawRect(0f, 0f, w, h, androidPaint)
                        }
                    }
                    // Tint pass on top so the capsule still reads as a
                    // distinct surface even if the backdrop is similar
                    // to the surrounding bar colour.
                    drawIntoCanvas { canvas ->
                        val tintPaint = AndroidPaint().apply {
                            color = android.graphics.Color.argb(
                                (tintColor.alpha * 255).toInt().coerceIn(0, 255),
                                (tintColor.red * 255).toInt().coerceIn(0, 255),
                                (tintColor.green * 255).toInt().coerceIn(0, 255),
                                (tintColor.blue * 255).toInt().coerceIn(0, 255),
                            )
                            isAntiAlias = true
                        }
                        canvas.nativeCanvas.drawRect(0f, 0f, w, h, tintPaint)
                    }
                    drawContent()
                }
            },
    )
}
