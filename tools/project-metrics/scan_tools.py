#!/usr/bin/env python3
"""
scan_tools.py - ejecuta analizadores REALES y vuelca sus resultados por fecha
en data/tools/<fecha>.json, de modo que maturity.py sustituya sus proxies.

Herramientas (ruta por variable de entorno, o autodeteccion en PATH y /tmp/opencode):
    PM_GITLEAKS   -> fugas de secretos (escaneo de TODO el historico git)
    PM_SEMGREP    -> hallazgos de seguridad (arbol de cada snapshot)
    PM_PMD        -> violaciones Java (opcional; requiere java)
    PM_ESLINT     -> errores JS/TS (opcional; requiere node)

Estrategia:
  - gitleaks : una sola pasada sobre la historia; cada fuga se asigna a la fecha
               de su commit. Rellena TODAS las fechas de corte.
  - semgrep  : extrae el arbol de cada snapshot y lo escanea; cuenta hallazgos
               totales, por severidad (peso) y por modulo.

Uso:
    scan_tools.py                       # gitleaks + semgrep en todos los cortes
    scan_tools.py --only gitleaks
    scan_tools.py --dates 2026-09-16,2026-10-18
"""
import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
from collections import Counter

import paths

REPO = os.environ.get("PM_REPO") or paths.REPO
DATA = paths.DATA
TOOLS_DIR = os.path.join(DATA, "tools")
WEIGHT = {"ERROR": 4.0, "WARNING": 1.0, "INFO": 0.5}
PMD_WEIGHT = {1: 3.0, 2: 2.0, 3: 1.0, 4: 0.5, 5: 0.5}


def find_tool(env, names):
    v = os.environ.get(env)
    if v and os.path.exists(v):
        return v
    for n in names:
        p = shutil.which(n)
        if p:
            return p
    for n in names:
        for d in ("/tmp/opencode/bin", "/tmp/opencode/venv/bin"):
            p = os.path.join(d, n)
            if os.path.exists(p):
                return p
    return None


GITLEAKS = find_tool("PM_GITLEAKS", ["gitleaks"])
SEMGREP = find_tool("PM_SEMGREP", ["semgrep"])
PMD = os.environ.get("PM_PMD") or (
    "/tmp/opencode/bin/pmd/bin/pmd"
    if os.path.exists("/tmp/opencode/bin/pmd/bin/pmd") else None)
JAVA_HOME = os.environ.get("PM_JAVA_HOME") or os.environ.get("JAVA_HOME") \
    or ("/opt/javac/x64/21" if os.path.exists("/opt/javac/x64/21/bin/java") else None)

EXCLUDE = ["--exclude", "node_modules", "--exclude", "dist", "--exclude", "build",
           "--exclude", "out", "--exclude", "target", "--exclude", ".gradle",
           "--exclude", "vendor", "--exclude", "coverage"]


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args],
                          capture_output=True, text=True).stdout


def module_of(p):
    parts = p.split("/")
    if parts[0] == "src" and len(parts) >= 2:
        return parts[1]
    if parts[0] == "tools" and len(parts) >= 2:
        return "tools:" + parts[1]
    if len(parts) == 1:
        return "(root)"
    return parts[0]


def snapshot_dates():
    with open(os.path.join(DATA, "git_daily.csv")) as f:
        return [r["date"] for r in csv.DictReader(f)]


def day_end_cores():
    out = {}
    for ln in git("log", "--reverse", "--date=short", "--format=%H|%ad").strip().splitlines():
        if "|" in ln:
            h, d = ln.split("|", 1)
            out[d] = h
    return out


def merge(date, obj):
    os.makedirs(TOOLS_DIR, exist_ok=True)
    p = os.path.join(TOOLS_DIR, f"{date}.json")
    cur = {}
    if os.path.exists(p):
        with open(p) as f:
            cur = json.load(f)
    cur.update(obj)
    with open(p, "w") as f:
        json.dump(cur, f, ensure_ascii=False, indent=0)


def run_gitleaks(dates):
    if not GITLEAKS:
        print("  gitleaks: no disponible, se omite")
        return
    rp = "/tmp/opencode/gitleaks.json"
    subprocess.run([GITLEAKS, "detect", "--source", REPO, "-f", "json",
                    "-r", rp, "--no-banner", "--redact"],
                   capture_output=True, text=True)
    leaks = []
    if os.path.exists(rp):
        try:
            leaks = json.load(open(rp))
        except Exception:
            leaks = []
    cd = {}
    for ln in git("log", "--date=short", "--format=%H|%ad").strip().splitlines():
        if "|" in ln:
            h, d = ln.split("|", 1)
            cd[h] = d
    by = Counter()
    for x in leaks:
        h = x.get("Commit") or ""
        by[cd.get(h, (x.get("Date") or "")[:10])] += 1
    for d in dates:
        merge(d, {"gitleaks": {"leaks": by.get(d, 0)}})
    print(f"  gitleaks: {len(leaks)} fugas -> {len(dates)} fechas")


