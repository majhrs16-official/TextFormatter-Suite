#!/usr/bin/env python3
"""
judge.py - soporte del juez para las familias cualitativas de la rubrica
           (3 documentacion, 11 implementacion real).

El juicio NO lo hace este programa: lo hace el agente de OpenCode a traves del
skill `.opencode/skills/juzgar-rubrica`. Aqui viven solo las dos mitades que son
deterministas y por tanto reproducibles:

  - la evidencia: un "evidence pack" corto (~10 lineas) por corte y familia, a
    partir de git_long.csv y del historico de commits (.md tocados, asuntos de
    commit). Nunca se usa el chat.log.
  - el veredicto materializado: el CSV commiteado (judge_scores.csv) se vuelca a
    data/judge/<fecha>.json, que es lo que lee maturity.py. `--export-scores`
    hace el camino inverso.

Como el veredicto se produce una sola vez y queda commiteado, el pipeline es
determinista: las imagenes se reproducen sin depender de ningun servicio.

Uso:
    judge.py                                     # genera data/evidence/ (2 por corte)
    judge.py --import-scores judge_scores.csv    # CSV de veredictos -> data/judge/
    judge.py --export-scores judge_scores.csv    # data/judge/ -> CSV (inverso)
"""
import argparse
import csv
import glob
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

import paths

REPO = os.environ.get("PM_REPO") or paths.REPO
DATA = paths.DATA
EVID = os.path.join(DATA, "evidence")
JDIR = os.path.join(DATA, "judge")

# Familias cualitativas que puntua el juez; el resto las cubre el proxy
# determinista. Los IDs deben coincidir con maturity.py y con judge_scores.csv.
FAMILIES = ("documentacion", "implementacion_real")


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args],
                          capture_output=True, text=True).stdout


def load_daily():
    with open(os.path.join(DATA, "git_daily.csv")) as f:
        return {r["date"]: r for r in csv.DictReader(f)}


def commits_by_date():
    """{fecha: [(hash, asunto)], fecha: [ficheros .md tocados]}"""
    out = git("log", "--date=short", "--format=@@%H|%ad|%s", "--name-only")
    cbd, md = defaultdict(list), defaultdict(set)
    cur = None
    for ln in out.splitlines():
        if ln.startswith("@@"):
            h, d, s = ln[2:].split("|", 2)
            cbd[d].append((h[:8], s))
            cur = d
        elif ln.strip() and cur:
            if ln.endswith(".md"):
                md[cur].add(ln)
    return cbd, md


def inline(s, n=180):
    return re.sub(r"\s+", " ", s.strip())[:n]


def pack_documentacion(date, d, mods, cbd, md, tools):
    lines = [f"doc_loc={d['doc_loc']} doc_files={d['doc_files']} "
             f"src_loc={d['src_loc']} doc/src={float(d['doc_loc'])/max(float(d['src_loc']),1):.2f}",
             f"comment_lines={d['comment_lines']} "
             f"comment/src={float(d['comment_lines'])/max(float(d['src_loc']),1):.2f}"]
    reales = [m for m in mods if m != "(total)"]
    sin_doc = [m for m in reales if mods[m].get("doc_loc", {}).get(date, 0) == 0]
    if reales:
        lines.append(f"modulos con doc=0: {len(sin_doc)} de {len(reales)}")
    mds = sorted(md.get(date, []))
    lines.append("ficheros .md tocados hoy (%d): %s" %
                 (len(mds), ", ".join(mds[:8]) or "ninguno"))
    top = sorted(((m, v["doc_loc"][date]) for m, v in mods.items()
                  if date in v.get("doc_loc", {})), key=lambda kv: -kv[1])[:6]
    if top:
        lines.append("doc por modulo (top): " + ", ".join(f"{m}={v}" for m, v in top))
    cs = cbd.get(date, [])
    lines.append("commits de hoy: " + " | ".join(inline(s, 60) for _, s in cs[:6]))
    return "\n".join(lines)


def pack_implementacion(date, d, mods, cbd, md, tools):
    lines = [f"src_loc={d['src_loc']} src_files={d['src_files']} "
             f"public_api={d['public_api']}",
             f"stubs={d['stubs']}  todo/fixme={d['todo']}  test_annotations={d['test_annotations']}",
             f"interfaces={d['interfaces']} ports={d['ports']} "
             f"error_handling={d['error_handling']} bare_catch={d['bare_catch']}"]
    sg, pm = tools.get("semgrep") or {}, tools.get("pmd") or {}
    if sg or pm:
        lines.append(f"analisis estatico: semgrep={sg.get('findings', '?')} hallazgos "
                     f"(ERROR={sg.get('by_severity', {}).get('ERROR', 0)}), "
                     f"pmd={pm.get('violations', '?')} violaciones en "
                     f"{pm.get('java_modules', '?')} modulos Java")
    top = sorted(((m, v["stubs"][date], v["src_loc"][date]) for m, v in mods.items()
                  if date in v.get("stubs", {})), key=lambda kv: -kv[1])[:5]
    if top:
        lines.append("stubs por modulo (top): " +
                     ", ".join(f"{m}: {s} stubs / {l} LOC" for m, s, l in top))
    lines.append(f"loc_all={d['loc_all']} files_all={d['files_all']} "
                 f"(proporcion de codigo frente a otros ficheros)")
    cs = cbd.get(date, [])
    lines.append("commits de hoy: " + " | ".join(inline(s, 60) for _, s in cs[:6]))
    return "\n".join(lines)


