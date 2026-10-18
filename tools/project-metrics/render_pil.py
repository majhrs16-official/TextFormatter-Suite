#!/usr/bin/env python3
"""Render PIL (detallado) de las metricas git + chat, con hitos y eras."""
import csv
import os

from PIL import Image, ImageDraw

import chart_pil as C

import paths

DIR = paths.DATA
OUT = paths.IMG
os.makedirs(OUT, exist_ok=True)

# hitos (fecha, etiqueta corta) extraidos del historico de git
MILESTONES = [
    ("2026-08-16", "inicio"),
    ("2026-08-23", "chats base"),
    ("2026-09-13", "auditoria 1"),
    ("2026-09-15", "split modular"),
    ("2026-09-24", "historial git"),
    ("2026-10-09", "aud. modulos"),
    ("2026-10-15", "aud. iFlow"),
    ("2026-10-17", "2a aud. iFlow"),
]
ERAS = [
    ("2026-08-14", "2026-09-15", (255, 246, 226)),   # era monolito src/suite
    ("2026-10-15", "2026-10-19", (234, 243, 255)),   # endurecimiento iFlow
]

LBL = {
    "src_loc": "LOC fuente (lineas)", "src_files": "archivos fuente",
    "loc_all": "LOC total (texto)", "files_all": "archivos totales",
    "public_api": "API publica (decl.)", "comment_lines": "lineas de comentario",
    "comment_ratio": "% comentarios", "test_loc": "LOC de test",
    "test_files": "archivos de test", "test_ratio": "% test / fuente",
    "todo": "TODO/FIXME", "stubs": "stubs",
    "commits": "commits / dia", "added_lines": "lineas anadidas / dia",
    "deleted_lines": "lineas borradas / dia",
    "user_prompts": "prompts del usuario", "assistant_texts": "mensajes asistente",
    "reasoning_blocks": "bloques razonamiento", "tool_calls": "llamadas a tools",
    "tool_errors": "errores de tool", "edits": "ediciones (edit/write)",
    "reads": "lecturas", "commands": "comandos shell", "searches": "busquedas",
    "git_commits": "git commit (desde chat)", "compactions": "compactaciones",
    "tokens_input": "tokens entrada", "tokens_output": "tokens salida",
    "tokens_reasoning": "tokens razonamiento", "cache_read": "cache leida",
}


def load_wide(name):
    with open(os.path.join(DIR, name)) as f:
        rows = list(csv.DictReader(f))
    dates = [r["date"] for r in rows]
    data = {k: [float(r[k]) for r in rows] for k in rows[0] if k != "date"}
    return dates, data


def ser(dates, ys, color=None):
    return {"xs": dates, "ys": ys, "color": color or C.PALETTE[0]}


def footer():
    parts = [f"{d[5:]} {lab}" for d, lab in MILESTONES]
    return ["Hitos: " + "  |  ".join(parts),
            "Era sombreada ambar = monolito src/suite (hasta 15-sep).  "
            "Era azul = endurecimiento iFlow (15-17 oct)."]


def panel_git(dates, g):
    g["test_ratio"] = [(t / s * 100 if s else 0) for t, s in zip(g["test_loc"], g["src_loc"])]
    g["comment_ratio"] = [(c / s * 100 if s else 0) for c, s in zip(g["comment_lines"], g["src_loc"])]
    order = ["src_loc", "src_files", "loc_all", "files_all", "public_api",
             "comment_lines", "comment_ratio", "test_loc", "test_files",
             "test_ratio", "todo", "stubs", "commits", "added_lines", "deleted_lines"]
    cells = []
    for m in order:
        kw = {"last_label": True}
        if m.endswith("ratio"):
            kw["ylabel"] = "%"
        cells.append((LBL[m], [ser(dates, g[m])], kw))
    p, _ = C.panel_grid(cells, os.path.join(OUT, "panel_git.png"),
                        "TextFormatter Suite - metricas del repositorio (git), corte diario",
                        cols=5, cw=470, ch=270, xrange=(dates[0], dates[-1]),
                        markers=MILESTONES, eras=ERAS, footer=footer())
    print("escrito:", p)


