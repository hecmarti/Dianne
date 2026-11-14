package com.dianne.krugerscorer.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import com.dianne.krugerscorer.model.AnalysisResult
import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Template / canonical-frame scorer (v6):
 * 1. Calibrate black mark → warp so it becomes a fixed circle
 * 2. Flatten illumination inside the black disk
 * 3. Score hole likelihood (flat residual + chroma backstop + bright)
 * 4. Morph-close incomplete circles, DT-split clusters, seed leftovers
 * 5. Validate each circle, map back to working-image coords
 */
object TemplatePipeline {

    const val CANON = 640
    const val BLACK_R = 200f
    /** Expected pellet radius in canonical px (~0.15 × black radius). */
    val PELLET_R: Float = BLACK_R * 0.15f

    private val RING_DIAM_MM = floatArrayOf(59.5f, 53.5f, 47.5f, 41.5f, 35.5f, 29.5f, 23.5f, 17.5f, 11.5f, 5.5f)

    data class Output(
        val result: AnalysisResult,
        val workingBitmap: Bitmap
    )

    fun analyze(source: Bitmap, sensitivity: Float): Output {
        val (_, working) = ImageBuffer.fromBitmap(source, maxSide = 1100)
        val buffer = ImageBuffer.fromBitmap(working, maxSide = 1100).first
        val cal = TargetCalibrator.calibrate(buffer)
            ?: return Output(AnalysisResult(null, emptyList(), sensitivity), working)

        val warped = warpToCanonical(working, cal)
        val (like, zone, distMap) = likelihood(warped, sensitivity)
        val detsCanon = extract(like, zone, distMap, sensitivity)

        val inv = InvMap(BLACK_R / cal.blackRadius, cal.centerX, cal.centerY)
        val detections = detsCanon.mapIndexed { idx, d ->
            val (ox, oy) = inv.map(d.x, d.y)
            ShotDetection(
                id = idx,
                x = ox,
                y = oy,
                radius = d.r * inv.scale,
                score = d.score,
                confidence = d.conf
            )
        }
        // Recenter calibration onto working bitmap (already is)
        val calWorking = TargetCalibration(cal.centerX, cal.centerY, cal.blackRadius, working.width, working.height)
        return Output(AnalysisResult(calWorking, detections, sensitivity), working)
    }

