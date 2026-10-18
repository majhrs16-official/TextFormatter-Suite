#!/usr/bin/env python3
"""
chart_pil.py - mini libreria de graficas estaticas con Pillow.

PIL gana en nivel de detalle (decision del usuario). Presta:
  - rejilla, etiquetas de valor, ticks de fecha densos
  - varias series con leyenda
  - hitos (lineas verticales), eras (sombreado), rango X forzado
  - pie de leyenda
"""
import math
import os
from datetime import date as _date

from PIL import Image, ImageDraw, ImageFont

BG = (255, 255, 255)
PANEL = (249, 250, 252)
BORDER = (214, 219, 226)
FG = (26, 30, 36)
MUTED = (122, 130, 142)
GRID = (229, 233, 239)
AXIS = (150, 158, 168)
MARK = (168, 120, 190)
ERA = (255, 246, 226)
PALETTE = [
    (47, 107, 214), (217, 67, 47), (15, 157, 88), (244, 160, 0),
    (123, 67, 219), (0, 150, 170), (198, 40, 120), (110, 110, 110),
    (30, 120, 60), (170, 90, 0), (60, 60, 160), (200, 60, 60),
    (20, 140, 140), (140, 20, 140), (90, 140, 20), (160, 100, 40),
]

_FONTS = {
    False: ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf"],
    True: ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
           "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf"],
}
_cache = {}


def font(size, bold=False):
    key = (size, bold)
    if key not in _cache:
        f = None
        for p in _FONTS[bold]:
            if os.path.exists(p):
                f = ImageFont.truetype(p, size)
                break
        _cache[key] = f or ImageFont.load_default()
    return _cache[key]


def fmt(v):
    if v >= 1e6:
        return f"{v/1e6:.1f}M"
    if v >= 1e3:
        return f"{v/1e3:.0f}k"
    if v >= 10 or v == int(v):
        return f"{v:.0f}"
    return f"{v:.1f}"


