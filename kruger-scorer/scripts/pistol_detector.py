#!/usr/bin/env python3
"""
Dianne pistol-target detector — logic aligned with App Tiro v2 (analizador.js).

- Black mark = 29.75 mm radius (10 m air pistol ring 7)
- Pellet radius = 2.25 mm
- Local brightness threshold vs blurred background
- Blobs → N circles by XOR cost (k-means + local search)
- Prefer combination summing to 10 shots
- Edge bites (mordiscos) + wide border rejection (fingers)
"""
from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

HOLE_R = 2.25
RINGS = {10: 5.75, 9: 13.75, 8: 21.75, 7: 29.75}  # mm radii
DUDA = 0.8
R_OBJ = 320
A1 = np.pi * HOLE_R * HOLE_R
BLACK_MM = 29.75

# Known totals from App Tiro progreso.md (14-11 session)
GT_14 = {
    "IMG_20261114_180333": 94,
    "IMG_20261114_180457": 91,
    "IMG_20261114_180520": 92,
    "IMG_20261114_180543": 90,
    "IMG_20261114_180618": 91,
    "IMG_20261114_180632": 89,
}


def puntos_de(d: float) -> int:
    for s in (10, 9, 8, 7):
        if d <= RINGS[s] + HOLE_R:
            return s
    return 6


def margen(d: float) -> float:
    return min(abs(d - (RINGS[s] + HOLE_R)) for s in (10, 9, 8, 7))


def find_black(img_bgr: np.ndarray):
    hsv = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2HSV)
    V, S = hsv[..., 2], hsv[..., 1]
    dark = ((V < 105) & (S < 110)).astype(np.uint8) * 255
    k = max(5, int(min(img_bgr.shape[:2]) / 120)) | 1
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    dark = cv2.morphologyEx(dark, cv2.MORPH_CLOSE, ker)
    cnts, _ = cv2.findContours(dark, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    if not cnts:
        return None
    h, w = dark.shape
    # Fill holes: flood from borders on inverted labels of largest component
    best = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(best)
    if area < (min(w, h) * 0.12) ** 2:
        return None
    mask = np.zeros((h, w), np.uint8)
    cv2.drawContours(mask, [best], -1, 255, -1)
    # Flood exterior
    ff = mask.copy()
    flood = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(ff, flood, (0, 0), 128)
    filled = ((ff != 128)).astype(np.uint8) * 255
    # Also fill if border wasn't black: use component fill
    filled = cv2.bitwise_or(filled, mask)
    ys, xs = np.nonzero(filled)
    if len(xs) < 100:
        return None
    area_f = float(len(xs))
    cx = float(xs.mean())
    cy = float(ys.mean())
    R = float(np.sqrt(area_f / np.pi))
    # Edge points for Kasa refine
    edges = cv2.Canny(filled, 50, 150)
    ey, ex = np.nonzero(edges)
    if len(ex) >= 40:
        pts = np.column_stack([ex.astype(np.float64), ey.astype(np.float64)])
        for _ in range(5):
            dx = pts[:, 0] - cx
            dy = pts[:, 1] - cy
            res = np.abs(np.hypot(dx, dy) - R)
            med = np.median(res)
            lim = max(1.5, 3 * med)
            keep = res <= lim
            if keep.sum() < 30:
                break
            p = pts[keep]
            # algebraic fit relative to current center
            x = p[:, 0] - cx
            y = p[:, 1] - cy
            z = x * x + y * y
            n = len(p)
            Sx, Sy = x.sum(), y.sum()
            Sxx, Syy, Sxy = (x * x).sum(), (y * y).sum(), (x * y).sum()
            Sxz, Syz, Sz = (x * z).sum(), (y * z).sum(), z.sum()
            A = np.array([[Sxx, Sxy, Sx], [Sxy, Syy, Sy], [Sx, Sy, n]], dtype=np.float64)
            b = -np.array([Sxz, Syz, Sz], dtype=np.float64)
            try:
                D, E, F = np.linalg.solve(A, b)
            except np.linalg.LinAlgError:
                break
            nR2 = D * D / 4 + E * E / 4 - F
            if nR2 <= 0:
                break
            ncx, ncy, nR = cx - D / 2, cy - E / 2, np.sqrt(nR2)
            if abs(nR - R) > 0.25 * R:
                break
            cx, cy, R = float(ncx), float(ncy), float(nR)
    # Roundness check
    x0, y0, bw, bh = cv2.boundingRect(best)
    if abs(bw - 2 * R) > 0.30 * R or abs(bh - 2 * R) > 0.30 * R:
        # still accept if area-based R is plausible
        if R < min(w, h) * 0.12 or R > min(w, h) * 0.48:
            return None
    return cx, cy, R


def local_hole_mask(gray: np.ndarray, cx, cy, R, pxmm, params=None):
    P = params or dict(techo=0.25, radio=4.0, salto=0.35, minSalto=35)
    h, w = gray.shape
    yy, xx = np.mgrid[:h, :w]
    d2 = (xx - cx) ** 2 + (yy - cy) ** 2
    dentro = d2 < (R * 0.985) ** 2
    vals = gray[dentro]
    if vals.size < 100:
        return np.zeros_like(gray, np.uint8), 0.0
    med = float(np.median(vals))
    alto = float(np.percentile(vals, 99.5))
    techo = med + P["techo"] * (alto - med)
    clipped = np.where(dentro, np.minimum(gray.astype(np.float64), techo), med)
    rad = max(3, int(round(P["radio"] * pxmm))) | 1
    fondo = cv2.blur(clipped, (rad, rad))
    salto = max(P["minSalto"], P["salto"] * (alto - med))
    m = ((dentro) & (gray > fondo + salto)).astype(np.uint8) * 255
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    ok = max(3, int(round(1.1 * pxmm))) | 1
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ok, ok)))
    return m, salto


