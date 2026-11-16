package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Detects pellet holes on photographed Krüger targets.
 *
 * Tuned on real phone photos:
 * - holes are bright (torn paper / backstop) inside the black aiming mark
 * - overlapping shots are split with a distance-transform peak search
 * - expected pellet radius ≈ 7% of black radius on close-ups
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
        val cx = calibration.centerX
        val cy = calibration.centerY
        val R = calibration.blackRadius

        val er = (R * (0.068f + 0.012f * sens)).coerceIn(8f, 34f)
        val likelihood = FloatArray(w * h)
        val blackLevel = estimateBlackLevel(image, cx, cy, R)
        val absThr = blackLevel + (28f - sens * 14f)

        val topHatR = max(2, min(14, (er * 0.9f).roundToInt()))
        val topHat = FloatArray(w * h)
        Morphology.whiteTopHat(image.gray, w, h, topHatR, topHat)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot(x - cx, y - cy)
                if (d > R * 0.88f) continue
                val i = y * w + x
                val bright = max(0f, image.gray[i] - absThr)
                likelihood[i] = 0.55f * bright + 0.45f * topHat[i]
            }
        }

        val vals = ArrayList<Float>(likelihood.size / 8)
        var i = 0
        while (i < likelihood.size) {
            if (likelihood[i] > 4f) vals.add(likelihood[i])
            i += 2
        }
        if (vals.size < 20) return emptyList()
        vals.sort()
        val keep = 0.018f + sens * 0.032f
        val thrIdx = ((vals.size - 1) * (1f - keep)).roundToInt().coerceIn(0, vals.size - 1)
        val thr = vals[thrIdx]
        val floor = max(thr * (0.42f - sens * 0.1f), 7f + sens * 2f)

        val raw = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot(x - cx, y - cy)
                val idx = y * w + x
                raw[idx] = d <= R * 0.88f && likelihood[idx] >= floor
            }
        }

        val closeR = max(1, (er * 0.55f).roundToInt())
        val openR = max(1, (er * 0.14f).roundToInt())
        val mask = Morphology.openBool(
            Morphology.closeBool(raw, w, h, closeR),
            w, h, openR
        )

        val holeArea = (PI * er * er).toFloat()
        val components = labeledComponents(mask, likelihood, w, h)
        val detections = ArrayList<ShotDetection>()
        val occupied = BooleanArray(w * h)
        val minConf = 0.30f - sens * 0.10f

        for (comp in components) {
            if (comp.area < holeArea * 0.22f) continue
            val distCenter = hypot(comp.cx - cx, comp.cy - cy)
            if (distCenter > R * 0.88f) continue

            val peri = approxPerimeter(comp)
            val circularity = (4f * PI.toFloat() * comp.area / (peri * peri + 1e-3f)).coerceIn(0f, 1.5f)
            val conf = (comp.meanLike / (floor + 1e-3f)).coerceIn(0f, 2.2f) / 2.2f
            if (conf < minConf) continue

            var est = max(1, (comp.area / (holeArea * 0.92f)).roundToInt())
            val single = est == 1 || (circularity > 0.52f && comp.area < holeArea * 1.85f)

            if (single) {
                val radius = sqrt(comp.area / PI.toFloat()).coerceIn(er * 0.4f, er * 1.35f)
                if (isOccupied(occupied, w, h, comp.cx, comp.cy, er * 0.9f)) continue
                markOccupied(occupied, w, h, comp.cx, comp.cy, radius * 0.85f)
                detections.add(
                    ShotDetection(
                        id = detections.size,
                        x = comp.cx,
                        y = comp.cy,
                        radius = radius,
                        score = ScoreMapper.score(calibration, comp.cx, comp.cy, radius),
                        confidence = conf * (0.65f + 0.35f * min(circularity, 1f))
                    )
                )
            } else {
                est = max(est, max(1, (comp.area / (holeArea * 0.78f)).roundToInt()))
                val dist = distanceTransform(comp.mask, w, h)
                val minR = er * (0.36f - sens * 0.05f)
                val peaks = findPeaks(dist, w, h, minR, er * 1.45f, er)
                var taken = 0
                for ((px, py, peak) in peaks.sortedByDescending { it.third }) {
                    if (taken >= est + 1) break
                    if (!comp.mask[py.roundToInt().coerceIn(0, h - 1) * w + px.roundToInt().coerceIn(0, w - 1)]) {
                        continue
                    }
                    val radius = peak.coerceIn(minR, er * 1.45f)
                    if (isOccupied(occupied, w, h, px, py, er * 0.85f)) continue
                    markOccupied(occupied, w, h, px, py, radius * 0.85f)
                    detections.add(
                        ShotDetection(
                            id = detections.size,
                            x = px,
                            y = py,
                            radius = radius,
                            score = ScoreMapper.score(calibration, px, py, radius),
                            confidence = conf * 0.75f
                        )
                    )
                    taken++
                }
            }
        }

        return detections
            .sortedByDescending { it.confidence }
            .take(12)
            .mapIndexed { idx, d -> d.copy(id = idx) }
    }

    private fun estimateBlackLevel(
        image: ImageBuffer,
        cx: Float,
        cy: Float,
        R: Float
    ): Float {
        val samples = ArrayList<Float>()
        val step = max(1, (R / 50f).roundToInt())
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val d = hypot(x - cx, y - cy)
                if (d in (R * 0.28f)..(R * 0.72f)) {
                    samples.add(image.get(x, y))
                }
                x += step
            }
            y += step
        }
        if (samples.isEmpty()) return 28f
        samples.sort()
        val cut = samples[(samples.size * 0.35f).toInt().coerceIn(0, samples.size - 1)]
        val dark = samples.filter { it <= cut }
        return if (dark.isEmpty()) cut else dark[dark.size / 2]
    }

    private data class Blob(
        val area: Float,
        val cx: Float,
        val cy: Float,
        val meanLike: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val mask: BooleanArray
    )

    private fun labeledComponents(
        mask: BooleanArray,
        likelihood: FloatArray,
        w: Int,
        h: Int
    ): List<Blob> {
        val labels = IntArray(w * h)
        val out = ArrayList<Blob>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        var next = 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx] || labels[idx] != 0) continue
                var head = 0
                var tail = 0
                qx[tail] = x; qy[tail] = y; tail++
                labels[idx] = next
                var area = 0
                var sx = 0.0
                var sy = 0.0
                var likeSum = 0.0
                var x0 = x; var x1 = x; var y0 = y; var y1 = y
                val pixels = ArrayList<Int>()
                while (head < tail) {
                    val cx = qx[head]; val cy = qy[head]; head++
                    area++
                    sx += cx; sy += cy
                    val p = cy * w + cx
                    likeSum += likelihood[p]
                    pixels.add(p)
                    x0 = min(x0, cx); x1 = max(x1, cx)
                    y0 = min(y0, cy); y1 = max(y1, cy)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val nIdx = ny * w + nx
                        if (mask[nIdx] && labels[nIdx] == 0) {
                            labels[nIdx] = next
                            qx[tail] = nx; qy[tail] = ny; tail++
                        }
                    }
                }
                next++
                val blobMask = BooleanArray(w * h)
                for (p in pixels) blobMask[p] = true
                out.add(
                    Blob(
                        area = area.toFloat(),
                        cx = (sx / area).toFloat(),
                        cy = (sy / area).toFloat(),
                        meanLike = (likeSum / area).toFloat(),
                        minX = x0, maxX = x1, minY = y0, maxY = y1,
                        mask = blobMask
                    )
                )
            }
        }
        return out
    }

    private fun approxPerimeter(comp: Blob): Float {
        val bw = (comp.maxX - comp.minX + 1).toFloat()
        val bh = (comp.maxY - comp.minY + 1).toFloat()
        // Rough perimeter estimate from bbox + fill
        return 2f * (bw + bh) * 0.85f
    }

    // --- distance transform / peaks (shared with prior impl) ---

    private fun distanceTransform(mask: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e8f
        val dist = FloatArray(w * h) { if (mask[it]) inf else 0f }
        for (y in 0 until h) {
            val row = y * w
            for (x in 1 until w) dist[row + x] = min(dist[row + x], dist[row + x - 1] + 1f)
            for (x in w - 2 downTo 0) dist[row + x] = min(dist[row + x], dist[row + x + 1] + 1f)
        }
        val out = FloatArray(w * h)
        val envelope = IntArray(h + 1)
        val parabola = FloatArray(h + 1)
        for (x in 0 until w) {
            var k = 0
            envelope[0] = 0
            parabola[0] = Float.NEGATIVE_INFINITY
            parabola[1] = Float.POSITIVE_INFINITY
            fun f(y: Int) = dist[y * w + x]
            fun sep(i: Int, u: Int): Float {
                val di = f(i); val du = f(u)
                return ((u * u - i * i + du * du - di * di) / (2f * (u - i)))
            }
            for (q in 1 until h) {
                var s = sep(envelope[k], q)
                while (k > 0 && s <= parabola[k]) {
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
                while (k < h && parabola[k + 1] < q) k++
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
        val minPeak = minR * 0.7f
        val n = max(1, (expectedR * 0.45f).roundToInt())
        for (y in n until h - n) {
            for (x in n until w - n) {
                val v = dist[y * w + x]
                if (v < minPeak || v > maxR * 1.35f) continue
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
                if (isMax) peaks.add(Triple(x.toFloat(), y.toFloat(), v))
            }
        }
        return peaks
    }

    private fun isOccupied(
        occupied: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float
    ): Boolean {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(w - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(h - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2 && occupied[y * w + x]) return true
        }
        return false
    }

    private fun markOccupied(
        occupied: BooleanArray, w: Int, h: Int, cx: Float, cy: Float, radius: Float
    ) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(w - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(h - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) occupied[y * w + x] = true
        }
    }
}
