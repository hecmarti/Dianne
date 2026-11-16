package com.dianne.krugerscorer.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.dianne.krugerscorer.model.AnalysisResult
import com.dianne.krugerscorer.model.ShotDetection
import com.dianne.krugerscorer.model.TargetCalibration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 10 m air-pistol target scoring (logic aligned with App Tiro v2).
 *
 * - Black aiming mark = 29.75 mm radius (outer edge of ring 7)
 * - Pellet radius = 2.25 mm
 * - Local brightness vs blurred background
 * - Blobs → N circles by XOR cost
 * - Prefer a combination that sums to [expectedShots] (default 10)
 */
object PistolPipeline {

    const val HOLE_R_MM = 2.25f
    const val BLACK_R_MM = 29.75f
    const val R_OBJ = 320f
    const val DUDA_MM = 0.8f
    const val EXPECTED_SHOTS = 10
    private val A1 = (PI * HOLE_R_MM * HOLE_R_MM).toFloat()
    /** Outer radii (mm) of scoring rings 10..7 for air pistol. */
    private val RING_R_MM = floatArrayOf(5.75f, 13.75f, 21.75f, 29.75f) // 10,9,8,7

    data class Output(
        val result: AnalysisResult,
        val workingBitmap: Bitmap,
        val avisos: List<String> = emptyList()
    )