def panel_chat(dates, c, xrange):
    activity = ["user_prompts", "assistant_texts", "reasoning_blocks", "tool_calls",
                "tool_errors", "edits", "reads", "commands", "searches",
                "git_commits", "compactions"]
    cells = []
    for m in activity:
        col = C.PALETTE[1] if "error" in m else C.PALETTE[0]
        cells.append((LBL[m], [ser(dates, c[m], col)], {"last_label": True}))
    for m in ["tokens_input", "tokens_output", "tokens_reasoning", "cache_read"]:
        cells.append((LBL[m], [ser(dates, c[m], C.PALETTE[4])],
                      {"log": True, "last_label": True}))
    ft = ["Hitos: " + "  |  ".join(f"{d[5:]} {lab}" for d, lab in MILESTONES),
          "La capa narrativa solo existe desde 23-ago (primer chat). "
          "Antes de esa fecha no hay actividad de chat."]
    p, _ = C.panel_grid(cells, os.path.join(OUT, "panel_chat.png"),
                        "TextFormatter Suite - capa narrativa (chat), corte diario",
                        cols=5, cw=470, ch=270, xrange=xrange,
                        markers=MILESTONES, eras=ERAS, footer=ft)
    print("escrito:", p)


def panel_actividad(gd, g, cd, c, xrange):
    cells = [
        ("repo: commits / dia", [ser(gd, g["commits"], C.PALETTE[0])], {"last_label": True}),
        ("chat: llamadas a tools / dia", [ser(cd, c["tool_calls"], C.PALETTE[1])], {"last_label": True}),
        ("chat: ediciones edit/write", [ser(cd, c["edits"], C.PALETTE[2])], {"last_label": True}),
        ("repo: lineas anadidas / dia", [ser(gd, g["added_lines"], C.PALETTE[3])], {"last_label": True}),
        ("chat: errores de tool / dia", [ser(cd, c["tool_errors"], C.PALETTE[5])], {"last_label": True}),
    ]
    ft = ["Hitos: " + "  |  ".join(f"{d[5:]} {lab}" for d, lab in MILESTONES),
          "Correlacion entre el trabajo asistido (chat) y el resultado en el repositorio (git)."]
    p, _ = C.panel_grid(cells, os.path.join(OUT, "panel_actividad.png"),
                        "Actividad: repositorio (git) vs trabajo asistido (chat)",
                        cols=5, cw=470, ch=270, xrange=xrange,
                        markers=MILESTONES, eras=ERAS, footer=ft)
    print("escrito:", p)


def load_long_modules():
    mods = {}
    with open(os.path.join(DIR, "git_long.csv")) as f:
        for r in csv.DictReader(f):
            if r["module"] == "(total)":
                continue
            mods.setdefault(r["module"], {}).setdefault(r["metric"], {})[r["date"]] = float(r["value"])
    return mods


def multiline(mods, metric, top, path, title, xrange, note=None):
    cand = {m: v[metric] for m, v in mods.items() if metric in v and any(v[metric].values())}
    ranked = sorted(cand.items(), key=lambda kv: max(kv[1].values()), reverse=True)[:top]
    sers = []
    for i, (m, vv) in enumerate(ranked):
        ds = sorted(vv)
        sers.append({"label": m, "xs": ds, "ys": [vv[d] for d in ds],
                     "color": C.PALETTE[i % len(C.PALETTE)]})
    H = 860
    img = Image.new("RGB", (1560, H), C.BG)
    d = ImageDraw.Draw(img, "RGBA")
    d.text((16, 12), title, font=C.font(18, True), fill=C.FG)
    C.draw_chart(d, (12, 46, 1548, 600), LBL.get(metric, metric), sers,
                 xticks=12, markers=MILESTONES, eras=ERAS, xrange=xrange)
    lx, ly, sw = 26, 626, 500
    for i, s in enumerate(sers):
        cx = lx + (i % 3) * sw
        cy = ly + (i // 3) * 26
        d.rectangle([cx, cy + 5, cx + 22, cy + 13], fill=s["color"])
        d.text((cx + 30, cy), s["label"], font=C.font(12), fill=C.FG)
    if note:
        d.text((26, H - 34), note, font=C.font(11), fill=C.MUTED)
    d.text((26, H - 18), "Hitos: " + "  |  ".join(f"{dd[5:]} {lab}" for dd, lab in MILESTONES),
           font=C.font(11), fill=C.MUTED)
    img.save(path)
    print("escrito:", path, img.size)


def main():
    gd, g = load_wide("git_daily.csv")
    cd, c = load_wide("chat_daily.csv")
    xr = (gd[0], gd[-1])
    panel_git(gd, g)
    panel_chat(cd, c, xr)
    panel_actividad(gd, g, cd, c, xr)
    mods = load_long_modules()
    multiline(mods, "src_loc", 15, os.path.join(OUT, "modules_src_loc.png"),
              "Lineas de codigo fuente por modulo (top 15)", xr,
              "El acantilado de 'suite' (hasta 15-sep) es la reorganizacion de monolito a modulos.")
    multiline(mods, "test_loc", 12, os.path.join(OUT, "modules_test_loc.png"),
              "Lineas de test por modulo (top 12)", xr)


if __name__ == "__main__":
    main()