def build_pack(date, fam, d, mods, cbd, md, tools):
    ev = (pack_documentacion if fam == "documentacion" else pack_implementacion)(
        date, d, mods, cbd, md, tools)
    return f"CORTE {date} - proyecto TextFormatter Suite\n\nEVIDENCIA:\n{ev}\n"


def do_import(path):
    """Materializa los veredictos de un CSV en data/judge/<fecha>.json.

    Formato: date,family,score,reason,model. Es el puente entre el juicio (que
    produce el agente y se commitea) y el pipeline de imagenes, que solo lee.
    """
    by_date = defaultdict(dict)
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            d = (row.get("date") or "").strip()
            fam = (row.get("family") or "").strip()
            if not d or fam not in FAMILIES:
                continue
            by_date[d][fam] = {
                "score": max(0, min(100, int(float(row["score"])))),
                "reason": (row.get("reason") or "").strip()[:240],
                "juez": (row.get("juez") or row.get("model")
                         or "opencode-agent").strip(),
            }
    os.makedirs(JDIR, exist_ok=True)
    for d, fams in by_date.items():
        p = os.path.join(JDIR, f"{d}.json")
        cur = json.load(open(p)) if os.path.exists(p) else {}
        cur.setdefault("families", {}).update(fams)
        with open(p, "w") as fh:
            json.dump(cur, fh, ensure_ascii=False, indent=1)
    print(f"importados {len(by_date)} cortes "
          f"({sum(len(v) for v in by_date.values())} veredictos) -> {JDIR}")


def do_export(path):
    """Vuelca los veredictos de data/judge/ al formato de judge_scores.csv.

    Camino inverso de --import-scores: permite guardar como artefacto commiteado
    una matriz de veredictos ya materializada.
    """
    rows = []
    for p in sorted(glob.glob(os.path.join(JDIR, "*.json"))):
        date = os.path.basename(p)[:-5]
        with open(p) as f:
            cur = json.load(f)
        for fam, v in sorted(cur.get("families", {}).items()):
            if "score" not in v:
                continue
            rows.append({"date": date, "family": fam, "score": int(v["score"]),
                         "reason": str(v.get("reason", "")).replace(",", ";"),
                         "juez": v.get("juez", v.get("model", ""))})
    if not rows:
        sys.exit(f"no hay veredictos en {JDIR}")
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["date", "family", "score", "reason", "juez"],
                           lineterminator="\n")
        w.writeheader()
        w.writerows(rows)
    print(f"exportados {len(rows)} veredictos -> {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--import-scores", dest="scores", default="",
                    help="CSV date,family,score,reason,juez con veredictos ya calculados")
    ap.add_argument("--export-scores", dest="export", default="",
                    help="vuelca data/judge/ a un CSV con ese formato")
    a = ap.parse_args()

    if a.scores:
        do_import(a.scores)
        return
    if a.export:
        do_export(a.export)
        return

    os.makedirs(EVID, exist_ok=True)

    daily = load_daily()
    cbd, md = commits_by_date()
    mods = defaultdict(lambda: defaultdict(dict))
    git_dates = set()
    with open(os.path.join(DATA, "git_long.csv")) as f:
        for r in csv.DictReader(f):
            git_dates.add(r["date"])
            if r["module"] != "(total)":
                mods[r["module"]][r["metric"]][r["date"]] = float(r["value"])

    tools_by_date = {}
    for dt in sorted(git_dates):
        p = os.path.join(DATA, "tools", f"{dt}.json")
        if os.path.exists(p):
            with open(p) as f:
                tools_by_date[dt] = json.load(f)

    dates = [x for x in sorted(git_dates) if x in daily]
    n = 0
    for date in dates:
        d = daily[date]
        for fam in FAMILIES:
            pack = build_pack(date, fam, d, mods, cbd, md,
                              tools_by_date.get(date, {}))
            with open(os.path.join(EVID, f"{date}.{fam}.md"), "w") as fh:
                fh.write(pack)
            n += 1
    print(f"evidencia: {n} paquetes de {len(dates)} cortes -> {EVID}")


if __name__ == "__main__":
    main()