def _extract(commit, workdir):
    tree = tempfile.mkdtemp(dir=workdir)
    subprocess.run(f"git -C {REPO} archive {commit} | tar -x -C {tree}",
                   shell=True, capture_output=True)
    return tree


def run_semgrep(date, commit, workdir):
    if not SEMGREP:
        return None
    tree = _extract(commit, workdir)
    rp = os.path.join(workdir, "semgrep.json")
    subprocess.run([SEMGREP, "scan", "--config", "p/default", "--json",
                    "--output", rp, "--metrics=off", "--quiet", *EXCLUDE, tree],
                   capture_output=True, text=True)
    res = []
    if os.path.exists(rp):
        try:
            res = json.load(open(rp)).get("results", [])
        except Exception:
            res = []
    shutil.rmtree(tree, ignore_errors=True)
    sev = Counter(r["extra"]["severity"] for r in res)
    bym = Counter()
    for r in res:
        p = r.get("path", "")
        if tree in p:
            p = os.path.relpath(p, tree)
        p = p.lstrip("/")
        if p.startswith("./"):
            p = p[2:]
        bym[module_of(p)] += WEIGHT.get(r["extra"]["severity"], 1.0)
    return {"findings": len(res), "by_severity": dict(sev),
            "weight": round(sum(WEIGHT.get(r["extra"]["severity"], 1.0) for r in res), 1),
            "by_module": {k: round(v, 1) for k, v in bym.items()}}


def run_pmd(date, commit, workdir):
    if not PMD or not JAVA_HOME:
        return None
    tree = _extract(commit, workdir)
    rp = os.path.join(workdir, "pmd.json")
    env = dict(os.environ, JAVA_HOME=JAVA_HOME,
               PATH=os.path.join(JAVA_HOME, "bin") + os.pathsep + os.environ.get("PATH", ""))
    # todo el arbol: antes del split (24-sep) el Java vive en la raiz, no en src/
    subprocess.run([PMD, "check", "-d", tree, "-R", "rulesets/java/quickstart.xml",
                    "-f", "json", "-r", rp, "--no-progress"],
                   capture_output=True, text=True, env=env)
    # modulos con Java (aunque no tengan violaciones)
    java_mods = {module_of(p) for p in git("ls-tree", "-r", "--name-only", commit)
                 .splitlines() if p.endswith(".java")}
    found = []
    if os.path.exists(rp):
        try:
            for f in json.load(open(rp)).get("files", []):
                found += [(f["filename"], v) for v in f.get("violations", [])]
        except Exception:
            found = []
    shutil.rmtree(tree, ignore_errors=True)
    bym = Counter()
    for p, v in found:
        if tree in p:
            p = os.path.relpath(p, tree)
        p = p.lstrip("/")
        if p.startswith("./"):
            p = p[2:]
        bym[module_of(p)] += PMD_WEIGHT.get(int(v.get("priority", 3)), 1.0)
    return {"violations": len(found), "java_modules": len(java_mods),
            "by_module": {m: round(bym.get(m, 0.0), 1) for m in sorted(java_mods)}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="all",
                    choices=["all", "gitleaks", "semgrep", "pmd"])
    ap.add_argument("--dates", default="", help="lista separada por comas; por defecto todos")
    a = ap.parse_args()

    all_dates = snapshot_dates()
    de = day_end_cores()
    dates = [d for d in (a.dates.split(",") if a.dates else all_dates) if d]
    print(f"cortes: {len(dates)}  gitleaks={'ok' if GITLEAKS else '-'}  "
          f"semgrep={'ok' if SEMGREP else '-'}  "
          f"pmd={'ok' if (PMD and JAVA_HOME) else '-'}")

    if a.only in ("all", "gitleaks"):
        run_gitleaks(all_dates)

    if a.only in ("all", "semgrep"):
        work = tempfile.mkdtemp(dir="/tmp/opencode", prefix="scan-")
        for i, d in enumerate(dates):
            commit = de.get(d)
            if not commit:
                continue
            r = run_semgrep(d, commit, work)
            if r:
                merge(d, {"semgrep": r})
                print(f"  [{i+1}/{len(dates)}] {d}  hallazgos={r['findings']:<4} "
                      f"peso={r['weight']:<6} modulos={len(r['by_module'])}")
        shutil.rmtree(work, ignore_errors=True)

    if a.only in ("all", "pmd"):
        work = tempfile.mkdtemp(dir="/tmp/opencode", prefix="pmd-")
        for i, d in enumerate(dates):
            commit = de.get(d)
            if not commit:
                continue
            r = run_pmd(d, commit, work)
            if r:
                merge(d, {"pmd": r})
                print(f"  [{i+1}/{len(dates)}] {d}  violaciones={r['violations']:<5} "
                      f"modulos={len(r['by_module'])}")
        shutil.rmtree(work, ignore_errors=True)

    print("OK -> data/tools/<fecha>.json")


if __name__ == "__main__":
    main()