    fun analyze(source: Bitmap, sensitivity: Float): Output {
        val (_, working) = ImageBuffer.fromBitmap(source, maxSide = 1100)
        val k1 = min(1f, 800f / max(working.width, working.height))
        val peq = if (k1 < 0.999f) {
            Bitmap.createScaledBitmap(
                working,
                max(1, (working.width * k1).roundToInt()),
                max(1, (working.height * k1).roundToInt()),
                true
            )
        } else working
        val c1 = findBlack(peq) ?: return Output(
            AnalysisResult(null, emptyList(), sensitivity),
            working,
            listOf("No encuentro el círculo negro. Foto de frente y entera.")
        )
        val cxF = c1.cx / k1
        val cyF = c1.cy / k1
        val rF = c1.r / k1
        val lado = 2.3f * rF
        val sxWant = cxF - lado / 2f
        val syWant = cyF - lado / 2f
        val cropX0 = max(0, floor(sxWant).toInt())
        val cropY0 = max(0, floor(syWant).toInt())
        val crop = cropBitmap(working, sxWant, syWant, lado, lado)
        val esc = R_OBJ / rF
        val img = Bitmap.createScaledBitmap(
            crop,
            max(1, (crop.width * esc).roundToInt()),
            max(1, (crop.height * esc).roundToInt()),
            true
        )
        val c2 = findBlack(img) ?: return Output(
            AnalysisResult(null, emptyList(), sensitivity),
            working,
            listOf("No encuentro bien el círculo negro. Repite más de frente.")
        )
        val pxmm = c2.r / BLACK_R_MM
        val gray = FloatArray(img.width * img.height)
        val value = FloatArray(img.width * img.height)
        fillGrayValue(img, gray, value)

        val s = sensitivity.coerceIn(0f, 1f)
        // Sensitivity tweaks local jump: lower sens → higher salto → fewer holes
        val techoF = 0.22f + s * 0.08f
        val saltoF = 0.42f - s * 0.14f
        val minSalto = 40f - s * 12f

        val (mask, fondo, salto) = localHoleMask(
            gray, value, img.width, img.height, c2.cx, c2.cy, c2.r, pxmm,
            techoF, saltoF, minSalto
        )
        val bites = edgeBites(gray, fondo, img.width, img.height, c2.cx, c2.cy, c2.r, pxmm, salto)
        val comps = connected(mask, img.width, img.height)
        val a1px = A1 * pxmm * pxmm
        val rp = HOLE_R_MM * pxmm
        val avisos = ArrayList<String>()
        var tapado = false

        val borderAngles = HashMap<Int, ArrayList<Float>>()
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val id = comps.labels[y * img.width + x]
                if (id == 0) continue
                if (hypot(x - c2.cx, y - c2.cy) >= c2.r * 0.95f) {
                    borderAngles.getOrPut(id) { ArrayList() }.add(atan2(y - c2.cy, x - c2.cx))
                }
            }
        }

        data class Mancha(
            val sub: BooleanArray,
            val mw: Int,
            val mh: Int,
            val ox: Int,
            val oy: Int,
            val ratio: Float,
            val nmin: Int,
            val nmax: Int,
            val ajustes: HashMap<Int, Pair<List<Pair<Float, Float>>, Float>>
        )

        val manchas = ArrayList<Mancha>()
        for (c in comps.comps) {
            if (c.area < 0.35f * a1px) continue
            val cxb = c.sx / c.area
            val cyb = c.sy / c.area
            if (c.area < 1.6f * a1px && nearBite(bites, cxb, cyb, pxmm)) continue
            val angs = borderAngles[c.id]
            if (angs != null && borderWidthMm(angs) > 6.5f) {
                tapado = true
                continue
            }
            val pad = ceil(3f * pxmm).toInt()
            val x0 = max(0, c.x0 - pad)
            val y0 = max(0, c.y0 - pad)
            val x1 = min(img.width - 1, c.x1 + pad)
            val y1 = min(img.height - 1, c.y1 + pad)
            val mw = x1 - x0 + 1
            val mh = y1 - y0 + 1
            val sub = BooleanArray(mw * mh)
            for (yy in y0..y1) for (xx in x0..x1) {
                if (comps.labels[yy * img.width + xx] == c.id) {
                    sub[(yy - y0) * mw + (xx - x0)] = true
                }
            }
            val ratio = c.area / a1px
            val nmin = max(1, floor(ratio * 0.6f).toInt())
            val nmax = max(1, ceil(ratio).toInt()) + 1
            manchas.add(Mancha(sub, mw, mh, x0, y0, ratio, nmin, nmax, HashMap()))
        }

        for ((qi, mc) in manchas.withIndex()) {
            for (n in mc.nmin..mc.nmax) {
                mc.ajustes[n] = fitCircles(mc.sub, mc.mw, mc.mh, n, rp, seed = 7 + qi * 31 + n)
            }
        }

        val restoTarget = max(0, EXPECTED_SHOTS - bites.size)
        var bestCost = Float.POSITIVE_INFINITY
        var bestReparto: IntArray? = null

        fun buscar(idx: Int, resto: Int, coste: Float, reparto: IntArray, depth: Int) {
            if (coste >= bestCost) return
            if (idx == manchas.size) {
                if (resto == 0) {
                    bestCost = coste
                    bestReparto = reparto.copyOf()
                }
                return
            }
            val mc = manchas[idx]
            for (n2 in mc.nmin..min(mc.nmax, resto)) {
                reparto[depth] = n2
                buscar(idx + 1, resto - n2, coste + mc.ajustes[n2]!!.second, reparto, depth + 1)
            }
        }
        if (manchas.isNotEmpty()) {
            buscar(0, restoTarget, 0f, IntArray(manchas.size), 0)
        } else if (restoTarget == 0) {
            bestReparto = IntArray(0)
        }

        if (tapado) avisos.add("Algo tapa el borde del negro (¿un dedo?): no lo he contado.")

        val reparto = if (bestReparto != null) {
            bestReparto!!
        } else {
            val est = IntArray(manchas.size) { i ->
                val mc = manchas[i]
                min(mc.nmax, max(mc.nmin, mc.ratio.roundToInt()))
            }
            val vistos = est.sum() + bites.size
            avisos.add("Veo $vistos impactos, no $EXPECTED_SHOTS. Corrige con +/−.")
            est
        }

        data class Hit(val x: Float, val y: Float, val dMm: Float, val score: Int, val duda: Boolean)

        val hits = ArrayList<Hit>()
        for ((bx, by, d) in bites) {
            val sc = scoreOf(d)
            hits.add(Hit(bx, by, d, sc, abs(margenOf(d)) <= DUDA_MM))
        }
        for (i in manchas.indices) {
            val mc = manchas[i]
            val n = if (i < reparto.size) reparto[i] else mc.nmin
            val cents = mc.ajustes[n]?.first.orEmpty()
            for ((lx, ly) in cents) {
                val gx = lx + mc.ox
                val gy = ly + mc.oy
                val d = hypot(gx - c2.cx, gy - c2.cy) / pxmm
                val sc = scoreOf(d)
                hits.add(Hit(gx, gy, d, sc, margenOf(d) <= DUDA_MM))
            }
        }
        hits.sortBy { it.dMm }
        val dud = hits.count { it.duda }
        if (dud > 0) avisos.add("$dud disparo(s) muy cerca de una línea: revísalos.")

        // Map analysis-image coords → working bitmap (crop origin + unscale)
        val scaleBack = 1f / esc
        val dets = hits.mapIndexed { idx, h ->
            val wx = cropX0 + h.x * scaleBack
            val wy = cropY0 + h.y * scaleBack
            val wr = HOLE_R_MM * (rF / BLACK_R_MM)
            ShotDetection(
                id = idx,
                x = wx,
                y = wy,
                radius = wr,
                score = h.score,
                confidence = if (h.duda) 0.35f else 0.85f
            )
        }
        val cal = TargetCalibration(cxF, cyF, rF, working.width, working.height)
        return Output(AnalysisResult(cal, dets, sensitivity), working, avisos)
    }

    // --- geometry / scoring ---

    private fun scoreOf(dMm: Float): Int {
        for (i in RING_R_MM.indices) {
            val s = 10 - i
            if (dMm <= RING_R_MM[i] + HOLE_R_MM) return s
        }
        return 6
    }

    private fun margenOf(dMm: Float): Float {
        var m = 1e9f
        for (r in RING_R_MM) m = min(m, abs(dMm - (r + HOLE_R_MM)))
        return m
    }

    private data class Circle(val cx: Float, val cy: Float, val r: Float)

    private fun findBlack(bmp: Bitmap): Circle? {
        val w = bmp.width
        val h = bmp.height
        val gray = FloatArray(w * h)
        val value = FloatArray(w * h)
        fillGrayValue(bmp, gray, value)
        // Approximate S: chroma / value — use gray as V proxy; dark & low-sat ≈ gray < 105
        val mask = BooleanArray(w * h)
        for (i in gray.indices) {
            mask[i] = value[i] < 105f && gray[i] < 110f
        }
        val rMorph = max(2, min(w, h) / 120)
        var m = Morphology.closeBoolFast(mask, w, h, rMorph)
        val comps = connected(m, w, h)
        val best = comps.comps.maxByOrNull { it.area } ?: return null
        if (best.area < (min(w, h) * 0.12f) * (min(w, h) * 0.12f)) return null

        // Fill interior holes of the component
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(p: Int) {
            if (p < 0 || p >= outside.size) return
            if (outside[p]) return
            if (comps.labels[p] == best.id) return
            outside[p] = true
            stack[sp++] = p
        }
        for (x in 0 until w) {
            seed(x)
            seed((h - 1) * w + x)
        }
        for (y in 0 until h) {
            seed(y * w)
            seed(y * w + w - 1)
        }
        while (sp > 0) {
            val p = stack[--sp]
            val x = p % w
            val y = p / w
            if (x > 0) seed(p - 1)
            if (x < w - 1) seed(p + 1)
            if (y > 0) seed(p - w)
            if (y < h - 1) seed(p + w)
        }
        var area = 0
        var sx = 0.0
        var sy = 0.0
        val edge = ArrayList<Pair<Float, Float>>()
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            if (outside[p]) continue
            area++
            sx += x
            sy += y
            if (x in 1 until w - 1 && y in 1 until h - 1) {
                if (outside[p - 1] || outside[p + 1] || outside[p - w] || outside[p + w]) {
                    edge.add(x.toFloat() to y.toFloat())
                }
            }
        }
        if (area < 50) return null
        var cx = (sx / area).toFloat()
        var cy = (sy / area).toFloat()
        var r = sqrt(area / PI.toFloat())
        val refined = fitCircleKasa(edge, cx, cy, r)
        if (refined != null && abs(refined.r - r) < 0.2f * r) {
            return refined
        }
        val bw = (best.x1 - best.x0 + 1).toFloat()
        val bh = (best.y1 - best.y0 + 1).toFloat()
        val round = abs(bw - 2 * r) <= 0.30f * r && abs(bh - 2 * r) <= 0.30f * r
        return if (round) Circle(cx, cy, r) else Circle(cx, cy, r) // accept best effort
    }

    private fun fitCircleKasa(
        pts: List<Pair<Float, Float>>,
        cx0: Float,
        cy0: Float,
        r0: Float
    ): Circle? {
        if (pts.size < 30) return null
        var cx = cx0
        var cy = cy0
        var r = r0
        var use = pts
        repeat(6) {
            val n = use.size.toDouble()
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            var sxz = 0.0
            var syz = 0.0
            var sz = 0.0
            for ((px, py) in use) {
                val x = px - cx
                val y = py - cy
                val z = x * x + y * y
                sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
                sxz += x * z; syz += y * z; sz += z
            }
            // Solve 3x3
            val A = arrayOf(
                doubleArrayOf(sxx, sxy, sx, -sxz),
                doubleArrayOf(sxy, syy, sy, -syz),
                doubleArrayOf(sx, sy, n, -sz)
            )
            for (c in 0..2) {
                var piv = c
                for (rr in c + 1..2) if (abs(A[rr][c]) > abs(A[piv][c])) piv = rr
                val tmp = A[c]; A[c] = A[piv]; A[piv] = tmp
                if (abs(A[c][c]) < 1e-9) return null
                for (r2 in 0..2) {
                    if (r2 == c) continue
                    val f = A[r2][c] / A[c][c]
                    for (k in c..3) A[r2][k] -= f * A[c][k]
                }
            }
            val D = A[0][3] / A[0][0]
            val E = A[1][3] / A[1][1]
            val F = A[2][3] / A[2][2]
            val nR2 = D * D / 4 + E * E / 4 - F
            if (nR2 <= 0 || !nR2.isFinite()) return null
            cx = (cx - D / 2).toFloat()
            cy = (cy - E / 2).toFloat()
            r = sqrt(nR2).toFloat()
            val res = pts.map { abs(hypot(it.first - cx, it.second - cy) - r) }
            val med = res.sorted()[res.size / 2]
            val lim = max(1.5f, 3f * med)
            use = pts.filterIndexed { i, _ -> res[i] <= lim }
            if (use.size < 30) return null
        }
        return Circle(cx, cy, r)
    }

    private fun localHoleMask(
        gray: FloatArray,
        value: FloatArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        R: Float,
        pxmm: Float,
        techoF: Float,
        saltoF: Float,
        minSalto: Float
    ): Triple<BooleanArray, FloatArray, Float> {
        val dentro = BooleanArray(w * h)
        val vals = ArrayList<Float>()
        val r2 = (R * 0.985f) * (R * 0.985f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r2) {
                dentro[i] = true
                vals.add(value[i])
            }
        }
        if (vals.size < 100) return Triple(BooleanArray(w * h), FloatArray(w * h), 35f)
        vals.sort()
        val med = vals[vals.size / 2]
        val alto = vals[(vals.size * 0.995f).toInt().coerceIn(0, vals.lastIndex)]
        val techo = med + techoF * (alto - med)
        val clipped = FloatArray(w * h)
        for (i in clipped.indices) {
            clipped[i] = if (dentro[i]) min(value[i], techo) else med
        }
        val fondo = FloatArray(w * h)
        val rad = max(3, (4f * pxmm).roundToInt())
        clipped.blurBox(w, h, rad, fondo)
        val salto = max(minSalto, saltoF * (alto - med))
        val raw = BooleanArray(w * h)
        for (i in raw.indices) {
            raw[i] = dentro[i] && value[i] > fondo[i] + salto
        }
        var m = Morphology.closeBoolFast(raw, w, h, 2)
        val openR = max(1, (1.1f * pxmm).roundToInt())
        m = Morphology.openBoolFast(m, w, h, openR)
        return Triple(m, fondo, salto)
    }

    private data class Bite(val x: Float, val y: Float, val d: Float)

    private fun edgeBites(
        gray: FloatArray,
        fondo: FloatArray,
        w: Int,
        h: Int,
        cx: Float,
        cy: Float,
        R: Float,
        pxmm: Float,
        salto: Float
    ): List<Bite> {
        val n = 720
        val rb = FloatArray(n) { R }
        val r0 = R - 2.6f * pxmm
        val r1 = R + 0.3f * pxmm
        for (i in 0 until n) {
            val a = i * 2f * PI.toFloat() / n
            val ca = cos(a)
            val sa = sin(a)
            var r = r0
            while (r <= r1) {
                val x = (cx + r * ca).roundToInt()
                val y = (cy + r * sa).roundToInt()
                if (x !in 0 until w || y !in 0 until h) break
                val p = y * w + x
                if (gray[p] > fondo[p] + salto) {
                    rb[i] = r
                    break
                }
                r += 0.5f
            }
        }
        val lim = R - 0.7f * pxmm
        val seen = BooleanArray(n)
        val out = ArrayList<Bite>()
        for (i in 0 until n) {
            if (seen[i] || rb[i] >= lim) continue
            var ini = i
            while (rb[(ini - 1 + n) % n] < lim && (ini - 1 + n) % n != i) {
                ini = (ini - 1 + n) % n
            }
            var k = ini
            var count = 0
            var minR = R
            var aMin = 0
            while (rb[k] < lim && count < n) {
                seen[k] = true
                if (rb[k] < minR) {
                    minR = rb[k]
                    aMin = k
                }
                count++
                k = (k + 1) % n
            }
            val ancho = count * 2f * PI.toFloat() / n * R / pxmm
            if (ancho in 1.2f..6.5f && minR > r0 + 1f) {
                val D = (R - minR) / pxmm
                val dist = R / pxmm - D + HOLE_R_MM
                val ang = aMin * 2f * PI.toFloat() / n
                out.add(Bite(cx + dist * pxmm * cos(ang), cy + dist * pxmm * sin(ang), dist))
            }
        }
        return out
    }

    private fun nearBite(bites: List<Bite>, x: Float, y: Float, pxmm: Float): Boolean =
        bites.any { hypot(it.x - x, it.y - y) < 3.5f * pxmm }

    private fun borderWidthMm(angs: List<Float>): Float {
        if (angs.size < 2) return 0f
        val sorted = angs.sorted()
        var maxGap = (2 * PI - (sorted.last() - sorted.first())).toFloat()
        for (i in 1 until sorted.size) {
            maxGap = max(maxGap, sorted[i] - sorted[i - 1])
        }
        return ((2 * PI).toFloat() - maxGap) * BLACK_R_MM
    }

    private fun fitCircles(
        mask: BooleanArray,
        w: Int,
        h: Int,
        n: Int,
        rp: Float,
        seed: Int
    ): Pair<List<Pair<Float, Float>>, Float> {
        val pts = ArrayList<Pair<Float, Float>>()
        for (y in 0 until h) for (x in 0 until w) {
            if (mask[y * w + x]) pts.add(x.toFloat() to y.toFloat())
        }
        if (pts.size < 5) return emptyList<Pair<Float, Float>>() to 1e9f
        val rng = Random(seed)
        val r2 = rp * rp
        var bestCost = Float.POSITIVE_INFINITY
        var bestC: Array<FloatArray>? = null
        val step = max(1, pts.size / 800)
        val sample = pts.filterIndexed { i, _ -> i % step == 0 }

        fun cost(c: Array<FloatArray>): Float {
            var err = 0
            for (y in 0 until h) for (x in 0 until w) {
                var cov = false
                for (k in 0 until n) {
                    val dx = x - c[k][0]
                    val dy = y - c[k][1]
                    if (dx * dx + dy * dy <= r2) {
                        cov = true
                        break
                    }
                }
                if (cov != mask[y * w + x]) err++
            }
            return err.toFloat()
        }

        repeat(4) {
            val c = Array(n) {
                val p = pts[rng.nextInt(pts.size)]
                floatArrayOf(p.first, p.second)
            }
            repeat(10) {
                val acc = Array(n) { FloatArray(3) }
                for (p in sample) {
                    var best = 0
                    var bd = Float.POSITIVE_INFINITY
                    for (k in 0 until n) {
                        val dx = p.first - c[k][0]
                        val dy = p.second - c[k][1]
                        val dd = dx * dx + dy * dy
                        if (dd < bd) {
                            bd = dd
                            best = k
                        }
                    }
                    acc[best][0] += p.first
                    acc[best][1] += p.second
                    acc[best][2] += 1f
                }
                for (k in 0 until n) if (acc[k][2] > 0) {
                    c[k][0] = acc[k][0] / acc[k][2]
                    c[k][1] = acc[k][1] / acc[k][2]
                }
            }
            for (st in floatArrayOf(3f, 1.5f, 0.75f)) {
                var improved = true
                var turns = 0
                while (improved && turns < 20) {
                    improved = false
                    turns++
                    val cur = cost(c)
                    outer@ for (k in 0 until n) {
                        for (dir in 0 until 8) {
                            val ang = dir * PI.toFloat() / 4f
                            val trial = Array(n) { i -> c[i].clone() }
                            trial[k][0] += st * cos(ang).roundToInt()
                            trial[k][1] += st * sin(ang).roundToInt()
                            val nc = cost(trial)
                            if (nc < cur) {
                                for (i in 0 until n) {
                                    c[i][0] = trial[i][0]
                                    c[i][1] = trial[i][1]
                                }
                                improved = true
                                break@outer
                            }
                        }
                    }
                }
            }
            val tot = cost(c)
            if (tot < bestCost) {
                bestCost = tot
                bestC = Array(n) { c[it].clone() }
            }
        }
        val cents = bestC?.map { it[0] to it[1] }.orEmpty()
        return cents to bestCost
    }

    // --- components ---

    private data class Comp(
        val id: Int,
        val area: Float,
        val sx: Float,
        val sy: Float,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int
    )

    private data class CompResult(val labels: IntArray, val comps: List<Comp>)

    private fun connected(mask: BooleanArray, w: Int, h: Int): CompResult {
        val labels = IntArray(w * h)
        val comps = ArrayList<Comp>()
        val stack = IntArray(w * h)
        var next = 1
        for (y in 0 until h) for (x in 0 until w) {
            val idx = y * w + x
            if (!mask[idx] || labels[idx] != 0) continue
            var sp = 0
            stack[sp++] = idx
            labels[idx] = next
            var area = 0
            var sx = 0.0
            var sy = 0.0
            var x0 = x
            var x1 = x
            var y0 = y
            var y1 = y
            while (sp > 0) {
                val p = stack[--sp]
                val cx = p % w
                val cy = p / w
                area++
                sx += cx
                sy += cy
                x0 = min(x0, cx); x1 = max(x1, cx)
                y0 = min(y0, cy); y1 = max(y1, cy)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (mask[ni] && labels[ni] == 0) {
                        labels[ni] = next
                        stack[sp++] = ni
                    }
                }
            }
            comps.add(Comp(next, area.toFloat(), sx.toFloat(), sy.toFloat(), x0, y0, x1, y1))
            next++
        }
        return CompResult(labels, comps)
    }

    private fun fillGrayValue(bmp: Bitmap, gray: FloatArray, value: FloatArray) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c).toFloat()
            val g = Color.green(c).toFloat()
            val b = Color.blue(c).toFloat()
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            value[i] = max(r, max(g, b))
        }
    }

    private fun cropBitmap(src: Bitmap, sx: Float, sy: Float, sw: Float, sh: Float): Bitmap {
        val x0 = max(0, floor(sx).toInt())
        val y0 = max(0, floor(sy).toInt())
        val x1 = min(src.width, ceil(sx + sw).toInt())
        val y1 = min(src.height, ceil(sy + sh).toInt())
        val cw = max(1, x1 - x0)
        val ch = max(1, y1 - y0)
        return Bitmap.createBitmap(src, x0, y0, cw, ch)
    }
}
