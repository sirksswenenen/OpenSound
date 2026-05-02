package com.soundcloud.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.soundcloud.lite.ui.theme.LocalSCTheme

/** Legacy opt-out flag — unused by the new non-Haze path but kept
 *  so older call sites still compile. */
val LocalSafeForGlass = compositionLocalOf { true }

/** Captured frame containing everything **behind** Liquid Glass panels.
 *  `null` when Liquid Glass is disabled. */
val LocalBackdropLayer = compositionLocalOf<GraphicsLayer?> { null }

/** Top-left position of the backdrop capture box in root coordinates.
 *  Needed to align per-panel sampling correctly. */
val LocalBackdropOrigin = compositionLocalOf { Offset.Zero }

/** Device tilt normalised to (-1, 1). Drives dynamic side reflections. */
val LocalTilt = compositionLocalOf { Offset.Zero }

/** Pre-blurred snapshot of the backdrop content, captured periodically
 *  on a background coroutine, StackBlur'd, and made available to all
 *  glass panels. `null` when blur is disabled or the bitmap hasn't
 *  been produced yet. The bitmap may be at reduced resolution — its
 *  size relative to the panel size is the source-to-destination
 *  scale used when sampling. */
val LocalBlurredBackdrop = compositionLocalOf<ImageBitmap?> { null }

/** The downscale factor used to capture [LocalBlurredBackdrop]. Glass
 *  panels need this to map their on-screen position to the bitmap
 *  coordinate system. */
val LocalBlurredBackdropScale = compositionLocalOf { 1f }

/** Compatibility shim for the old Haze-based BackdropHost. */
@Composable
fun BackdropHost(content: @Composable () -> Unit) { content() }

