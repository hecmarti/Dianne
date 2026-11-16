package com.dianne.krugerscorer.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight grayscale + working buffers for the pure-Kotlin vision pipeline.
 * Avoids OpenCV so the APK stays small and sideloading stays simple.
 */
class ImageBuffer private constructor(
    val width: Int,
    val height: Int,
    val gray: FloatArray,
    val work: FloatArray
) {
    fun index(x: Int, y: Int): Int = y * width + x

    fun get(x: Int, y: Int): Float = gray[index(x, y)]

    fun copyGrayTo(dest: FloatArray) {
        System.arraycopy(gray, 0, dest, 0, gray.size)
    }

    companion object {
        fun fromBitmap(source: Bitmap, maxSide: Int = 900): Pair<ImageBuffer, Bitmap> {
            val scaled = scaleDown(source, maxSide)
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = FloatArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                gray[i] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
            return ImageBuffer(w, h, gray, FloatArray(w * h)) to scaled
        }

        private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
            val longest = max(source.width, source.height)
            if (longest <= maxSide) {
                return if (source.config == Bitmap.Config.ARGB_8888) {
                    source
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            val scale = maxSide.toFloat() / longest
            val w = max(1, (source.width * scale).roundToInt())
            val h = max(1, (source.height * scale).roundToInt())
            return Bitmap.createScaledBitmap(source, w, h, true)
        }
    }
}

internal fun FloatArray.blurBox(width: Int, height: Int, radius: Int, dest: FloatArray) {
    if (radius <= 0) {
        System.arraycopy(this, 0, dest, 0, size)
        return
    }
    val temp = FloatArray(size)
    val diameter = radius * 2 + 1

    // Horizontal
    for (y in 0 until height) {
        var sum = 0f
        val row = y * width
        for (x in -radius..radius) {
            val xx = min(width - 1, max(0, x))
            sum += this[row + xx]
        }
        for (x in 0 until width) {
            temp[row + x] = sum / diameter
            val leave = min(width - 1, max(0, x - radius))
            val enter = min(width - 1, max(0, x + radius + 1))
            sum += this[row + enter] - this[row + leave]
        }
    }

    // Vertical
    for (x in 0 until width) {
        var sum = 0f
        for (y in -radius..radius) {
            val yy = min(height - 1, max(0, y))
            sum += temp[yy * width + x]
        }
        for (y in 0 until height) {
            dest[y * width + x] = sum / diameter
            val leave = min(height - 1, max(0, y - radius))
            val enter = min(height - 1, max(0, y + radius + 1))
            sum += temp[enter * width + x] - temp[leave * width + x]
        }
    }
}
