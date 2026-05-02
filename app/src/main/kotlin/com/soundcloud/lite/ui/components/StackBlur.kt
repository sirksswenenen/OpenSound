package com.soundcloud.lite.ui.components

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stack Blur — a fast pure-CPU box-blur approximation by
 * Mario Klingemann <mario@quasimondo.com> (Java port by Yahel
 * Bouaziz, ported to Kotlin here).
 *
 * Quality is between a single box blur and a true Gaussian:
 * indistinguishable from Gaussian to the eye for radii up to ~20
 * but several times faster. Operates on the bitmap in-place and
 * also returns it for chaining.
 *
 * Used by Liquid Glass instead of a GPU `BlurEffect` /
 * `RuntimeShader` because those primitives crash the Adreno 660
 * driver on this device. CPU is slow but deterministic.
 */
/**
 * Pre-allocated scratch buffers reused by [StackBlur.blurInPlace]
 * across frames to avoid allocating ~2 MB of int[] per blur tick.
 * Hold one per worker coroutine; the buffers grow on demand to fit
 * the largest bitmap the worker has seen.
 */
class StackBlurBuffers {
    var pix: IntArray = IntArray(0)
    var r: IntArray = IntArray(0)
    var g: IntArray = IntArray(0)
    var b: IntArray = IntArray(0)
    var vmin: IntArray = IntArray(0)
}

object StackBlur {
    /**
     * In-place stack blur. Pass a [StackBlurBuffers] to reuse
     * scratch arrays between frames; if null, fresh ones are
     * allocated each call (slow path used only by tests).
     */
    fun blurInPlace(
        bitmap: Bitmap,
        radius: Int,
        buffers: StackBlurBuffers? = null,
    ): Bitmap {
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val area = w * h
        val buf = buffers ?: StackBlurBuffers()
        if (buf.pix.size < area) buf.pix = IntArray(area)
        val pix = buf.pix
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1

        if (buf.r.size < area) buf.r = IntArray(area)
        if (buf.g.size < area) buf.g = IntArray(area)
        if (buf.b.size < area) buf.b = IntArray(area)
        val r = buf.r
        val g = buf.g
        val b = buf.b
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vminSize = max(w, h)
        if (buf.vmin.size < vminSize) buf.vmin = IntArray(vminSize)
        val vmin = buf.vmin

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yi = 0
        yw = yi

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = bsum
            ginsum = rsum
            binsum = ginsum
            rinsum = binsum
            boutsum = rinsum
            goutsum = boutsum
            routsum = goutsum
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = bsum
            ginsum = rsum
            binsum = ginsum
            rinsum = binsum
            boutsum = rinsum
            goutsum = boutsum
            routsum = goutsum
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x

                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - abs(i)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }

                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (-0x1000000 and pix[yi]) or
                        (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