def mordiscos(gray, fondo, cx, cy, R, pxmm, salto):
    N = 720
    r0 = R - 2.6 * pxmm
    r1 = R + 0.3 * pxmm
    rb = np.full(N, R, np.float64)
    h, w = gray.shape
    for i in range(N):
        a = i * 2 * np.pi / N
        ca, sa = np.cos(a), np.sin(a)
        for r in np.arange(r0, r1 + 1e-6, 0.5):
            x = int(round(cx + r * ca))
            y = int(round(cy + r * sa))
            if not (0 <= x < w and 0 <= y < h):
                break
            if gray[y, x] > fondo[y, x] + salto:
                rb[i] = r
                break
    lim = R - 0.7 * pxmm
    visto = np.zeros(N, np.uint8)
    out = []
    for i in range(N):
        if visto[i] or rb[i] >= lim:
            continue
        ini = i
        while rb[(ini - 1) % N] < lim and (ini - 1) % N != i:
            ini = (ini - 1) % N
        k = ini
        n = 0
        minR = R
        aMin = 0
        while rb[k] < lim and n < N:
            visto[k] = 1
            if rb[k] < minR:
                minR = rb[k]
                aMin = k
            n += 1
            k = (k + 1) % N
        ancho = n * 2 * np.pi / N * R / pxmm
        if 1.2 <= ancho <= 6.5 and minR > r0 + 1:
            D = (R - minR) / pxmm
            dist = R / pxmm - D + HOLE_R
            ang = aMin * 2 * np.pi / N
            out.append((cx + dist * pxmm * np.cos(ang), cy + dist * pxmm * np.sin(ang), dist))
    return out


