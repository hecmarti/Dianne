package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Locates the black aiming mark on a Krüger airgun target.
 *
 * Tries several dark thresholds (shadow-robust), keeps candidates whose
 * radius is in a plausible fraction of the image, scores by rim contrast,
 * then refines with radial dark→light edges.
 */
object TargetCalibrator {

    fun calibrate(image: ImageBuffer): TargetCalibration? {
        val w = image.width
        val h = image.height
        val minDim = min(w, h).toFloat()
        val minR = minDim * 0.14f
        val maxR = minDim * 0.40f
        val cxImg = w / 2f
        val cyImg = h / 2f

        data class Cand(val score: Float, val cx: Float, val cy: Float, val r: Float)

        val cands = ArrayList<Cand>()
        for (pct in intArrayOf(18, 25, 32)) {
            val thr = percentileThreshold(image, pct / 100f) + 10f
            val mask = BooleanArray(w * h)
            for (i in image.gray.indices) {
                mask[i] = image.gray[i] < thr.coerceIn(15f, 100f)
            }
            // light open/close via box morph
            val closed = Morphology.closeBoolFast(mask, w, h, 3)
            val opened = Morphology.openBoolFast(closed, w, h, 2)
            for (comp in connectedComponents(opened, w, h)) {
                if (comp.area < w * h * 0.045f) continue
                val circ = circularity(comp)
                if (circ < 0.58f) continue
                // Use bounding-box equivalent radius (more stable than area radius)
                val rBox = 0.25f * ((comp.maxX - comp.minX + 1) + (comp.maxY - comp.minY + 1))
                if (rBox !in minR..maxR) continue
                val q = circleQuality(image, comp.cx, comp.cy, rBox)
                if (q < 16f) continue
                val centerPen = 0.02f * hypot(comp.cx - cxImg, comp.cy - cyImg)
                cands.add(Cand(q + circ * 20f - centerPen, comp.cx, comp.cy, rBox))
            }
        }
        if (cands.isEmpty()) return null
        cands.sortByDescending { it.score }
        var cx = cands[0].cx
        var cy = cands[0].cy
        var r = cands[0].r

        repeat(5) {
            val edges = sampleEdgePoints(image, cx, cy, r)
            if (edges.size < 28) return@repeat
            val rr = edges.map { hypot(it.first - cx, it.second - cy) }
            val med = rr.sorted()[rr.size / 2]
            val mad = rr.map { abs(it - med) }.sorted()[rr.size / 2] + 1e-3f
            val filtered = edges.filterIndexed { i, _ -> abs(rr[i] - med) < 2.2f * mad }
            if (filtered.size < 24) return@repeat
            val fitted = fitCircle(filtered)
            if (fitted != null) {
                cx = fitted.first
                cy = fitted.second
                r = fitted.third
            } else {
                cx = filtered.map { it.first }.average().toFloat()
                cy = filtered.map { it.second }.average().toFloat()
                r = filtered.map { hypot(it.first - cx, it.second - cy) }.average().toFloat()
            }
        }

        if (r !in minR..maxR) return null
        return TargetCalibration(cx, cy, r, w, h)
    }

    private fun percentileThreshold(image: ImageBuffer, pct: Float): Float {
        val hist = IntArray(256)
        var i = 0
        while (i < image.gray.size) {
            hist[image.gray[i].roundToInt().coerceIn(0, 255)]++
            i += 3
        }
        val total = hist.sum().coerceAtLeast(1)
        val target = (total * pct).toInt()
        var cum = 0
        for (t in 0..255) {
            cum += hist[t]
            if (cum >= target) return t.toFloat()
        }
        return 40f
    }

