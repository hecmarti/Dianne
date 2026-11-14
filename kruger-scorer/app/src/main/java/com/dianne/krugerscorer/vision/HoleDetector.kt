package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detects pellet holes on a photographed Krüger target.
 *
 * Approach (no ML dependency):
 * 1. Build a hole-likelihood map: local contrast vs a blurred background,
 *    polarity-aware inside/outside the black aiming mark.
 * 2. Threshold with a sensitivity-driven cutoff, then morphologically open
 *    to drop thin printed digits (6/7/8/9) while keeping round holes.
 * 3. Distance-transform peak finding separates overlapping / merged holes.
 * 4. Reject peaks on cardinal ring-number loci and non-circular blobs.
 *
 * [sensitivity] ∈ [0, 1]: higher → more detections (lower contrast threshold).
 */
object HoleDetector {

    fun detect(
        image: ImageBuffer,
        calibration: TargetCalibration,
        sensitivity: Float
    ): List<ShotDetection> {
        val sens = sensitivity.coerceIn(0f, 1f)
        val w = image.width
        val h = image.height

        // Pellet ≈ 4.5 mm diameter. blackRadius maps to ring-7 outer radius (11.75 mm).
        val expectedR = (calibration.blackRadius * (2.25f / 11.75f)).coerceIn(4f, 36f)
        val minR = expectedR * (0.55f - sens * 0.12f)
        val maxR = expectedR * (1.35f + sens * 0.25f)

        val likelihood = FloatArray(w * h)
        buildLikelihood(image, calibration, expectedR, likelihood)

        val threshold = percentile(likelihood, 1f - (0.006f + sens * 0.018f))
        val softFloor = threshold * (0.72f - sens * 0.16f)
        val rawMask = BooleanArray(w * h)
        val maxScoreRadius = calibration.blackRadius * 2.05f
        val boundaryInner = calibration.blackRadius - expectedR * 0.9f
        val boundaryOuter = calibration.blackRadius + expectedR * 0.9f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val d = hypot(x - calibration.centerX, y - calibration.centerY)
                // Drop the black-mark rim — it creates strong false contrast.
                val onBoundary = d in boundaryInner..boundaryOuter
                rawMask[i] = !onBoundary && likelihood[i] >= softFloor && d <= maxScoreRadius
            }
        }

        // Opening removes thin strokes of printed ring numbers.
        val openRadius = max(1, (expectedR * 0.35f).roundToInt())
        val mask = morphologicalOpen(rawMask, w, h, openRadius)

        val dist = distanceTransform(mask, w, h)
        val peaks = findPeaks(dist, w, h, minR, maxR, expectedR)

        val detections = ArrayList<ShotDetection>()
        var id = 0
        val occupied = BooleanArray(w * h)

        val ordered = peaks.sortedByDescending { it.third }
        for ((px, py, peakDist) in ordered) {
            val radius = peakDist.coerceIn(minR, maxR)
            if (isOccupied(occupied, w, h, px, py, radius * 0.75f)) continue

            var conf = scorePeak(
                likelihood, image, w, h, px, py, radius, threshold, sens, calibration
            )
            // Printed ring numbers sit on the axes — demand higher confidence there.
            if (isRingNumberLocus(calibration, px, py, radius)) {
                conf *= 0.55f
            }
            val minConf = 0.42f - sens * 0.14f
            if (conf < minConf) continue

            val dCenter = hypot(px - calibration.centerX, py - calibration.centerY)
            if (abs(dCenter - calibration.blackRadius) < expectedR * 1.1f) continue

            markOccupied(occupied, w, h, px, py, radius * 0.9f)

            val score = ScoreMapper.score(calibration, px, py, radius)
            detections.add(
                ShotDetection(
                    id = id++,
                    x = px,
                    y = py,
                    radius = radius,
                    score = score,
                    confidence = conf
                )
            )
        }

        return detections
    }

    /**
     * Printed scores sit on the four cardinal axes at the mid-radius of each ring.
     * Suppress peaks that land on those loci unless they look strongly hole-like later.
     */
    private fun isRingNumberLocus(
        cal: TargetCalibration,
        x: Float,
        y: Float,
        radius: Float
    ): Boolean {
        val dx = x - cal.centerX
        val dy = y - cal.centerY
        val dist = hypot(dx, dy)
        if (dist < cal.blackRadius * 0.12f) return false // allow 10-ring area

        val angle = abs(atan2(dy, dx))
        // Near horizontal or vertical axes (0, ±π/2, π).
        val axisDist = min(
            min(angle, abs(angle - PI.toFloat())),
            abs(angle - PI.toFloat() / 2f)
        )
        if (axisDist > 0.22f) return false // ~12.5°

        // Mid-radii of rings 6–9 relative to black (= ring 7 outer).
        val ringMids = floatArrayOf(
            cal.blackRadius * (5.5f / 23.5f),   // ~10 mid — skip, too central
            cal.blackRadius * (11.5f / 23.5f),  // 9
            cal.blackRadius * (17.5f / 23.5f),  // 8
            cal.blackRadius * (23.5f / 23.5f),  // 7
            cal.blackRadius * (29.5f / 23.5f)   // 6
        )
        for (i in 1 until ringMids.size) {
            if (abs(dist - ringMids[i] * 0.85f) < radius * 1.6f ||
                abs(dist - ringMids[i] * 0.92f) < radius * 1.6f
            ) {
                return true
            }
        }
        // Also cover the visual number placement slightly inside each ring band.
        val bands = floatArrayOf(0.55f, 0.72f, 0.88f, 1.05f, 1.22f)
        for (b in bands) {
            if (abs(dist - cal.blackRadius * b) < radius * 1.4f) return true
        }
        return false
    }

    private fun buildLikelihood(
        image: ImageBuffer,
        calibration: TargetCalibration,
        expectedR: Float,
        out: FloatArray
    ) {
        val w = image.width
        val h = image.height
        val blurR = max(2, (expectedR * 2.4f).roundToInt())
        val local = FloatArray(w * h)
        image.gray.blurBox(w, h, blurR, local)

        val fine = FloatArray(w * h)
        image.gray.blurBox(w, h, max(1, (expectedR * 0.35f).roundToInt()), fine)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val d = hypot(x - calibration.centerX, y - calibration.centerY)
                val insideBlack = d <= calibration.blackRadius * 1.02f
                val value = fine[i]
                val bg = local[i]
                val contrast = if (insideBlack) (value - bg) else (bg - value)
                val radial = exp((-(d / (calibration.blackRadius * 1.7f)).let { it * it })).toFloat()
                out[i] = max(0f, contrast) * (0.30f + 0.70f * radial)
            }
        }
    }

    private fun morphologicalOpen(
        mask: BooleanArray,
        w: Int,
        h: Int,
        radius: Int
    ): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        val eroded = erode(mask, w, h, radius)
        return dilate(eroded, w, h, radius)
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
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

    private fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
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
                        if (nx in 0 until w && ny in 0 until h) {
                            out[ny * w + nx] = true
                        }
                    }
                }
            }
        }
        return out
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        val sample = ArrayList<Float>(values.size / 8)
        var i = 0
        while (i < values.size) {
            if (values[i] > 0f) sample.add(values[i])
            i += 3
        }
        if (sample.isEmpty()) return 20f
        sample.sort()
        val idx = ((sample.size - 1) * p.coerceIn(0f, 1f)).roundToInt()
        return sample[idx]
    }

    private fun distanceTransform(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e8f
        val dist = FloatArray(w * h) { if (mask[it]) inf else 0f }

        for (y in 0 until h) {
            val row = y * w
            for (x in 1 until w) {
                dist[row + x] = min(dist[row + x], dist[row + x - 1] + 1f)
            }
            for (x in w - 2 downTo 0) {
                dist[row + x] = min(dist[row + x], dist[row + x + 1] + 1f)
            }
        }

        val out = FloatArray(w * h)
        val envelope = IntArray(h)
        val parabola = FloatArray(h)
        for (x in 0 until w) {
            var k = 0
            envelope[0] = 0
            parabola[0] = Float.NEGATIVE_INFINITY
            parabola[1] = Float.POSITIVE_INFINITY
            fun f(y: Int) = dist[y * w + x]
            fun sep(i: Int, u: Int): Float {
                val di = f(i)
                val du = f(u)
                return ((u * u - i * i + du * du - di * di) / (2f * (u - i)))
            }
            for (q in 1 until h) {
                var s = sep(envelope[k], q)
                while (s <= parabola[k]) {
                    k--
                    s = sep(envelope[k], q)
                }
                k++
                envelope[k] = q
                parabola[k] = s
                parabola[k + 1] = Float.POSITIVE_INFINITY
            }
            k = 0
            for (q in 0 until h) {
                while (parabola[k + 1] < q) k++
                val p = envelope[k]
                val d = (q - p) * (q - p) + f(p) * f(p)
                out[q * w + x] = if (!mask[q * w + x]) 0f else sqrt(d)
            }
        }
        return out
    }

    private fun findPeaks(
        dist: FloatArray,
        w: Int,
        h: Int,
        minR: Float,
        maxR: Float,
        expectedR: Float
    ): List<Triple<Float, Float, Float>> {
        val peaks = ArrayList<Triple<Float, Float, Float>>()
        val minPeak = minR * 0.75f
        val n = max(1, (expectedR * 0.55f).roundToInt())
        for (y in n until h - n) {
            for (x in n until w - n) {
                val v = dist[y * w + x]
                if (v < minPeak || v > maxR * 1.25f) continue
                var isMax = true
                outer@ for (dy in -n..n) {
                    for (dx in -n..n) {
                        if (dx == 0 && dy == 0) continue
                        if (dist[(y + dy) * w + (x + dx)] > v) {
                            isMax = false
                            break@outer
                        }
                    }
                }
                if (isMax) {
                    var sx = 0f
                    var sy = 0f
                    var sw = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val wv = dist[(y + dy) * w + (x + dx)]
                            sx += (x + dx) * wv
                            sy += (y + dy) * wv
                            sw += wv
                        }
                    }
                    val cx = if (sw > 0f) sx / sw else x.toFloat()
                    val cy = if (sw > 0f) sy / sw else y.toFloat()
                    peaks.add(Triple(cx, cy, v))
                }
            }
        }
        return peaks
    }

    private fun scorePeak(
        likelihood: FloatArray,
        image: ImageBuffer,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        threshold: Float,
        sens: Float,
        calibration: TargetCalibration
    ): Float {
        var sumIn = 0f
        var countIn = 0
        var sumRing = 0f
        var countRing = 0
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        val r2 = radius * radius
        val rOuter = radius * 1.7f
        val rOuter2 = rOuter * rOuter
        val x0 = max(0, (cx - rOuter).toInt())
        val x1 = min(w - 1, (cx + rOuter).toInt())
        val y0 = max(0, (cy - rOuter).toInt())
        val y1 = min(h - 1, (cy + rOuter).toInt())
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                val v = likelihood[y * w + x]
                if (d2 <= r2) {
                    sumIn += v
                    countIn++
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                } else if (d2 <= rOuter2) {
                    sumRing += v
                    countRing++
                }
            }
        }
        if (countIn < 6) return 0f
        val meanIn = sumIn / countIn
        val meanRing = if (countRing > 0) sumRing / countRing else 0f
        val contrast = (meanIn - meanRing).coerceAtLeast(0f)
        val area = countIn.toFloat()
        val idealArea = (PI * radius * radius).toFloat()
        val fill = (area / idealArea).coerceIn(0.2f, 1.4f)
        val fillScore = 1f - abs(1f - fill)

        val bw = (maxX - minX + 1).toFloat().coerceAtLeast(1f)
        val bh = (maxY - minY + 1).toFloat().coerceAtLeast(1f)
        val aspect = min(bw, bh) / max(bw, bh)
        // Digits are elongated; holes are round.
        if (aspect < 0.62f) return 0f

        // Prefer a clear intensity discontinuity (hole edge) over flat printed ink.
        val edgeStrength = localEdgeStrength(image, calibration, cx, cy, radius)

        val contrastScore = (contrast / (threshold + 1f)).coerceIn(0f, 1.5f) / 1.5f
        return (
            0.40f * contrastScore +
                0.25f * fillScore +
                0.20f * aspect +
                0.15f * edgeStrength
            ).coerceIn(0f, 1f) * (0.88f + 0.12f * sens)
    }

    private fun localEdgeStrength(
        image: ImageBuffer,
        calibration: TargetCalibration,
        cx: Float,
        cy: Float,
        radius: Float
    ): Float {
        val steps = 16
        var sum = 0f
        var count = 0
        val dCenter = hypot(cx - calibration.centerX, cy - calibration.centerY)
        val insideBlack = dCenter <= calibration.blackRadius
        for (i in 0 until steps) {
            val ang = (PI * 2.0 * i / steps).toFloat()
            val cos = kotlin.math.cos(ang)
            val sin = kotlin.math.sin(ang)
            val xin = cx + cos * (radius * 0.45f)
            val yin = cy + sin * (radius * 0.45f)
            val xout = cx + cos * (radius * 1.25f)
            val yout = cy + sin * (radius * 1.25f)
            if (xin < 1f || yin < 1f || xin >= image.width - 1f || yin >= image.height - 1f) continue
            if (xout < 1f || yout < 1f || xout >= image.width - 1f || yout >= image.height - 1f) continue
            val vin = sample(image, xin, yin)
            val vout = sample(image, xout, yout)
            // Inside black: hole brighter inside; outside: hole darker inside.
            val edge = if (insideBlack) (vin - vout) else (vout - vin)
            sum += max(0f, edge)
            count++
        }
        if (count == 0) return 0f
        return ((sum / count) / 40f).coerceIn(0f, 1f)
    }

    private fun sample(image: ImageBuffer, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, image.width - 2)
        val y0 = y.toInt().coerceIn(0, image.height - 2)
        val fx = x - x0
        val fy = y - y0
        val a = image.get(x0, y0) * (1 - fx) + image.get(x0 + 1, y0) * fx
        val b = image.get(x0, y0 + 1) * (1 - fx) + image.get(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }

    private fun isOccupied(
        occupied: BooleanArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float
    ): Boolean {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(w - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(h - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2 && occupied[y * w + x]) return true
            }
        }
        return false
    }

    private fun markOccupied(
        occupied: BooleanArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(w - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(h - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2) occupied[y * w + x] = true
            }
        }
    }
}