/**
 * Translucent "Liquid Glass" surface. When [LocalBackdropLayer] is
 * supplied the panel resamples the captured app frame behind it with:
 *  - a multi-pass offset box-blur (cheap but real blur),
 *  - optional chromatic aberration (R / B channels sampled with a
 *    horizontal offset),
 *  - a tint gradient on top,
 *  - two tilt-driven specular side reflections.
 * All effects use only `Brush` / `translate` / `GraphicsLayer` and
 * **no RenderEffect or RuntimeShader** — safe on drivers that crash
 * on GPU blur/shader primitives (e.g. Adreno 660 / MIUI A13).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    dark: Boolean = LocalSCTheme.current.glassDark,
    enabled: Boolean = LocalSCTheme.current.glassEnabled,
    content: @Composable () -> Unit
) {
    val theme = LocalSCTheme.current
    val effectiveShape: Shape = shape
        ?: CircularCornerShape(theme.glassRadius.coerceAtLeast(0f).dp)

    if (!enabled) {
        Box(
            modifier = modifier
                .clip(effectiveShape)
                .background(theme.surface)
                .border(1.dp, Color.White.copy(alpha = 0.05f), effectiveShape)
        ) {
            content()
        }
        return
    }

    // Glass tuning
    val tintScale = theme.glassTint.coerceIn(0f, 1f)
    val reflectionScale = theme.glassReflection.coerceIn(0f, 1f)
    val blurAmount = theme.glassBlur.coerceIn(0f, 1f)
    val chromaAmount = theme.glassChroma.coerceIn(0f, 1f)
    val refraction = theme.glassRefraction.coerceIn(0f, 1f)

    // Tint colour chosen by the user (alpha channel is overwritten per-stop).
    val tintBase = Color(theme.glassTintColor.toInt())
    val darkenBottom = if (dark) Color.Black else Color.White.copy(alpha = 0f)

    val tintTop = tintBase.copy(alpha = (if (dark) 0.22f else 0.60f) * tintScale)
    val tintMid = tintBase.copy(alpha = (if (dark) 0.10f else 0.22f) * tintScale)
    val tintBottom = darkenBottom.copy(alpha = (if (dark) 0.40f else 0.08f) * tintScale)

    val topHighlight: Color
    val bottomHighlight: Color
    val sideHighlight: Color
    if (dark) {
        topHighlight    = Color.White.copy(alpha = 0.28f)
        bottomHighlight = Color.White.copy(alpha = 0.08f)
        sideHighlight   = Color.White.copy(alpha = 0.55f)
    } else {
        topHighlight    = Color.White.copy(alpha = 0.55f)
        bottomHighlight = Color.White.copy(alpha = 0.20f)
        sideHighlight   = Color.White.copy(alpha = 0.70f)
    }

    val backdropLayer = LocalBackdropLayer.current
    val backdropOrigin = LocalBackdropOrigin.current
    val tilt = LocalTilt.current
    val useGpu = theme.glassUseGpuEffects

    var panelPos by remember { mutableStateOf(Offset.Zero) }

    val chromaShift = chromaAmount * 35f            // 0..35 px horizontal channel split

    // GPU-blur radius in dp.  Modifier.blur sets
    // RenderEffect.createBlurEffect under the hood.  Adreno 660 /
    // MIUI A13 silently returns a transparent buffer when the
    // requested blur radius is too large (user reported the panel
    // becomes see-through at slider > ~50 %).  Cap at 25 dp so the
    // driver always succeeds — the trade-off is that the slider
    // saturates at ~50 % visually, but it never disappears.
    val gpuBlurDp = (blurAmount * 50f).coerceAtLeast(0f).coerceAtMost(25f).dp
    val gpuZoom = 1f + refraction * 0.6f

    // Pre-blurred backdrop bitmap supplied by AppRoot. Periodically
    // re-captured + Stack Blurred on a background coroutine. Each
    // panel just blits its panel-aligned slice of this bitmap —
    // there is no GPU blur involved, so the result is reliably
    // opaque (unlike RenderEffect/BlurEffect on this device, which
    // produces a partially-transparent layer that lets the sharp
    // backdrop content show through).
    val blurredBackdrop = LocalBlurredBackdrop.current
    val backdropScale = LocalBlurredBackdropScale.current

    // Channel-isolation matrices: each one zeros two of the three
    // colour channels, leaving only one. Drawing the same source
    // three times with these filters and BlendMode.Plus reconstructs
    // the colour at flat areas but leaves a per-channel offset on
    // sharp edges — i.e. real chromatic aberration. We use an
    // explicit ColorMatrix because BlendMode.Modulate-based tinting
    // on a GraphicsLayer does not always isolate channels reliably.
    val redOnly = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    val greenOnly = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
    val blueOnly = ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )

    // Base panel
    Box(
        modifier = modifier
            .clip(effectiveShape)
            .onGloballyPositioned { panelPos = it.positionInRoot() }
    ) {
        // -----------------------------------------------------------
        // Blurred backdrop child.
        //
        // Reads the pre-Stack-Blurred bitmap from
        // [LocalBlurredBackdrop] and draws the panel-aligned slice
        // of it. With chroma > 0 the slice is drawn three times
        // with R/G/B ColorMatrix filters and ±chromaShift x-offsets
        // so that flat areas reconstruct the original colour and
        // sharp edges show real channel-split aberration. Falls
        // back to the live (unblurred) backdrop layer when the
        // blurred bitmap isn't ready yet (first ~50 ms after
        // enabling Liquid Glass).
        // -----------------------------------------------------------
        if (backdropLayer != null && useGpu) {
            // -------------------------------------------------------
            // GPU path. The backdrop child Box becomes its own
            // graphics layer with `Modifier.blur(...)` (which under
            // the hood sets RenderEffect.createBlurEffect on the
            // node).  Inside drawBehind we paint a translated +
            // optionally zoomed slice of the captured backdrop
            // layer.  All the heavy work (full-screen sample,
            // downsample, blur) happens on the render thread, so the
            // panel tracks the live UI at 60 fps.
            //
            // Chromatic aberration is implemented as three
            // Plus-blended drawLayer passes with R, G and B
            // ColorMatrix filters set on backdropLayer between
            // calls — all wrapped in a saveLayer so the additive
            // blends accumulate only on the offscreen and don't
            // wash out the parent canvas.  No CPU bitmap roundtrip
            // is needed.
            // -------------------------------------------------------
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(gpuBlurDp)
                    .drawBehind {
                        val dx = -(panelPos.x - backdropOrigin.x)
                        val dy = -(panelPos.y - backdropOrigin.y)
                        val w = size.width
                        val h = size.height
                        if (w < 1f || h < 1f) return@drawBehind
                        val refractionEnabled = gpuZoom > 1.001f
                        // Helper: given a translation amount (dx',
                        // dy') paint backdropLayer with optional
                        // refraction zoom centred on the panel.
                        fun paintSlice(tdx: Float, tdy: Float) {
                            translate(tdx, tdy) {
                                if (refractionEnabled) {
                                    // Pivot in CURRENT (translated)
                                    // local coords: panel canvas
                                    // centre is at (w/2, h/2), which
                                    // in translated coords is
                                    // (w/2 - tdx, h/2 - tdy).
                                    scale(
                                        gpuZoom, gpuZoom,
                                        pivot = Offset(
                                            w / 2f - tdx,
                                            h / 2f - tdy,
                                        ),
                                    ) {
                                        drawLayer(backdropLayer)
                                    }
                                } else {
                                    drawLayer(backdropLayer)
                                }
                            }
                        }
                        if (chromaAmount > 0.02f) {
                            // Reset baseline — each channel pass
                            // composites the layer through a Paint
                            // applied at saveLayer/restore, NOT via
                            // backdropLayer.colorFilter (mutating
                            // the shared layer's properties between
                            // drawLayer calls is unreliable: in
                            // practice the last-set filter applied
                            // to all three passes, so the chroma
                            // collapsed into a flat horizontal
                            // shift).
                            backdropLayer.alpha = 1f
                            backdropLayer.blendMode = BlendMode.SrcOver
                            backdropLayer.colorFilter = null
                            drawIntoCanvas { canvas ->
                                val rect = androidx.compose.ui
                                    .geometry.Rect(0f, 0f, w, h)
                                // Outer accumulator so the three
                                // Plus passes only sum with each
                                // other, never with the parent
                                // canvas (which is the .blur layer
                                // surface — having Plus here would
                                // ruin the blur).
                                canvas.saveLayer(rect, Paint())
                                val redPaint = Paint().apply {
                                    blendMode = BlendMode.Plus
                                    colorFilter = redOnly
                                }
                                canvas.saveLayer(rect, redPaint)
                                paintSlice(dx - chromaShift, dy)
                                canvas.restore()
                                val greenPaint = Paint().apply {
                                    blendMode = BlendMode.Plus
                                    colorFilter = greenOnly
                                }
                                canvas.saveLayer(rect, greenPaint)
                                paintSlice(dx, dy)
                                canvas.restore()
                                val bluePaint = Paint().apply {
                                    blendMode = BlendMode.Plus
                                    colorFilter = blueOnly
                                }
                                canvas.saveLayer(rect, bluePaint)
                                paintSlice(dx + chromaShift, dy)
                                canvas.restore()
                                canvas.restore()
                            }
                        } else {
                            backdropLayer.alpha = 1f
                            backdropLayer.blendMode = BlendMode.SrcOver
                            backdropLayer.colorFilter = null
                            paintSlice(dx, dy)
                        }
                    }
            )
        } else if (backdropLayer != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        val dx = -(panelPos.x - backdropOrigin.x)
                        val dy = -(panelPos.y - backdropOrigin.y)
                        val w = size.width
                        val h = size.height
                        if (w < 1f || h < 1f) return@drawBehind

                        // Reset shared backdrop state.
                        backdropLayer.alpha = 1f
                        backdropLayer.blendMode = BlendMode.SrcOver
                        backdropLayer.colorFilter = null

                        val bmp = blurredBackdrop
                        // Use the captured bitmap whenever it's
                        // available — that means: blur is on, OR
                        // chroma is on (the worker keeps publishing a
                        // sharp downsampled snapshot in the chroma-
                        // only case so this branch can still do the
                        // R/G/B aberration pass). The fallback is
                        // only for the brief window after enabling
                        // Liquid Glass before the first capture lands.
                        if (bmp != null && (blurAmount > 0.02f || chromaAmount > 0.02f || refraction > 0.02f)) {
                            // Map panel rect → bitmap rect.
                            val sx0 = (-dx * backdropScale).toInt()
                                .coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
                            val sy0 = (-dy * backdropScale).toInt()
                                .coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
                            val sw0 = (w * backdropScale).toInt()
                                .coerceAtMost(bmp.width - sx0)
                                .coerceAtLeast(1)
                            val sh0 = (h * backdropScale).toInt()
                                .coerceAtMost(bmp.height - sy0)
                                .coerceAtLeast(1)

                            // Refraction = "lens magnification". The
                            // panel acts as a slight convex lens by
                            // sampling a SMALLER central region of the
                            // captured backdrop and stretching it to
                            // the panel size. zoom factor 1.0..1.6.
                            val zoom = 1f + refraction * 0.6f
                            val sw = (sw0 / zoom).toInt().coerceAtLeast(1)
                            val sh = (sh0 / zoom).toInt().coerceAtLeast(1)
                            val sx = (sx0 + (sw0 - sw) / 2)
                                .coerceIn(0, (bmp.width - sw).coerceAtLeast(0))
                            val sy = (sy0 + (sh0 - sh) / 2)
                                .coerceIn(0, (bmp.height - sh).coerceAtLeast(0))

                            val dstSize = IntSize(w.toInt(), h.toInt())

                            if (chromaAmount > 0.02f) {
                                // Render the three Plus-blended channel
                                // copies into a TEMPORARY OFFSCREEN
                                // layer first. Without saveLayer the
                                // BlendMode.Plus passes additively
                                // brighten whatever the live UI behind
                                // the panel painted into the parent
                                // canvas (panel wash-out: "blur почти
                                // полностью пропадает, стекло становится
                                // светлее"). saveLayer makes the three
                                // passes start on a transparent offscreen
                                // surface, so Plus only sums the bitmap
                                // channels with each other. restore()
                                // composites the result onto the panel
                                // with normal SrcOver, preserving the
                                // blurred-bitmap appearance plus R/G/B
                                // edge fringing.
                                drawIntoCanvas { canvas ->
                                    val rect = androidx.compose.ui.geometry
                                        .Rect(0f, 0f, w, h)
                                    canvas.saveLayer(rect, Paint())
                                    drawImage(
                                        image = bmp,
                                        srcOffset = IntOffset(sx, sy),
                                        srcSize = IntSize(sw, sh),
                                        dstOffset = IntOffset(-chromaShift.toInt(), 0),
                                        dstSize = dstSize,
                                        colorFilter = redOnly,
                                        blendMode = BlendMode.Plus,
                                        filterQuality = FilterQuality.Low,
                                    )
                                    drawImage(
                                        image = bmp,
                                        srcOffset = IntOffset(sx, sy),
                                        srcSize = IntSize(sw, sh),
                                        dstOffset = IntOffset.Zero,
                                        dstSize = dstSize,
                                        colorFilter = greenOnly,
                                        blendMode = BlendMode.Plus,
                                        filterQuality = FilterQuality.Low,
                                    )
                                    drawImage(
                                        image = bmp,
                                        srcOffset = IntOffset(sx, sy),
                                        srcSize = IntSize(sw, sh),
                                        dstOffset = IntOffset(chromaShift.toInt(), 0),
                                        dstSize = dstSize,
                                        colorFilter = blueOnly,
                                        blendMode = BlendMode.Plus,
                                        filterQuality = FilterQuality.Low,
                                    )
                                    canvas.restore()
                                }
                            } else {
                                drawImage(
                                    image = bmp,
                                    srcOffset = IntOffset(sx, sy),
                                    srcSize = IntSize(sw, sh),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = dstSize,
                                    filterQuality = FilterQuality.Low,
                                )
                            }
                        } else {
                            // Fallback — no blur or bitmap not ready.
                            // Draw the live backdrop slice unblurred.
                            translate(dx, dy) { drawLayer(backdropLayer) }
                        }
                    }
            )
        }

        // Tint gradient on top of the blurred backdrop.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(tintTop, tintMid, tintBottom)))
        )
        // Top specular strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(Brush.verticalGradient(0.0f to topHighlight, 1.0f to Color.Transparent))
                .align(Alignment.TopCenter)
        )
        // Bottom specular strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(Brush.verticalGradient(0.0f to Color.Transparent, 1.0f to bottomHighlight))
                .align(Alignment.BottomCenter)
        )
        // Tilt-driven side reflections — brightness of the left/right
        // strips shifts with device lean, mimicking a real glass rim
        // catching ambient light.
        if (reflectionScale > 0.01f) {
            val leanX = tilt.x.coerceIn(-1f, 1f)
            val leftA = ((1f - leanX) * 0.5f).coerceIn(0f, 1f) * reflectionScale
            val rightA = ((1f + leanX) * 0.5f).coerceIn(0f, 1f) * reflectionScale
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to sideHighlight.copy(alpha = sideHighlight.alpha * leftA),
                                0.10f to Color.Transparent,
                                0.90f to Color.Transparent,
                                1.0f to sideHighlight.copy(alpha = sideHighlight.alpha * rightA),
                            )
                        )
                    )
            )
        }
        content()
    }
}

/**
 * A rounded-corner shape that always produces **circular** corners
 * (rx == ry), shrinking the radius uniformly when the panel is too
 * short or too narrow to fit a full circle on every corner. The
 * stock `RoundedCornerShape` clamps the X and Y radii independently
 * which on short panels produces visible elliptical corners with a
 * cusp where the curve meets the straight edge.
 */
class CircularCornerShape(private val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val rPx = with(density) { radius.toPx() }
        val maxR = minOf(size.width, size.height) / 2f
        val r = rPx.coerceIn(0f, maxR)
        return Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, size.width, size.height),
                cornerRadius = CornerRadius(r, r),
            )
        )
    }
}


