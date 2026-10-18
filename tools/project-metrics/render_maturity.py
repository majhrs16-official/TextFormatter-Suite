#!/usr/bin/env python3
"""Render (Pillow) de la rubrica de madurez: matriz modulo x familia y evolucion."""
import csv
import os
from collections import defaultdict

from PIL import Image, ImageDraw

import chart_pil as C
import paths
from render_pil import MILESTONES, ERAS

DIR = paths.DATA
OUT = paths.IMG
os.makedirs(OUT, exist_ok=True)

FAMILIES = [
    "calidad_codigo", "cobertura_tests", "documentacion", "seguridad",
    "secretos", "dependencias", "concurrencia", "estabilidad",
    "rendimiento", "arquitectura", "implementacion_real", "integracion",
]
LBL = {
    "calidad_codigo": "1. Calidad de codigo",
    "cobertura_tests": "2. Cobertura de tests",
    "documentacion": "3. Documentacion",
    "seguridad": "4. Seguridad",
    "secretos": "5. Secretos",
    "dependencias": "6. Dependencias",
    "concurrencia": "7. Concurrencia",
    "estabilidad": "8. Estabilidad",
    "rendimiento": "9. Rendimiento",
    "arquitectura": "10. Arquitectura",
    "implementacion_real": "11. Implementacion real",
    "integracion": "12. Integracion",
}


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def score_color(v):
    """0 -> rojo, 50 -> ambar, 100 -> verde."""
    if v < 52:
        return mix((198, 40, 40), (244, 160, 0), v / 52)
    return mix((244, 160, 0), (15, 157, 88), (v - 52) / 48)


def load_total_series():
    """{familia: {fecha: score}} para el ambito (total)."""
    fam = defaultdict(dict)
    with open(os.path.join(DIR, "maturity_long.csv")) as f:
        for r in csv.DictReader(f):
            if r["module"] == "(total)":
                fam[r["family"]][r["date"]] = float(r["score"])
    return fam


def calendar_dates():
    with open(os.path.join(DIR, "git_daily.csv")) as f:
        return [r["date"] for r in csv.DictReader(f)]


def fill(dates, series):
    """Arrastra el ultimo valor conocido (la madurez es un estado)."""
    out, last = [], None
    for d in dates:
        if d in series:
            last = series[d]
        out.append(last if last is not None else 0.0)
    return out


def sources_used():
    """{familia: {fuentes}} para el ambito (total), excluyendo 'determinista'."""
    fam = defaultdict(set)
    with open(os.path.join(DIR, "maturity_long.csv")) as f:
        for r in csv.DictReader(f):
            if r["module"] == "(total)" and r["source"] not in ("determinista", "asumido", "estructura"):
                fam[r["family"]].add(r["source"])
    return fam


def panel_temporal(dates, fam):
    cells = []
    for i, f in enumerate(FAMILIES):
        ys = fill(dates, fam.get(f, {}))
        col = C.PALETTE[i % len(C.PALETTE)]
        cells.append((LBL[f], [{"xs": dates, "ys": ys, "color": col}],
                      {"ymin": 0, "ymax": 100, "last_label": True}))
    used = sources_used()
    real = ", ".join(f"{k}={','.join(sorted(v))}" for k, v in sorted(used.items()))
    ft = ["Hitos: " + "  |  ".join(f"{d[5:]} {lab}" for d, lab in MILESTONES),
          "Herramientas reales: " + (real if real else
                                     "(ninguna; puntuacion determinista)") + ".",
          "Escala fija 0-100 en todos los paneles. Dias sin commit = ultimo valor conocido (estado)."]
    p, _ = C.panel_grid(cells, os.path.join(OUT, "rubrica_familias.png"),
                        "TextFormatter Suite - rubrica de madurez (12 familias), corte diario",
                        cols=4, cw=470, ch=270, xrange=(dates[0], dates[-1]),
                        markers=MILESTONES, eras=ERAS, footer=ft)
    print("escrito:", p)


