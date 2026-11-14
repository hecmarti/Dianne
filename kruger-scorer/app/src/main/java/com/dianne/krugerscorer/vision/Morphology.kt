package com.dianne.krugerscorer.vision

import kotlin.math.max
import kotlin.math.min

/** Grayscale morphology helpers (no OpenCV). */
internal object Morphology {

    /** Fast separable box min (approximates disk erode for top-hat). */
    fun erodeGray(src: FloatArray, w: Int, h: Int, radius: Int, dest: FloatArray) {
        if (radius <= 0) {
            System.arraycopy(src, 0, dest, 0, src.size)
            return
        }
        val temp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = Float.POSITIVE_INFINITY
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                for (xx in x0..x1) m = min(m, src[row + xx])
                temp[row + x] = m
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var m = Float.POSITIVE_INFINITY
                val y0 = max(0, y - radius)
                val y1 = min(h - 1, y + radius)
                for (yy in y0..y1) m = min(m, temp[yy * w + x])
                dest[y * w + x] = m
            }
        }
    }

    /** Fast separable box max (approximates disk dilate for top-hat). */
    fun dilateGray(src: FloatArray, w: Int, h: Int, radius: Int, dest: FloatArray) {
        if (radius <= 0) {
            System.arraycopy(src, 0, dest, 0, src.size)
            return
        }
        val temp = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = Float.NEGATIVE_INFINITY
                val x0 = max(0, x - radius)
                val x1 = min(w - 1, x + radius)
                for (xx in x0..x1) m = max(m, src[row + xx])
                temp[row + x] = m
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var m = Float.NEGATIVE_INFINITY
                val y0 = max(0, y - radius)
                val y1 = min(h - 1, y + radius)
                for (yy in y0..y1) m = max(m, temp[yy * w + x])
                dest[y * w + x] = m
            }
        }
    }

    /** White top-hat: src - opening(src). Highlights bright spots on dark background. */
    fun whiteTopHat(src: FloatArray, w: Int, h: Int, radius: Int, dest: FloatArray) {
        val tmp = FloatArray(src.size)
        val opened = FloatArray(src.size)
        erodeGray(src, w, h, radius, tmp)
        dilateGray(tmp, w, h, radius, opened)
        for (i in src.indices) {
            dest[i] = max(0f, src[i] - opened[i])
        }
    }

    fun erodeBool(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val r2 = radius * radius
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                var ok = true
                outer@ for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > r2) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h || !mask[ny * w + nx]) {
                            ok = false
                            break@outer
                        }
                    }
                }
                out[y * w + x] = ok
            }
        }
        return out
    }

    fun dilateBool(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        val r2 = radius * radius
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > r2) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = true
                    }
                }
            }
        }
        return out
    }

    fun openBool(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray =
        dilateBool(erodeBool(mask, w, h, radius), w, h, radius)

    fun closeBool(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray =
        erodeBool(dilateBool(mask, w, h, radius), w, h, radius)
}