def nice_max(v):
    if v <= 0:
        return 1
    e = math.floor(math.log10(v))
    b = 10 ** e
    for m in (1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if v <= m * b:
            return m * b
    return 10 * b


def ord_of(x):
    if isinstance(x, _date):
        return x.toordinal()
    if hasattr(x, "date"):
        return x.date().toordinal()
    if isinstance(x, str):
        return _date.fromisoformat(x).toordinal()
    return int(x)


def vdash(d, x, y0, y1, color, dash=4, gap=4):
    y = y0
    while y < y1:
        d.line([x, y, x, min(y + dash, y1)], fill=color, width=1)
        y += dash + gap


def draw_chart(d, box, title, series, *, fill=False, ymin=0, ymax=None,
               showxticks=True, xticks=8, yticks=4, log=False, ylabel=None,
               markers=None, eras=None, xrange=None, last_label=False):
    x0, y0, x1, y1 = box
    pl = 60 if ylabel else 52
    pt = 26
    pb = 24 if showxticks else 10
    ax0, ay0, ax1, ay1 = x0 + pl, y0 + pt, x1 - 12, y1 - pb

    xs_all = [ord_of(x) for s in series for x in s["xs"]]
    ys_all = [y for s in series for y in s["ys"]]
    d.rectangle([x0, y0, x1, y1], fill=PANEL, outline=BORDER)
    if not xs_all or not ys_all:
        d.text((x0 + 8, y0 + 5), title + " (sin datos)", font=font(12, True), fill=MUTED)
        return None

    if xrange:
        xd0, xd1 = ord_of(xrange[0]), ord_of(xrange[1])
    else:
        xd0, xd1 = min(xs_all), max(xs_all)
    spanx = (xd1 - xd0) or 1
    vmax = ymax if ymax is not None else nice_max(max(ys_all))
    vmin = ymin if not log else 1

    def TX(od):
        return ax0 + (od - xd0) / spanx * (ax1 - ax0)

    def TY(v):
        if log:
            hi = max(vmax, 10)
            v = max(v, 1)
            return ay1 - (math.log10(v) - math.log10(1)) / (math.log10(hi) - math.log10(1)) * (ay1 - ay0)
        return ay1 - (v - vmin) / ((vmax - vmin) or 1) * (ay1 - ay0)

    # eras (sombreado de fondo)
    for (e0, e1, col) in (eras or []):
        a, b = TX(ord_of(e0)), TX(ord_of(e1))
        d.rectangle([a, ay0, b, ay1], fill=col)

    d.text((x0 + 8, y0 + 5), title, font=font(12, True), fill=FG)

    # rejilla y ticks Y
    for i in range(yticks + 1):
        if log:
            hi = math.log10(max(vmax, 10))
            v = 10 ** (hi * i / yticks)
        else:
            v = vmin + (vmax - vmin) * i / yticks
        yy = TY(v)
        d.line([ax0, yy, ax1, yy], fill=GRID)
        d.text((x0 + 4, yy - 6), fmt(v), font=font(9), fill=MUTED)

    # rejilla y ticks X
    if showxticks:
        for i in range(xticks + 1):
            od = xd0 + spanx * i / xticks
            xx = TX(od)
            d.line([xx, ay0, xx, ay1], fill=GRID)
            lab = _date.fromordinal(int(round(od))).strftime("%d-%b")
            tw = d.textlength(lab, font=font(8))
            d.text((xx - tw / 2, ay1 + 4), lab, font=font(8), fill=MUTED)

    # hitos
    for (mk, _lab) in (markers or []):
        xx = TX(ord_of(mk))
        if ax0 <= xx <= ax1:
            vdash(d, xx, ay0, ay1, MARK)

    # ejes
    d.line([ax0, ay0, ax0, ay1], fill=AXIS)
    d.line([ax0, ay1, ax1, ay1], fill=AXIS)

    # series
    for s in series:
        pts = [(TX(ord_of(x)), TY(v)) for x, v in zip(s["xs"], s["ys"])]
        col = s["color"]
        if fill and len(pts) > 1:
            d.polygon(pts + [(pts[-1][0], ay1), (pts[0][0], ay1)], fill=col + (26,))
        if len(pts) > 1:
            d.line(pts, fill=col, width=2)
        if pts:
            ex, ey = pts[-1]
            d.ellipse([ex - 3, ey - 3, ex + 3, ey + 3], fill=col, outline=BG)
            if last_label:
                t = fmt(s["ys"][-1])
                tw = d.textlength(t, font=font(9, True))
                d.text((ex - tw - 6, ey - 14 if ey > ay0 + 16 else ey + 4),
                       t, font=font(9, True), fill=col)
    if ylabel:
        d.text((x0 + 4, ay0 - 16), ylabel, font=font(9), fill=MUTED)
    return (ax0, ay0, ax1, ay1)


def panel_grid(cells, path, title, *, cols=4, cw=470, ch=270, header=44,
               footer=None, xrange=None, markers=None, eras=None,
               marker_names=None):
    """cells: (titulo, series, kwargs). footer: lista de lineas de leyenda."""
    rows = math.ceil(len(cells) / cols)
    fh = 26 * len(footer) if footer else 0
    W, H = cols * cw, rows * ch + header + fh
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((14, 12), title, font=font(18, True), fill=FG)
    for i, (t, series, kw) in enumerate(cells):
        c, r = i % cols, i // cols
        box = (c * cw + 6, header + r * ch + 6, c * cw + cw - 6, header + r * ch + ch - 6)
        k = dict(kw or {})
        k.setdefault("xrange", xrange)
        k.setdefault("markers", markers)
        k.setdefault("eras", eras)
        draw_chart(d, box, t, series, **k)
    if footer:
        y = header + rows * ch + 6
        for line in footer:
            d.text((16, y), line, font=font(11), fill=MUTED)
            y += 20
    img.save(path)
    return path, img.size
