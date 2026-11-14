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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Template-superposition scoring:
 * 1. Locate black aiming mark
 * 2. Warp photo so the mark becomes a fixed circle in a canonical frame
 * 3. Compare against an ideal black disk → residuals = holes
 * 4. Morph-close incomplete circles, split merged blobs, score in canonical space
 * 5. Map detections back to the working bitmap for the UI overlay
 */
object TemplatePipeline {

    const val CANON = 512
    const val BLACK_R = 150f

    data class Output(
        val result: AnalysisResult,
        val workingBitmap: Bitmap,
        val canonicalPreview: Bitmap? = null
    )

    fun analyze(source: Bitmap, sensitivity: Float): Output {
        val (_, working) = ImageBuffer.fromBitmap(source, maxSide = 900)
        val buffer = ImageBuffer.fromBitmap(working, maxSide = 900).first
        val cal = TargetCalibrator.calibrate(buffer)
            ?: return Output(
                AnalysisResult(null, emptyList(), sensitivity),
                working
            )

        val warped = warpToCanonical(working, cal)
        val template = makeIdealTemplate()
        val (mask, like, er) = holeMask(warped, template, sensitivity)
        val detsCanon = extractShots(mask, like, er, sensitivity)

        // Map canonical detections → working-image coordinates
        val inv = inverseSimilarity(cal, working.width, working.height)
        val detections = detsCanon.mapIndexed { idx, d ->
            val (ox, oy) = inv.map(d.x, d.y)
            val or = d.r * inv.scale
            ShotDetection(
                id = idx,
                x = ox,
                y = oy,
                radius = or,
                score = d.score,
                confidence = d.conf
            )
        }

        return Output(
            result = AnalysisResult(cal, detections, sensitivity),
            workingBitmap = working,
            canonicalPreview = warped
        )
    }

    // --- geometry ---