    private fun circleQuality(image: ImageBuffer, cx: Float, cy: Float, r: Float): Float {
        var inSum = 0.0
        var inN = 0
        var outSum = 0.0
        var outN = 0
        var darkN = 0
        val step = max(1, (r / 40f).roundToInt())
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val d = hypot(x - cx, y - cy)
                val v = image.get(x, y)
                if (d <= r * 0.70f) {
                    inSum += v
                    inN++
                    if (v < 70f) darkN++
                } else if (d in (r * 1.05f)..(r * 1.22f)) {
                    outSum += v
                    outN++
                }
                x += step
            }
            y += step
        }
        if (inN < 40 || outN < 20) return -1e6f
        val contrast = (outSum / outN - inSum / inN).toFloat()
        if (contrast < 16f) return -1e6f
        if (inSum / inN > 95.0) return -1e6f
        val darkFrac = darkN.toFloat() / inN
        return contrast + 30f * darkFrac
    }

    private data class Component(
        val area: Int,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private fun connectedComponents(mask: BooleanArray, w: Int, h: Int): List<Component> {
        val labels = IntArray(w * h)
        var next = 1
        val out = ArrayList<Component>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx] || labels[idx] != 0) continue
                var head = 0
                var tail = 0
                qx[tail] = x
                qy[tail] = y
                tail++
                labels[idx] = next
                var area = 0
                var sx = 0L
                var sy = 0L
                var x0 = x
                var x1 = x
                var y0 = y
                var y1 = y
                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    area++
                    sx += cx
                    sy += cy
                    x0 = min(x0, cx)
                    x1 = max(x1, cx)
                    y0 = min(y0, cy)
                    y1 = max(y1, cy)
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val nIdx = ny * w + nx
                            if (mask[nIdx] && labels[nIdx] == 0) {
                                labels[nIdx] = next
                                qx[tail] = nx
                                qy[tail] = ny
                                tail++
                            }
                        }
                    }
                }
                next++
                out.add(
                    Component(
                        area = area,
                        cx = sx.toFloat() / area,
                        cy = sy.toFloat() / area,
                        radius = sqrt(area / Math.PI.toFloat()),
                        minX = x0,
                        maxX = x1,
                        minY = y0,
                        maxY = y1
                    )
                )
            }
        }
        return out
    }

    private fun circularity(comp: Component): Float {
        val bw = (comp.maxX - comp.minX + 1).toFloat()
        val bh = (comp.maxY - comp.minY + 1).toFloat()
        val aspect = min(bw, bh) / max(bw, bh)
        val expectedArea = Math.PI.toFloat() * comp.radius * comp.radius
        val fill = (comp.area / expectedArea).coerceIn(0f, 1.5f)
        val fillScore = 1f - abs(1f - fill).coerceAtMost(1f)
        return aspect * 0.55f + fillScore * 0.45f
    }

    private fun sampleEdgePoints(
        image: ImageBuffer,
        cx: Float,
        cy: Float,
        seedR: Float
    ): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(96)
        val searchMin = (seedR * 0.70f).coerceAtLeast(6f)
        val searchMax = (seedR * 1.25f).coerceAtMost(
            min(min(cx, image.width - cx), min(cy, image.height - cy)) - 2f
        )
        if (searchMax <= searchMin) return points
        for (i in 0 until 96) {
            val angle = (Math.PI * 2.0 * i / 96).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            var found: Float? = null
            var f = 0.72f
            while (f <= 1.22f) {
                val rr = seedR * f
                val xi = cx + c * rr
                val yi = cy + s * rr
                val xj = cx + c * rr * 0.90f
                val yj = cy + s * rr * 0.90f
                if (xi < 1 || yi < 1 || xi >= image.width - 1 || yi >= image.height - 1) {
                    f += 0.02f
                    continue
                }
                if (xj < 1 || yj < 1 || xj >= image.width - 1 || yj >= image.height - 1) {
                    f += 0.02f
                    continue
                }
                if (sampleBilinear(image, xi, yi) - sampleBilinear(image, xj, yj) > 12f) {
                    found = rr
                    break
                }
                f += 0.02f
            }
            if (found != null && found in searchMin..searchMax) {
                points.add(cx + c * found to cy + s * found)
            }
        }
        return points
    }

    private fun sampleBilinear(image: ImageBuffer, x: Float, y: Float): Float {
        if (x < 0f || y < 0f || x >= image.width - 1f || y >= image.height - 1f) {
            return 255f
        }
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = image.get(x0, y0)
        val i10 = image.get(x0 + 1, y0)
        val i01 = image.get(x0, y0 + 1)
        val i11 = image.get(x0 + 1, y0 + 1)
        val a = i00 * (1 - fx) + i10 * fx
        val b = i01 * (1 - fx) + i11 * fx
        return a * (1 - fy) + b * fy
    }

    private fun fitCircle(points: List<Pair<Float, Float>>): Triple<Float, Float, Float>? {
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var sumXY = 0.0
        var sumX3 = 0.0
        var sumY3 = 0.0
        var sumX2Y = 0.0
        var sumXY2 = 0.0
        val n = points.size.toDouble()
        for ((x, y) in points) {
            val xd = x.toDouble()
            val yd = y.toDouble()
            val x2 = xd * xd
            val y2 = yd * yd
            sumX += xd
            sumY += yd
            sumX2 += x2
            sumY2 += y2
            sumXY += xd * yd
            sumX3 += x2 * xd
            sumY3 += y2 * yd
            sumX2Y += x2 * yd
            sumXY2 += xd * y2
        }
        val c = n * sumX2 - sumX * sumX
        val d = n * sumXY - sumX * sumY
        val e = n * sumY2 - sumY * sumY
        val g = 0.5 * (n * (sumX3 + sumXY2) - sumX * (sumX2 + sumY2))
        val h = 0.5 * (n * (sumY3 + sumX2Y) - sumY * (sumX2 + sumY2))
        val denom = c * e - d * d
        if (abs(denom) < 1e-6) return null
        val cx = (g * e - d * h) / denom
        val cy = (c * h - d * g) / denom
        val r2 = ((sumX2 + sumY2) - 2 * cx * sumX - 2 * cy * sumY) / n + cx * cx + cy * cy
        if (r2 <= 0) return null
        return Triple(cx.toFloat(), cy.toFloat(), sqrt(r2).toFloat())
    }
}
