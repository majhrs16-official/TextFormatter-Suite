#!/usr/bin/env python3
"""Heatmap (modulo x tiempo) y radar (primer corte vs ultimo) con Pillow."""
import csv
import math
import os
from collections import defaultdict
from datetime import date as _date

from PIL import Image, ImageDraw

import chart_pil as C

import paths

DIR = paths.DATA
OUT = paths.IMG


def load_long():
    mods = defaultdict(lambda: defaultdict(dict))
    with open(os.path.join(DIR, "git_long.csv")) as f:
        for r in csv.DictReader(f):
            if r["module"] == "(total)":
                continue
            mods[r["module"]][r["metric"]][r["date"]] = float(r["value"])
    return mods


def load_wide(name):
    with open(os.path.join(DIR, name)) as f:
        rows = list(csv.DictReader(f))
    dates = [r["date"] for r in rows]
    data = {k: [float(r[k]) for r in rows] for k in rows[0] if k != "date"}
    data["test_ratio"] = [(t / s * 100 if s else 0) for t, s in zip(data["test_loc"], data["src_loc"])]
    data["comment_ratio"] = [(c / s * 100 if s else 0) for c, s in zip(data["comment_lines"], data["src_loc"])]
    return dates, data


def wk(d):
    y, m, dd = map(int, d.split("-"))
    iso = _date(y, m, dd).isocalendar()
    return (iso[0], iso[1])


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def heatmap(mods, metric, path, title, top=18, only_src=True):
    dv = {m: v[metric] for m, v in mods.items() if metric in v and any(v[metric].values())}
    if only_src:
        dv = {m: s for m, s in dv.items() if not m.startswith("tools:") and m not in ("docs", "(root)", ".github")}
    weeks = sorted({wk(d) for m in dv for d in dv[m]})
    rows = {}
    for m, s in dv.items():
        wl = wk(max(s))
        r, last = {}, 0.0
        for w in weeks:
            if w > wl:              # el modulo ya no existia en esa semana
                r[w] = 0.0
                continue
            vals = [v for d, v in s.items() if wk(d) == w]
            if vals:
                last = max(vals)
            r[w] = last
        rows[m] = r
    ranked = sorted(rows.items(), key=lambda kv: max(kv[1].values()), reverse=True)
    rows = ranked[:top]
    vmax = max((v for _, r in rows for v in r.values()), default=1)

    lw, th, bh = 210, 66, 46
    cw = max(46, (1560 - lw - 30) // max(1, len(weeks)))
    ch = 30
    W = lw + cw * len(weeks) + 30
    H = th + ch * len(rows) + bh
    img = Image.new("RGB", (W, H), C.BG)
    d = ImageDraw.Draw(img)
    d.text((16, 14), title, font=C.font(17, True), fill=C.FG)
    blue = (32, 88, 184)
    for j, w in enumerate(weeks):
        x = lw + j * cw
        d.text((x + 4, th - 18), f"S{w[1]}", font=C.font(9), fill=C.MUTED)
    for i, (m, r) in enumerate(rows):
        y = th + i * ch
        d.text((12, y + ch // 2 - 7), m[:26], font=C.font(11, True), fill=C.FG)
        for j, w in enumerate(weeks):
            v = r[w]
            t = math.log(1 + v) / math.log(1 + vmax) if vmax else 0
            col = mix((244, 246, 250), blue, t)
            x = lw + j * cw
            d.rectangle([x + 1, y + 1, x + cw - 2, y + ch - 2], fill=col, outline=(255, 255, 255))
            if t > 0.55:
                d.text((x + 5, y + ch // 2 - 6), C.fmt(v), font=C.font(9), fill=(255, 255, 255))
    # leyenda
    lx, ly = lw, H - bh + 12
    d.text((lx, ly), "0", font=C.font(9), fill=C.MUTED)
    for k in range(40):
        d.rectangle([lx + 16 + k * 6, ly, lx + 20 + k * 6, ly + 12],
                    fill=mix((244, 246, 250), blue, k / 39))
    d.text((lx + 16 + 40 * 6 + 6, ly), C.fmt(vmax), font=C.font(9), fill=C.MUTED)
    img.save(path)
    print("escrito:", path, img.size)


def bars_first_last(dates, g, path, title):
    specs = ["src_loc", "src_files", "test_loc", "test_files", "test_ratio",
             "public_api", "comment_lines"]
    data = []
    for m in specs:
        a = g[m]
        data.append((m, a[0], a[-1], max(a) or 1))
    dirty = [t + s for t, s in zip(g["todo"], g["stubs"])]
    md = max(dirty) or 1
    # Se muestra la deuda tal cual (no "max - deuda", que daria 0 en cualquier
    # corte que sea el mas sucio y un factor sin sentido). Menos es mejor.
    data.append(("deuda (todo+stubs)", dirty[0], dirty[-1], md))

    row_h, th, bh, lw = 62, 104, 54, 190
    W = 1240
    H = th + row_h * len(data) + bh
    img = Image.new("RGB", (W, H), C.BG)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((16, 14), title, font=C.font(17, True), fill=C.FG)
    d.text((16, 40), f"del {dates[0]} al {dates[-1]}   (barra = valor / maximo historico del eje; "
                     "en 'deuda', menos es mejor)", font=C.font(11), fill=C.MUTED)
    track = W - lw - 250
    for i, (m, first, last, mx) in enumerate(data):
        y = th + i * row_h
        d.text((16, y + 18), m, font=C.font(13, True), fill=C.FG)
        for k, (val, col) in enumerate(((first, C.PALETTE[1]), (last, C.PALETTE[0]))):
            yy = y + 6 + k * 24
            w = max(2, track * val / mx)
            d.rectangle([lw, yy, lw + track, yy + 17], fill=(240, 242, 246))
            d.rectangle([lw, yy, lw + w, yy + 17], fill=col)
            txt = f"{val:.0f}" if val >= 100 or val == int(val) else f"{val:.1f}"
            d.text((lw + w + 8, yy + 2), txt, font=C.font(11, True), fill=col)
        fac = (last / first) if first else float("inf")
        d.text((W - 230, y + 20), f"x{fac:.1f}" if first else "nuevo",
               font=C.font(14, True), fill=C.FG)
    ly = H - bh + 16
    for i, (col, lab) in enumerate(((C.PALETTE[1], "primer corte"), (C.PALETTE[0], "ultimo corte"))):
        x = lw + i * 240
        d.rectangle([x, ly + 3, x + 20, ly + 13], fill=col)
        d.text((x + 28, ly), lab, font=C.font(12), fill=C.FG)
    img.save(path)
    print("escrito:", path, img.size)


def main():
    mods = load_long()
    heatmap(mods, "src_loc", os.path.join(OUT, "heatmap_modulos.png"),
            "Heatmap: lineas de fuente por modulo y semana (top 18)")
    dates, g = load_wide("git_daily.csv")
    bars_first_last(dates, g, os.path.join(OUT, "crecimiento.png"),
                    "TextFormatter Suite: primer corte vs ultimo corte")


if __name__ == "__main__":
    main()