    private data class InvMap(val scale: Float, val cx: Float, val cy: Float, val ox: Float, val oy: Float) {
        /** Canonical (x,y) → original bitmap */
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val dx = (x - CANON / 2f) / scale
            val dy = (y - CANON / 2f) / scale
            return ox + dx to oy + dy
        }
    }

    private fun inverseSimilarity(cal: TargetCalibration, w: Int, h: Int): InvMap {
        val scale = BLACK_R / cal.blackRadius
        return InvMap(scale, CANON / 2f, CANON / 2f, cal.centerX, cal.centerY)
    }

    private fun warpToCanonical(src: Bitmap, cal: TargetCalibration): Bitmap {
        val scale = BLACK_R / cal.blackRadius
        val matrix = Matrix()
        // Translate black center to origin, scale, translate to canonical center
        matrix.postTranslate(-cal.centerX, -cal.centerY)
        matrix.postScale(scale, scale)
        matrix.postTranslate(CANON / 2f, CANON / 2f)
        val out = Bitmap.createBitmap(CANON, CANON, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(215, 218, 222))
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun makeIdealTemplate(): Bitmap {
        val bmp = Bitmap.createBitmap(CANON, CANON, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(215, 218, 222))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 16, 16)
            style = Paint.Style.FILL
        }
        c.drawCircle(CANON / 2f, CANON / 2f, BLACK_R, paint)
        return bmp
    }

    // --- hole mask via superposition ---

    private data class MaskPack(val mask: BooleanArray, val like: FloatArray, val er: Float)

    private fun holeMask(warped: Bitmap, template: Bitmap, sens: Float): MaskPack {
        val s = sens.coerceIn(0f, 1f)
        val wGray = FloatArray(CANON * CANON)
        val tGray = FloatArray(CANON * CANON)
        val chroma = FloatArray(CANON * CANON)
        val backstop = FloatArray(CANON * CANON)
        val wp = IntArray(CANON * CANON)
        val tp = IntArray(CANON * CANON)
        warped.getPixels(wp, 0, CANON, 0, 0, CANON, CANON)
        template.getPixels(tp, 0, CANON, 0, 0, CANON, CANON)
        for (i in wp.indices) {
            val wr = Color.red(wp[i]); val wg = Color.green(wp[i]); val wb = Color.blue(wp[i])
            val tr = Color.red(tp[i]); val tg = Color.green(tp[i]); val tb = Color.blue(tp[i])
            wGray[i] = 0.299f * wr + 0.587f * wg + 0.114f * wb
            tGray[i] = 0.299f * tr + 0.587f * tg + 0.114f * tb
            val mx = max(wr, max(wg, wb)).toFloat()
            val mn = min(wr, min(wg, wb)).toFloat()
            chroma[i] = mx - mn
            // rough HSV-ish backstop: cyan/blue or brown
            val maxc = mx; val minc = mn
            val delta = maxc - minc
            var h = 0f
            if (delta > 1f) {
                h = when (maxc) {
                    wr.toFloat() -> 60f * (((wg - wb) / delta) % 6f)
                    wg.toFloat() -> 60f * ((wb - wr) / delta + 2f)
                    else -> 60f * ((wr - wg) / delta + 4f)
                }
                if (h < 0) h += 360f
            }
            val sat = if (maxc > 1f) delta / maxc else 0f
            val value = maxc
            val cyan = h in 150f..260f && sat > 0.15f && value > 50f
            val brown = (h < 40f || h > 320f) && sat > 0.12f && value > 40f
            backstop[i] = when {
                cyan -> 60f
                brown -> 40f
                else -> 0f
            }
        }

        // Ink level inside black zone
        val inkSamples = ArrayList<Float>()
        for (y in 0 until CANON) {
            for (x in 0 until CANON) {
                val d = hypot(x - CANON / 2f, y - CANON / 2f)
                if (d <= BLACK_R * 0.95f) {
                    inkSamples.add(wGray[y * CANON + x])
                }
            }
        }
        inkSamples.sort()
        val inkCut = inkSamples[(inkSamples.size * 0.35f).toInt().coerceIn(0, inkSamples.lastIndex)]
        val darkInk = inkSamples.filter { it <= inkCut }
        val ink = if (darkInk.isEmpty()) 25f else darkInk[darkInk.size / 2]

        val like = FloatArray(CANON * CANON)
        val vals = ArrayList<Float>()
        for (y in 0 until CANON) {
            for (x in 0 until CANON) {
                val i = y * CANON + x
                val d = hypot(x - CANON / 2f, y - CANON / 2f)
                if (d > BLACK_R * 0.95f) continue
                if (d >= BLACK_R * 0.90f) continue // rim
                val bright = max(0f, wGray[i] - (ink + 20f - s * 10f))
                val diff = max(0f, abs(wGray[i] - tGray[i]) - 30f)
                val v = 0.35f * bright + 0.20f * chroma[i] + 0.30f * backstop[i] + 0.15f * diff
                like[i] = v
                if (v > 3f) vals.add(v)
            }
        }
        val er = max(7f, BLACK_R * 0.075f)
        if (vals.size < 30) {
            return MaskPack(BooleanArray(CANON * CANON), like, er)
        }
        vals.sort()
        val keep = 0.012f + s * 0.022f
        val thr = vals[((vals.size - 1) * (1f - keep)).roundToInt().coerceIn(0, vals.lastIndex)]
        val floor = max(thr * (0.50f - s * 0.1f), 10f)
        val raw = BooleanArray(CANON * CANON)
        for (i in like.indices) {
            raw[i] = like[i] >= floor
        }
        // Close incomplete circles (torn paper edges), then open noise.
        val closeR = max(2, (er * 0.75f).roundToInt())
        val openR = max(1, (er * 0.28f).roundToInt())
        var mask = Morphology.closeBoolFast(raw, CANON, CANON, closeR)
        mask = Morphology.closeBoolFast(mask, CANON, CANON, closeR)
        mask = Morphology.openBoolFast(mask, CANON, CANON, openR)
        return MaskPack(mask, like, er)
    }

    private data class CanonShot(val x: Float, val y: Float, val r: Float, val conf: Float, val score: Int)

    private fun extractShots(
        mask: BooleanArray,
        like: FloatArray,
        er: Float,
        sens: Float
    ): List<CanonShot> {
        val s = sens.coerceIn(0f, 1f)
        val holeArea = (PI * er * er).toFloat()
        val comps = connected(mask, like)
        val dets = ArrayList<CanonShot>()
        val occupied = BooleanArray(CANON * CANON)
        val minConf = 0.22f - s * 0.06f

        for (comp in comps) {
            if (comp.area < holeArea * 0.28f) continue
            val peri = 2f * ((comp.maxX - comp.minX + 1) + (comp.maxY - comp.minY + 1)) * 0.85f
            val circ = (4f * PI.toFloat() * comp.area / (peri * peri + 1e-3f)).coerceIn(0f, 1.5f)
            val conf = (comp.meanLike / 35f).coerceIn(0f, 1f)
            if (conf < minConf) continue

            var est = max(1, (comp.area / (holeArea * 1.05f)).roundToInt())
            val single = est == 1 || (circ > 0.55f && comp.area < holeArea * 1.7f)
            if (single) {
                val r = sqrt(comp.area / PI.toFloat()).coerceIn(er * 0.45f, er * 1.35f)
                if (occupiedNear(occupied, comp.cx, comp.cy, er)) continue
                mark(occupied, comp.cx, comp.cy, r * 0.9f)
                dets.add(
                    CanonShot(comp.cx, comp.cy, r, conf, scoreCanon(comp.cx, comp.cy, r))
                )
            } else {
                est = min(est, max(1, (comp.area / (holeArea * 0.95f)).toInt()))
                val dist = distanceTransform(comp.pixels)
                val minR = er * 0.40f
                val peaks = localMaxima(dist, minR, er * 1.4f, er)
                var taken = 0
                for ((px, py, peak) in peaks.sortedByDescending { it.third }) {
                    if (taken >= est) break
                    if (occupiedNear(occupied, px, py, er)) continue
                    val r = peak.coerceIn(minR, er * 1.4f)
                    mark(occupied, px, py, r * 0.9f)
                    dets.add(CanonShot(px, py, r, conf * 0.85f, scoreCanon(px, py, r)))
                    taken++
                }
                if (taken == 0) {
                    val r = sqrt(comp.area / PI.toFloat()).coerceIn(er * 0.45f, er * 1.35f)
                    dets.add(CanonShot(comp.cx, comp.cy, r, conf, scoreCanon(comp.cx, comp.cy, r)))
                }
            }
        }
        return dets.sortedByDescending { it.conf }.take(11)
    }

    private fun scoreCanon(x: Float, y: Float, r: Float): Int {
        val dist = max(0f, hypot(x - CANON / 2f, y - CANON / 2f) - r)
        val mm = dist * (23.5f / (2f * BLACK_R))
        val diams = floatArrayOf(59.5f, 53.5f, 47.5f, 41.5f, 35.5f, 29.5f, 23.5f, 17.5f, 11.5f, 5.5f)
        for (score in 10 downTo 1) {
            if (mm <= diams[score - 1] / 2f) return score
        }
        return 0
    }

    private data class Comp(
        val area: Float, val cx: Float, val cy: Float, val meanLike: Float,
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
                var head = 0; var tail = 0
                qx[tail] = x; qy[tail] = y; tail++
                labels[idx] = next
                var area = 0; var sx = 0.0; var sy = 0.0; var likeSum = 0.0
                var x0 = x; var x1 = x; var y0 = y; var y1 = y
                val pix = BooleanArray(CANON * CANON)
                while (head < tail) {
                    val cx = qx[head]; val cy = qy[head]; head++
                    area++; sx += cx; sy += cy
                    val p = cy * CANON + cx
                    likeSum += like[p]; pix[p] = true
                    x0 = min(x0, cx); x1 = max(x1, cx)
                    y0 = min(y0, cy); y1 = max(y1, cy)
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx !in 0 until CANON || ny !in 0 until CANON) continue
                        val nIdx = ny * CANON + nx
                        if (mask[nIdx] && labels[nIdx] == 0) {
                            labels[nIdx] = next
                            qx[tail] = nx; qy[tail] = ny; tail++
                        }
                    }
                }
                next++
                out.add(
                    Comp(
                        area.toFloat(), (sx / area).toFloat(), (sy / area).toFloat(),
                        (likeSum / area).toFloat(), x0, x1, y0, y1, pix
                    )
                )
            }
        }
        return out
    }

    private fun distanceTransform(mask: BooleanArray): FloatArray {
        val w = CANON; val h = CANON
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
                while (k > 0 && s <= parabola[k]) { k--; s = sep(envelope[k], q) }
                k++
                envelope[k] = q; parabola[k] = s; parabola[k + 1] = Float.POSITIVE_INFINITY
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
        dist: FloatArray, minR: Float, maxR: Float, er: Float
    ): List<Triple<Float, Float, Float>> {
        val peaks = ArrayList<Triple<Float, Float, Float>>()
        val n = max(2, (er * 0.55f).roundToInt())
        val minPeak = minR * 0.75f
        for (y in n until CANON - n) {
            for (x in n until CANON - n) {
                val v = dist[y * CANON + x]
                if (v < minPeak || v > maxR) continue
                var isMax = true
                outer@ for (dy in -n..n) for (dx in -n..n) {
                    if (dx == 0 && dy == 0) continue
                    if (dist[(y + dy) * CANON + (x + dx)] > v) { isMax = false; break@outer }
                }
                if (isMax) peaks.add(Triple(x.toFloat(), y.toFloat(), v))
            }
        }
        return peaks
    }

    private fun occupiedNear(occupied: BooleanArray, cx: Float, cy: Float, er: Float): Boolean {
        val r = er.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r); val x1 = min(CANON - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r); val y1 = min(CANON - 1, cy.roundToInt() + r)
        val r2 = er * er
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2 && occupied[y * CANON + x]) return true
        }
        return false
    }

    private fun mark(occupied: BooleanArray, cx: Float, cy: Float, radius: Float) {
        val r = radius.roundToInt().coerceAtLeast(1)
        val x0 = max(0, cx.roundToInt() - r); val x1 = min(CANON - 1, cx.roundToInt() + r)
        val y0 = max(0, cy.roundToInt() - r); val y1 = min(CANON - 1, cy.roundToInt() + r)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) occupied[y * CANON + x] = true
        }
    }
}