def fit_circles(mask_u8, n, rp, seed=1):
    """K-means on blob pixels + vectorized XOR refine (App Tiro spirit, fast)."""
    m = mask_u8 > 0
    h, w = m.shape
    ys, xs = np.nonzero(m)
    if len(xs) < 5:
        return [], 1e9
    pts = np.column_stack([xs, ys]).astype(np.float64)
    rng = np.random.default_rng(seed)
    r2 = rp * rp
    yy, xx = np.mgrid[:h, :w].astype(np.float64)

    def coverage(cents):
        cov = np.zeros((h, w), dtype=bool)
        for cx, cy in cents:
            cov |= (xx - cx) ** 2 + (yy - cy) ** 2 <= r2
        return cov

    def cost(cents):
        return int(np.sum(coverage(cents) != m))

    best_cost = 1e18
    best_c = None
    sample = pts[:: max(1, len(pts) // 800)]

    for attempt in range(5):
        idx = rng.choice(len(pts), size=n, replace=True)
        c = pts[idx].copy()
        for _ in range(12):
            d2 = ((sample[:, None, :] - c[None, :, :]) ** 2).sum(axis=2)
            lab = d2.argmin(axis=1)
            for k in range(n):
                sel = sample[lab == k]
                if len(sel):
                    c[k] = sel.mean(axis=0)
        # local refine: try small moves per center
        for st in (3.0, 1.5, 0.75):
            improved = True
            turns = 0
            while improved and turns < 25:
                improved = False
                turns += 1
                cur = cost(c)
                for k in range(n):
                    for dir_i in range(8):
                        ang = dir_i * np.pi / 4
                        trial = c.copy()
                        trial[k, 0] += st * np.round(np.cos(ang))
                        trial[k, 1] += st * np.round(np.sin(ang))
                        nc = cost(trial)
                        if nc < cur:
                            c = trial
                            cur = nc
                            improved = True
                            break
                    if improved:
                        break
        tot = cost(c)
        if tot < best_cost:
            best_cost = tot
            best_c = c.copy()
    return [(float(x), float(y)) for x, y in best_c], float(best_cost)


def analyze(path: str, expect_n: int = 10):
    bgr0 = cv2.imread(path)
    if bgr0 is None:
        return dict(error="no open")
    h0, w0 = bgr0.shape[:2]
    k1 = min(1.0, 800 / max(w0, h0))
    peq = cv2.resize(bgr0, (int(w0 * k1), int(h0 * k1)), interpolation=cv2.INTER_AREA)
    c1 = find_black(peq)
    if c1 is None:
        return dict(error="no black (pass1)")
    cxF, cyF, RF = c1[0] / k1, c1[1] / k1, c1[2] / k1
    lado = 2.3 * RF
    sx, sy = cxF - lado / 2, cyF - lado / 2
    # crop from original
    x0 = int(max(0, sx))
    y0 = int(max(0, sy))
    x1 = int(min(w0, sx + lado))
    y1 = int(min(h0, sy + lado))
    crop = bgr0[y0:y1, x0:x1]
    esc = R_OBJ / RF
    img = cv2.resize(crop, (int(crop.shape[1] * esc), int(crop.shape[0] * esc)), interpolation=cv2.INTER_AREA)
    c2 = find_black(img)
    if c2 is None:
        return dict(error="no black (pass2)")
    cx, cy, R = c2
    pxmm = R / BLACK_MM
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float64)

    # local fondo for mordiscos
    h, w = gray.shape
    yy, xx = np.mgrid[:h, :w]
    dentro = (xx - cx) ** 2 + (yy - cy) ** 2 < (R * 0.985) ** 2
    vals = gray[dentro]
    med = float(np.median(vals))
    alto = float(np.percentile(vals, 99.5))
    techo = med + 0.25 * (alto - med)
    clipped = np.where(dentro, np.minimum(gray, techo), med)
    rad = max(3, int(round(4.0 * pxmm))) | 1
    fondo = cv2.blur(clipped, (rad, rad))
    salto = max(35.0, 0.35 * (alto - med))

    mask, _ = local_hole_mask(gray.astype(np.uint8), cx, cy, R, pxmm)
    bordes = mordiscos(gray, fondo, cx, cy, R, pxmm, salto)

    nl, lab, st, _ = cv2.connectedComponentsWithStats(mask)
    a1px = A1 * pxmm * pxmm
    rp = HOLE_R * pxmm
    manchas = []
    avisos = []
    tapado = False

    # border angles per label
    borde_comp = {}
    ys, xs = np.nonzero(lab > 0)
    for x, y, lab_i in zip(xs, ys, lab[ys, xs]):
        if np.hypot(x - cx, y - cy) >= R * 0.95:
            borde_comp.setdefault(int(lab_i), []).append(np.arctan2(y - cy, x - cx))

    def ancho_borde(angs):
        if len(angs) < 2:
            return 0.0
        angs = sorted(angs)
        max_gap = 2 * np.pi - (angs[-1] - angs[0])
        for i in range(1, len(angs)):
            max_gap = max(max_gap, angs[i] - angs[i - 1])
        return (2 * np.pi - max_gap) * BLACK_MM

    def cerca_mordisco(x, y):
        return any(np.hypot(bx - x, by - y) < 3.5 * pxmm for bx, by, _ in bordes)

    for i in range(1, nl):
        area = st[i, cv2.CC_STAT_AREA]
        if area < 0.35 * a1px:
            continue
        cxb = st[i, cv2.CC_STAT_LEFT] + st[i, cv2.CC_STAT_WIDTH] / 2
        cyb = st[i, cv2.CC_STAT_TOP] + st[i, cv2.CC_STAT_HEIGHT] / 2
        # better centroid
        ys_i, xs_i = np.nonzero(lab == i)
        cxb, cyb = float(xs_i.mean()), float(ys_i.mean())
        if area < 1.6 * a1px and cerca_mordisco(cxb, cyb):
            continue
        if i in borde_comp and ancho_borde(borde_comp[i]) > 6.5:
            tapado = True
            continue
        pad = int(np.ceil(3 * pxmm))
        x0b = max(0, st[i, 0] - pad)
        y0b = max(0, st[i, 1] - pad)
        x1b = min(w - 1, st[i, 0] + st[i, 2] + pad)
        y1b = min(h - 1, st[i, 1] + st[i, 3] + pad)
        sub = ((lab[y0b : y1b + 1, x0b : x1b + 1] == i).astype(np.uint8) * 255)
        ratio = area / a1px
        nmin = max(1, int(np.floor(ratio * 0.6)))
        nmax = max(1, int(np.ceil(ratio))) + 1
        manchas.append(dict(sub=sub, ox=x0b, oy=y0b, ratio=ratio, nmin=nmin, nmax=nmax, ajustes={}))

    for q, mc in enumerate(manchas):
        for n in range(mc["nmin"], mc["nmax"] + 1):
            cents, cost = fit_circles(mc["sub"], n, rp, seed=7 + q * 31 + n)
            mc["ajustes"][n] = (cents, cost)

    # search combination summing to expect_n - len(bordes)
    resto_target = max(0, expect_n - len(bordes))
    best = None

    def buscar(idx, resto, coste, reparto):
        nonlocal best
        if best is not None and coste >= best[0]:
            return
        if idx == len(manchas):
            if resto == 0:
                best = (coste, reparto[:])
            return
        for n2 in range(manchas[idx]["nmin"], manchas[idx]["nmax"] + 1):
            if n2 > resto:
                continue
            reparto.append(n2)
            buscar(idx + 1, resto - n2, coste + manchas[idx]["ajustes"][n2][1], reparto)
            reparto.pop()

    buscar(0, resto_target, 0.0, [])
    if tapado:
        avisos.append("Algo tapa el borde del negro (¿un dedo?): no lo he contado.")
    if best is None:
        reparto = [
            min(mc["nmax"], max(mc["nmin"], int(round(mc["ratio"]))))
            for mc in manchas
        ]
        vistos = sum(reparto) + len(bordes)
        avisos.append(f"Veo {vistos} impactos, no {expect_n}. Corrige con +/−.")
    else:
        reparto = best[1]

    impactos = []
    for bx, by, d in bordes:
        impactos.append(dict(x=bx, y=by, d=d, puntos=puntos_de(d), margen=margen(d), dudoso=margen(d) <= DUDA))
    for q, mc in enumerate(manchas):
        n = reparto[q] if q < len(reparto) else mc["nmin"]
        cents = mc["ajustes"].get(n, ( [], 0))[0]
        for gx0, gy0 in cents:
            gx, gy = gx0 + mc["ox"], gy0 + mc["oy"]
            d = float(np.hypot(gx - cx, gy - cy) / pxmm)
            impactos.append(dict(x=gx, y=gy, d=d, puntos=puntos_de(d), margen=margen(d), dudoso=margen(d) <= DUDA))
    impactos.sort(key=lambda u: u["d"])
    dud = sum(1 for u in impactos if u["dudoso"])
    if dud:
        avisos.append(f"{dud} disparo(s) muy cerca de una línea: revísalos.")
    total = sum(u["puntos"] for u in impactos)
    cuenta = {s: sum(1 for u in impactos if u["puntos"] == s) for s in (10, 9, 8, 7, 6)}
    return dict(
        impactos=impactos,
        total=total,
        cuenta=cuenta,
        n=len(impactos),
        avisos=avisos,
        cx=cx,
        cy=cy,
        R=R,
        pxmm=pxmm,
        img=img,
        mask=mask,
    )


def draw(res, out_path: Path, title: str):
    img = cv2.cvtColor(res["img"], cv2.COLOR_BGR2RGB)
    vis = Image.fromarray(img)
    dr = ImageDraw.Draw(vis)
    cx, cy, R, pxmm = res["cx"], res["cy"], res["R"], res["pxmm"]
    dr.ellipse([cx - R, cy - R, cx + R, cy + R], outline=(0, 220, 80), width=3)
    rp = HOLE_R * pxmm
    colors = {10: (0, 170, 60), 9: (240, 150, 0), 8: (220, 40, 40), 7: (150, 0, 0), 6: (100, 0, 0)}
    for h in res["impactos"]:
        col = colors[h["puntos"]]
        x, y = h["x"], h["y"]
        for a in range(0, 360, 30):
            dr.arc([x - rp, y - rp, x + rp, y + rp], a, a + 18, fill=col, width=3)
        tag = str(h["puntos"]) + ("?" if h["dudoso"] else "")
        dr.text((x + rp + 2, y - rp - 2), tag, fill=col)
    dr.text((8, 8), f"{title}  n={res['n']} total={res['total']}", fill=(0, 220, 80))
    vis.save(out_path, quality=90)


def main():
    root = Path(r"C:\Users\Usuario\Desktop\Code\Dianne\dianne\App Tiro v2\App Tiro v2\dianas\2026-11-14")
    out = Path(r"C:\Users\Usuario\Desktop\Code\Dianne\dianne\kruger-scorer\scripts\out_pistol")
    out.mkdir(parents=True, exist_ok=True)
    results = []
    print("=== Dianne pistol detector vs GT 14-11 ===")
    ok_total = 0
    for path in sorted(root.glob("*.jpg")):
        name = path.stem
        res = analyze(str(path))
        if "error" in res:
            print(f"{name}: ERROR {res['error']}")
            results.append(dict(name=name, error=res["error"]))
            continue
        gt = GT_14.get(name)
        match = gt is not None and res["total"] == gt
        if match:
            ok_total += 1
        flag = "OK" if match else "CHECK"
        print(f"{name}: n={res['n']} total={res['total']} gt={gt} {flag} | {res['avisos']}")
        draw(res, out / f"{name}.jpg", name[-6:])
        results.append(dict(name=name, n=res["n"], total=res["total"], gt=gt, match=match, avisos=res["avisos"], cuenta=res["cuenta"]))
    print(f"\nExact total match: {ok_total}/{len(GT_14)}")
    (out / "summary.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