    private data class InvMap(val scale: Float, val ox: Float, val oy: Float) {
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val dx = (x - CANON / 2f) / scale
            val dy = (y - CANON / 2f) / scale
            return ox + dx to oy + dy
        }
    }

    private fun warpToCanonical(src: Bitmap, cal: TargetCalibration): Bitmap {
        val scale = BLACK_R / cal.blackRadius
        val matrix = Matrix()
        matrix.postTranslate(-cal.centerX, -cal.centerY)
        matrix.postScale(scale, scale)
        matrix.postTranslate(CANON / 2f, CANON / 2f)
        val out = Bitmap.createBitmap(CANON, CANON, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(215, 218, 222))
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private data class LikePack(val like: FloatArray, val zone: BooleanArray, val dist: FloatArray)

    private fun likelihood(warped: Bitmap, sens: Float): LikePack {
        val s = sens.coerceIn(0f, 1f)
        val pixels = IntArray(CANON * CANON)
        warped.getPixels(pixels, 0, CANON, 0, 0, CANON, CANON)
        val gray = FloatArray(CANON * CANON)
        val like = FloatArray(CANON * CANON)
        val zone = BooleanArray(CANON * CANON)
        val dist = FloatArray(CANON * CANON)
        val cyan = FloatArray(CANON * CANON)
        val brown = FloatArray(CANON * CANON)
        val blueDom = FloatArray(CANON * CANON)

        for (y in 0 until CANON) {
            for (x in 0 until CANON) {
                val i = y * CANON + x
                val p = pixels[i]
                val r = Color.red(p).toFloat()
                val g = Color.green(p).toFloat()
                val b = Color.blue(p).toFloat()
                gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
                val d = hypot(x - CANON / 2f, y - CANON / 2f)
                dist[i] = d
                zone[i] = d <= BLACK_R * 0.88f

                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val delta = mx - mn
                var h = 0f
                if (delta > 1f) {
                    h = when (mx) {
                        r -> 60f * (((g - b) / delta) % 6f)
                        g -> 60f * ((b - r) / delta + 2f)
                        else -> 60f * ((r - g) / delta + 4f)
                    }
                    if (h < 0) h += 360f
                }
                val sat = if (mx > 1f) delta / mx else 0f
                val value = mx
                // OpenCV H is 0-179; we use 0-360 → scale thresholds ×2
                cyan[i] = if (h in 150f..290f && sat > 0.12f && value > 35f) 1f else 0f
                brown[i] = if ((h <= 64f || h >= 296f) && sat > 0.12f && value > 30f) 1f else 0f
                blueDom[i] = max(0f, b - max(r, g) - 5f)
            }
        }

        // Illumination flatten: gray / large blur
        val illum = FloatArray(CANON * CANON)
        gray.blurBox(CANON, CANON, (BLACK_R * 0.22f).roundToInt().coerceAtLeast(8), illum)
        // Approximate second pass for larger sigma
        val illum2 = FloatArray(CANON * CANON)
        illum.blurBox(CANON, CANON, (BLACK_R * 0.18f).roundToInt().coerceAtLeast(6), illum2)

        val ink = FloatArray(CANON * CANON)
        // Approximate median with two box blurs (good enough for ink estimate)
        gray.blurBox(CANON, CANON, 20, ink)
        val ink2 = FloatArray(CANON * CANON)
        ink.blurBox(CANON, CANON, 20, ink2)

        val flatRes = FloatArray(CANON * CANON)
        val bright = FloatArray(CANON * CANON)
        for (i in gray.indices) {
            val ill = max(illum2[i], 8f)
            val flat = gray[i] / ill
            flatRes[i] = max(0f, flat - (1.08f - s * 0.06f)) * 80f
            bright[i] = max(0f, gray[i] - (ink2[i] + 12f - s * 6f))
        }

        // DoG on flat residual
        val g1 = FloatArray(CANON * CANON)
        val g2 = FloatArray(CANON * CANON)
        flatRes.blurBox(CANON, CANON, max(2, (PELLET_R * 0.18f).roundToInt()), g1)
        flatRes.blurBox(CANON, CANON, max(3, (PELLET_R * 0.45f).roundToInt()), g2)

        for (i in like.indices) {
            if (!zone[i] || dist[i] >= BLACK_R * 0.87f) {
                like[i] = 0f
                continue
            }
            val dog = max(0f, g1[i] - g2[i])
            like[i] = (
                0.34f * flatRes[i]
                    + 0.22f * bright[i]
                    + 0.24f * (70f * cyan[i] + 40f * brown[i])
                    + 0.10f * blueDom[i]
                    + 0.10f * dog
                )
        }
        return LikePack(like, zone, dist)
    }

    private data class CanonShot(val x: Float, val y: Float, val r: Float, val conf: Float, val score: Int)

    private fun extract(
        like: FloatArray,
        zone: BooleanArray,
        distMap: FloatArray,
        sens: Float
    ): List<CanonShot> {
        val s = sens.coerceIn(0f, 1f)
        val er = PELLET_R
        val vals = ArrayList<Float>()
        for (i in like.indices) {
            if (zone[i] && like[i] > 2f) vals.add(like[i])
        }
        if (vals.size < 30) return emptyList()
        vals.sort()
        val keep = 0.025f + s * 0.035f
        val thr = vals[((vals.size - 1) * (1f - keep)).roundToInt().coerceIn(0, vals.lastIndex)]
        val floor = max(thr * (0.38f - s * 0.05f), 7.5f)

        val rawMask = BooleanArray(CANON * CANON)
        for (i in like.indices) {
            rawMask[i] = like[i] >= floor && zone[i] && distMap[i] <= BLACK_R * 0.86f
        }
        val closeR = max(2, (er * 0.28f).roundToInt())
        val openR = max(1, (er * 0.14f).roundToInt())
        var mask = Morphology.closeBoolFast(rawMask, CANON, CANON, closeR)
        mask = Morphology.closeBoolFast(mask, CANON, CANON, closeR)
        mask = Morphology.openBoolFast(mask, CANON, CANON, openR)
        for (i in mask.indices) {
            if (distMap[i] >= BLACK_R * 0.86f) mask[i] = false
        }

        val holeArea = (PI * er * er).toFloat()
        val comps = connected(mask, like)
        val raw = ArrayList<CanonShot>()

        for (comp in comps) {
            if (comp.area < holeArea * 0.28f) continue
            if (hypot(comp.cx - CANON / 2f, comp.cy - CANON / 2f) > BLACK_R * 0.86f) continue
            val peri = 2f * ((comp.maxX - comp.minX + 1) + (comp.maxY - comp.minY + 1)) * 0.85f
            val circ = (4f * PI.toFloat() * comp.area / (peri * peri + 1e-3f)).coerceIn(0f, 1.5f)
            var est = max(1, (comp.area / (holeArea * 1.0f)).roundToInt())
            val single = est == 1 || (circ > 0.55f && comp.area < holeArea * 1.8f)

            if (single) {
                val r = sqrt(comp.area / PI.toFloat()).coerceIn(er * 0.5f, er * 1.2f)
                val (ok, conf) = validate(like, comp.cx, comp.cy, r, floor)
                if (ok) raw.add(CanonShot(comp.cx, comp.cy, r, conf, scoreCanon(comp.cx, comp.cy, r)))
                continue
            }

            est = (comp.area / (holeArea * 0.90f)).roundToInt().coerceIn(2, 7)
            val dt = distanceTransform(comp.pixels)
            val minR = er * 0.38f
            val peaks = localMaxima(dt, minR, er * 1.25f, er)
            var taken = 0
            val occupied = ArrayList<Pair<Float, Float>>()
            for ((px, py, peak) in peaks.sortedByDescending { it.third }) {
                if (taken >= est) break
                if (occupied.any { (it.first - px) * (it.first - px) + (it.second - py) * (it.second - py) < (er * 0.68f) * (er * 0.68f) }) {
                    continue
                }
                val r = peak.coerceIn(minR, er * 1.25f)
                val (ok, conf) = validate(like, px, py, r, floor)
                if (ok) {
                    occupied.add(px to py)
                    raw.add(CanonShot(px, py, r, conf, scoreCanon(px, py, r)))
                    taken++
                }
            }
            if (taken == 0) {
                val r = sqrt(comp.area / PI.toFloat()).coerceIn(er * 0.5f, er * 1.2f)
                val (ok, conf) = validate(like, comp.cx, comp.cy, r, floor)
                if (ok) raw.add(CanonShot(comp.cx, comp.cy, r, conf, scoreCanon(comp.cx, comp.cy, r)))
            }
        }

        // Seed pass for leftovers
        val suppress = BooleanArray(CANON * CANON)
        for (d in raw) mark(suppress, d.x, d.y, er * 0.72f)
        val smooth = FloatArray(CANON * CANON)
        like.blurBox(CANON, CANON, max(2, (er * 0.14f).roundToInt()), smooth)
        for (i in smooth.indices) {
            if (suppress[i] || distMap[i] >= BLACK_R * 0.86f) smooth[i] = 0f
        }
        var guard = 0
        while (raw.size < 11 && guard < 16) {
            guard++
            var bestI = -1
            var bestV = 0f
            for (i in smooth.indices) {
                if (smooth[i] > bestV) {
                    bestV = smooth[i]
                    bestI = i
                }
            }
            if (bestI < 0 || bestV < floor * 0.95f) break
            var x = (bestI % CANON).toFloat()
            var y = (bestI / CANON).toFloat()
            // centroid refine
            val rLoc = (er * 0.60f).roundToInt()
            val x0 = max(0, x.roundToInt() - rLoc)
            val x1 = min(CANON - 1, x.roundToInt() + rLoc)
            val y0 = max(0, y.roundToInt() - rLoc)
            val y1 = min(CANON - 1, y.roundToInt() + rLoc)
            var wSum = 0.0
            var sx = 0.0
            var sy = 0.0
            for (yy in y0..y1) for (xx in x0..x1) {
                val v = like[yy * CANON + xx]
                if (v >= bestV * 0.40f) {
                    wSum += v
                    sx += xx * v
                    sy += yy * v
                }
            }
            if (wSum > 1) {
                x = (sx / wSum).toFloat()
                y = (sy / wSum).toFloat()
            }
            val r = er * 0.95f
            val (ok, conf) = validate(like, x, y, r, floor)
            if (ok && conf >= 0.15f) {
                raw.add(CanonShot(x, y, r, conf, scoreCanon(x, y, r)))
                mark(suppress, x, y, er * 0.75f)
                markZero(smooth, x, y, er * 0.75f)
            } else {
                markZero(smooth, x, y, er * 0.45f)
            }
        }

        return raw
            .filter { it.conf >= 0.14f && hypot(it.x - CANON / 2f, it.y - CANON / 2f) <= BLACK_R * 0.86f }
            .sortedByDescending { it.conf }
            .fold(ArrayList<CanonShot>()) { acc, d ->
                if (acc.none { hypot(it.x - d.x, it.y - d.y) < er * 0.75f }) acc.add(d)
                acc
            }
            .take(11)
    }

    private fun validate(like: FloatArray, x: Float, y: Float, r: Float, floor: Float): Pair<Boolean, Float> {
        val x0 = max(0, (x - r).toInt())
        val x1 = min(CANON - 1, (x + r).toInt())
        val y0 = max(0, (y - r).toInt())
        val y1 = min(CANON - 1, (y + r).toInt())
        var sum = 0.0
        var n = 0
        var covN = 0
        val r2 = (r * 0.60f) * (r * 0.60f)
        for (yy in y0..y1) for (xx in x0..x1) {
            val dx = xx - x
            val dy = yy - y
            if (dx * dx + dy * dy > r2) continue
            val v = like[yy * CANON + xx]
            sum += v
            n++
            if (v >= floor * 0.35f) covN++
        }
        if (n < 8) return false to 0f
        val coreM = (sum / n).toFloat()
        val cov = covN.toFloat() / n
        val ok = coreM >= floor * 0.45f && cov >= 0.22f
        val conf = min(coreM / 40f, 1f) * (0.4f + 0.6f * cov)
        return ok to conf
    }

    private fun scoreCanon(x: Float, y: Float, r: Float): Int {
        val dist = max(0f, hypot(x - CANON / 2f, y - CANON / 2f) - r)
        val mm = dist * (23.5f / (2f * BLACK_R))
        for (score in 10 downTo 1) {
            if (mm <= RING_DIAM_MM[score - 1] / 2f) return score
        }
        return 0
    }

    private data class Comp(
        val area: Float, val cx: Float, val cy: Float,
        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
        val pixels: BooleanArray
    )

    private fun connected(mask: BooleanArray, like: FloatArray): List<Comp> {
        val labels = IntArray(CANON * CANON)
        val out = ArrayList<Comp>()
        val qx = IntArray(CANON * CANON)
        val qy = IntArray(CANON * CANON)
        var next = 1
        for (y in 0 until CANON) {
            for (x in 0 until CANON) {
                val idx = y * CANON + x
                if (!mask[idx] || labels[idx] != 0) continue
                var head = 0
                var tail = 0
                qx[tail] = x
                qy[tail] = y
                tail++
                labels[idx] = next
                var area = 0
                var sx = 0.0
                var sy = 0.0
                var x0 = x
                var x1 = x
                var y0 = y
                var y1 = y
                val pix = BooleanArray(CANON * CANON)
                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head]
                    head++
                    area++
                    sx += cx
                    sy += cy
                    val p = cy * CANON + cx
                    pix[p] = true
                    x0 = min(x0, cx)
                    x1 = max(x1, cx)
                    y0 = min(y0, cy)
                    y1 = max(y1, cy)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx !in 0 until CANON || ny !in 0 until CANON) continue
                        val nIdx = ny * CANON + nx
                        if (mask[nIdx] && labels[nIdx] == 0) {
                            labels[nIdx] = next
                            qx[tail] = nx
                            qy[tail] = ny
                            tail++
                        }
                    }
                }
                next++
                out.add(
                    Comp(
                        area.toFloat(), (sx / area).toFloat(), (sy / area).toFloat(),
                        x0, x1, y0, y1, pix
                    )
                )
            }
        }
        return out
    }

    private fun distanceTransform(mask: BooleanArray): FloatArray {
        val w = CANON
        val h = CANON
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
                val di = f(i)
                val du = f(u)
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

    private fun localMaxima(
        dist: FloatArray,
        minR: Float,
        maxR: Float,
        er: Float
    ): List<Triple<Float, Float, Float>> {
        val peaks = ArrayList<Triple<Float, Float, Float>>()
        val n = max(2, (er * 0.48f).roundToInt())
        val minPeak = minR * 0.60f
        for (y in n until CANON - n) {
            for (x in n until CANON - n) {
                val v = dist[y * CANON + x]
                if (v < minPeak || v > maxR) continue
                var isMax = true
                outer@ for (dy in -n..n) for (dx in -n..n) {
                    if (dx == 0 && dy == 0) continue
                    if (dist[(y + dy) * CANON + (x + dx)] > v) {
                        isMax = false
                        break@outer
                    }
                }
                if (isMax) peaks.add(Triple(x.toFloat(), y.toFloat(), v))
            }
        }
        return peaks
    }

    private fun mark(occupied: BooleanArray, cx: Float, cy: Float, radius: Float) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(CANON - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(CANON - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r2) occupied[y * CANON + x] = true
        }
    }

    private fun markZero(arr: FloatArray, cx: Float, cy: Float, radius: Float) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r)
        val x1 = min(CANON - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r)
        val y1 = min(CANON - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r2) arr[y * CANON + x] = 0f
        }
    }
}
