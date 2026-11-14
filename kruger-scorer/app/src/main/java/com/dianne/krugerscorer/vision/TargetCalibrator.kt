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
 * Candidates come from dark connected components; each is scored by
 * interior/exterior contrast, then refined with radial dark→light edges.
 */
object TargetCalibrator {

    fun calibrate(image: ImageBuffer): TargetCalibration? {
        val w = image.width
        val h = image.height
        val threshold = estimateDarkThreshold(image)
        val mask = BooleanArray(w * h)
        for (i in image.gray.indices) {
            mask[i] = image.gray[i] < threshold
        }

        val components = connectedComponents(mask, w, h)
        if (components.isEmpty()) return null

        val cxImg = w / 2f
        val cyImg = h / 2f
        data class Cand(val score: Float, val cx: Float, val cy: Float, val r: Float)

        val cands = ArrayList<Cand>()
        for (comp in components) {
            if (comp.area !in (w * h / 16)..(w * h * 7 / 10)) continue
            val circularity = circularity(comp)
            if (circularity < 0.55f) continue
            val q = circleQuality(image, comp.cx, comp.cy, comp.radius) + circularity * 15f
            val centerPen = 0.015f * hypot(comp.cx - cxImg, comp.cy - cyImg)
            cands.add(Cand(q - centerPen, comp.cx, comp.cy, comp.radius))
        }
        if (cands.isEmpty()) {
            val largest = components.maxByOrNull { it.area } ?: return null
            cands.add(Cand(0f, largest.cx, largest.cy, largest.radius))
        }
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

        if (r < min(w, h) * 0.12f) return null
        return TargetCalibration(cx, cy, r, w, h)
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
                if (d <= r * 0.82f) {
                    inSum += v
                    inN++
                    if (v < 70f) darkN++
                } else if (d in (r * 1.04f)..(r * 1.22f)) {
                    outSum += v
                    outN++
                }
                x += step
            }
            y += step
        }
        if (inN < 40 || outN < 20) return -1e6f
        val contrast = (outSum / outN - inSum / inN).toFloat()
        val darkFrac = darkN.toFloat() / inN
        return contrast + 40f * darkFrac
    }

    private fun estimateDarkThreshold(image: ImageBuffer): Float {
        val hist = IntArray(256)
        var i = 0
        while (i < image.gray.size) {
            hist[image.gray[i].roundToInt().coerceIn(0, 255)]++
            i += 4
        }
        val total = hist.sum().coerceAtLeast(1)
        var sum = 0.0
        for (g in hist.indices) sum += g * hist[g].toDouble()
        var sumB = 0.0
        var wB = 0
        var maxVar = -1.0
        var threshold = 80
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxVar) {
                maxVar = between
                threshold = t
            }
        }
        // Also consider low percentile — close-ups are mostly black ink.
        var cum = 0
        var p28 = 40
        val target = (total * 0.28).toInt()
        for (t in 0..255) {
            cum += hist[t]
            if (cum >= target) {
                p28 = t
                break
            }
        }
        val thr = min(threshold + 4, p28 + 8)
        return thr.coerceIn(28, 95).toFloat()
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
        val points = ArrayList<Pair<Float, Float>>(180)
        val searchMin = (seedR * 0.55f).coerceAtLeast(6f)
        val searchMax = (seedR * 1.45f).coerceAtMost(
            min(
                min(cx, image.width - cx),
                min(cy, image.height - cy)
            ) - 2f
        )
        if (searchMax <= searchMin) return points
        for (i in 0 until 180) {
            val angle = (Math.PI * 2.0 * i / 180).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            var bestT = seedR
            var bestGrad = 0f
            var t = searchMin
            var prev = sampleBilinear(image, cx + c * t, cy + s * t)
            t += 0.7f
            while (t <= searchMax) {
                val v = sampleBilinear(image, cx + c * t, cy + s * t)
                val grad = v - prev
                if (grad > bestGrad) {
                    bestGrad = grad
                    bestT = t - 0.35f
                }
                prev = v
                t += 0.7f
            }
            if (bestGrad > 3f) {
                points.add(cx + c * bestT to cy + s * bestT)
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