def matrix():
    with open(os.path.join(DIR, "maturity_final.csv")) as f:
        rows = list(csv.DictReader(f))
    sc = defaultdict(dict)
    for r in rows:
        sc[r["module"]][r["family"]] = float(r["score"])
    # tamano por LOC de fuente (ultimo corte)
    size = {}
    with open(os.path.join(DIR, "git_long.csv")) as f:
        for r in csv.DictReader(f):
            if r["metric"] == "src_loc":
                size[r["module"]] = max(size.get(r["module"], 0), int(r["value"]))
    mods = [m for m in sc if m != "(total)" and size.get(m, 0) > 0]
    mods.sort(key=lambda m: -size.get(m, 0))
    mods = [m for m in mods if not m.startswith("tools:")][:20]

    lw, th, bh, rh = 210, 118, 74, 30
    cw = 92
    W = lw + cw * len(FAMILIES) + 30
    H = th + rh * len(mods) + bh
    img = Image.new("RGB", (W, H), C.BG)
    d = ImageDraw.Draw(img)
    d.text((16, 12), "TextFormatter Suite - rubrica de madurez por modulo (ultimo corte)",
           font=C.font(18, True), fill=C.FG)
    d.text((16, 44), "Puntuacion 0-100 por familia; filas ordenadas por LOC de fuente. "
                     "Verde = maduro, ambar = medio, rojo = debil.",
           font=C.font(11), fill=C.MUTED)
    # cabeceras de columna (texto envuelto en 2 lineas, sin rotar)
    for j, f in enumerate(FAMILIES):
        x = lw + j * cw
        num, name = LBL[f].split(". ", 1)
        lines, cur = [], ""
        for w in name.split():
            if cur and len(cur) + len(w) + 1 > 12:
                lines.append(cur)
                cur = w
            else:
                cur = (cur + " " + w).strip()
        if cur:
            lines.append(cur)
        d.text((x + cw / 2, th - 54), num, font=C.font(11, True), fill=C.MUTED, anchor="mm")
        for k, ln in enumerate(lines[:2]):
            d.text((x + cw / 2, th - 36 + k * 14), ln, font=C.font(9, True), fill=C.FG, anchor="mm")

    for i, m in enumerate(mods):
        y = th + i * rh
        d.text((12, y + rh // 2 - 7), m[:26], font=C.font(11, True), fill=C.FG)
        for j, f in enumerate(FAMILIES):
            v = sc[m].get(f, 0.0)
            x = lw + j * cw
            col = score_color(v)
            d.rectangle([x + 1, y + 1, x + cw - 2, y + rh - 2], fill=col, outline=(255, 255, 255))
            txt = f"{v:.0f}"
            tw = d.textlength(txt, font=C.font(10, True))
            lum = 0.299 * col[0] + 0.587 * col[1] + 0.114 * col[2]
            d.text((x + cw / 2 - tw / 2, y + rh / 2 - 7), txt, font=C.font(10, True),
                   fill=(255, 255, 255) if lum < 150 else (30, 30, 30))
    # leyenda
    lx, ly = lw, H - bh + 20
    for k in range(48):
        d.rectangle([lx + k * 6, ly, lx + 5 + k * 6, ly + 13], fill=score_color(k / 47 * 100))
    d.text((lx, ly - 18), "0", font=C.font(9), fill=C.MUTED)
    d.text((lx + 48 * 6 + 8, ly - 18), "100 (madurez)", font=C.font(9), fill=C.MUTED)
    d.text((lx, ly + 24), "Familias 5 (secretos) y 6 (dependencias) son de nivel proyecto: "
                          "mismo valor en todos los modulos.", font=C.font(10), fill=C.MUTED)
    p = os.path.join(OUT, "rubrica_matriz.png")
    img.save(p)
    print("escrito:", p, img.size)


def main():
    dates = calendar_dates()
    fam = load_total_series()
    panel_temporal(dates, fam)
    matrix()


if __name__ == "__main__":
    main()
