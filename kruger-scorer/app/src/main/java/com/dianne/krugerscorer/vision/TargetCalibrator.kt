package com.dianne.krugerscorer.vision

import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Locates the black aiming mark on a Krüger airgun target.
 *
 * Strategy:
 * 1. Threshold dark pixels.
 * 2. Find the largest roughly-circular dark blob near the image center.
 * 3. Refine center/radius with radial sampling of the gray profile.
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

        val labels = IntArray(w * h)
        val components = connectedComponents(mask, w, h, labels)
        if (components.isEmpty()) return null

        val cxImg = w / 2f
        val cyImg = h / 2f
        val best = components
            .asSequence()
            .filter { it.area in (w * h / 80)..(w * h / 2) }
            .map { comp ->
                val dx = comp.cx - cxImg
                val dy = comp.cy - cyImg
                val dist = hypot(dx, dy)
                val circularity = circularity(comp)
                Triple(comp, circularity, dist)
            }
            .filter { it.second > 0.55f }
            .minByOrNull { it.third }
            ?.first
            ?: components.maxByOrNull { it.area }
            ?: return null

        val refined = refineCircle(image, best.cx, best.cy, best.radius)
        return TargetCalibration(
            centerX = refined.first,
            centerY = refined.second,
            blackRadius = refined.third,
            imageWidth = w,
            imageHeight = h
        )
    }

    private fun estimateDarkThreshold(image: ImageBuffer): Float {
        // Otsu-ish: mean of the darker half of a subsampled histogram.
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
        // Keep threshold close to Otsu so cream paper stays out of the mask.
        // A large positive bias merges logos and outer rings into the aiming mark.
        return (threshold + 6).coerceIn(35, 110).toFloat()
    }

    private data class Component(
        val label: Int,
        val area: Int,
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    private fun connectedComponents(
        mask: BooleanArray,
        w: Int,
        h: Int,
        labels: IntArray
    ): List<Component> {
        labels.fill(0)
        var next = 1
        val areas = mutableListOf<Int>()
        val sumX = mutableListOf<Long>()
        val sumY = mutableListOf<Long>()
        val minX = mutableListOf<Int>()
        val maxX = mutableListOf<Int>()
        val minY = mutableListOf<Int>()
        val maxY = mutableListOf<Int>()

        val qx = IntArray(w * h)
        val qy = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!mask[idx] || labels[idx] != 0) continue
                val label = next++
                var head = 0
                var tail = 0
                qx[tail] = x
                qy[tail] = y
                tail++
                labels[idx] = label
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
                                labels[nIdx] = label
                                qx[tail] = nx
                                qy[tail] = ny
                                tail++
                            }
                        }
                    }
                }
                areas.add(area)
                sumX.add(sx)
                sumY.add(sy)
                minX.add(x0)
                maxX.add(x1)
                minY.add(y0)
                maxY.add(y1)
            }
        }

        return areas.indices.map { i ->
            val area = areas[i]
            val cx = sumX[i].toFloat() / area
            val cy = sumY[i].toFloat() / area
            // Area-derived radius is more robust than the bounding box when
            // thresholding spills into logos / outer rings.
            val radius = sqrt(area / Math.PI.toFloat())
            Component(
                label = i + 1,
                area = area,
                cx = cx,
                cy = cy,
                radius = radius,
                minX = minX[i],
                maxX = maxX[i],
                minY = minY[i],
                maxY = maxY[i]
            )
        }
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

    private fun refineCircle(
        image: ImageBuffer,
        seedX: Float,
        seedY: Float,
        seedR: Float
    ): Triple<Float, Float, Float> {
        var cx = seedX
        var cy = seedY
        var r = seedR
        repeat(3) {
            val edges = sampleEdgePoints(image, cx, cy, r)
            if (edges.size < 12) return Triple(cx, cy, r)
            val fitted = fitCircle(edges) ?: return Triple(cx, cy, r)
            cx = fitted.first
            cy = fitted.second
            r = fitted.third
        }
        return Triple(cx, cy, r)
    }

    private fun sampleEdgePoints(
        image: ImageBuffer,
        cx: Float,
        cy: Float,
        seedR: Float
    ): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(72)
        val searchMin = (seedR * 0.55f).coerceAtLeast(8f)
        val searchMax = (seedR * 1.45f).coerceAtMost(
            min(image.width, image.height) / 2f - 2f
        )
        val steps = 72
        for (i in 0 until steps) {
            val angle = (Math.PI * 2.0 * i / steps).toFloat()
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            var bestT = seedR
            var bestGrad = 0f
            var t = searchMin
            var prev = sampleBilinear(image, cx + cos * t, cy + sin * t)
            t += 1.5f
            while (t <= searchMax) {
                val v = sampleBilinear(image, cx + cos * t, cy + sin * t)
                val grad = v - prev
                // Dark→light transition at the black mark boundary.
                if (grad > bestGrad) {
                    bestGrad = grad
                    bestT = t - 0.75f
                }
                prev = v
                t += 1.5f
            }
            if (bestGrad > 8f) {
                points.add(cx + cos * bestT to cy + sin * bestT)
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
        // Algebraic circle fit (Kåsa).
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
