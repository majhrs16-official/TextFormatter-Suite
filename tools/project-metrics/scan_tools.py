#!/usr/bin/env python3
"""
scan_tools.py - ejecuta analizadores REALES y vuelca sus resultados por fecha
en data/tools/<fecha>.json, de modo que maturity.py sustituya sus proxies.

Herramientas (ruta por variable de entorno, o autodeteccion en PATH y $PM_TOOLS):
    PM_GITLEAKS   -> fugas de secretos (escaneo de TODO el historico git)
    PM_SEMGREP    -> hallazgos de seguridad (arbol de cada snapshot)
    PM_PMD        -> violaciones Java (opcional; requiere java)
    PM_ESLINT     -> errores JS/TS (opcional; requiere node)
    PM_TOOLS      -> raiz del toolchain y de los temporales (def. /tmp/opencode)

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
# Raiz donde viven los analizadores (binarios, venv, node_modules) y temporales.
TOOLS = os.environ.get("PM_TOOLS", "/tmp/opencode")
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
        for d in (os.path.join(TOOLS, "bin"), os.path.join(TOOLS, "venv", "bin")):
            p = os.path.join(d, n)
            if os.path.exists(p):
                return p
    return None


GITLEAKS = find_tool("PM_GITLEAKS", ["gitleaks"])
SEMGREP = find_tool("PM_SEMGREP", ["semgrep"])
PMD = os.environ.get("PM_PMD") or (
    os.path.join(TOOLS, "bin", "pmd", "bin", "pmd")
    if os.path.exists(os.path.join(TOOLS, "bin", "pmd", "bin", "pmd")) else None)
ESLINT_BIN = os.environ.get("PM_ESLINT") or (
    os.path.join(TOOLS, "eslint", "node_modules", ".bin", "eslint")
    if os.path.exists(os.path.join(TOOLS, "eslint", "node_modules", ".bin", "eslint")) else None)
ESLINT_MODULES = os.environ.get("PM_ESLINT_MODULES") or os.path.join(TOOLS, "eslint", "node_modules")
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
    rp = os.path.join(TOOLS, "gitleaks.json")
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


def run_eslint(date, commit, workdir):
    if not ESLINT_BIN:
        return None
    tree = _extract(commit, workdir)
    editor = None
    for root, dirs, _files in os.walk(tree):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        if os.path.basename(root) == "web-editor" and "eslint.config.js" in _files:
            editor = root
            break
    if not editor:
        shutil.rmtree(tree, ignore_errors=True)
        return {"errors": 0, "warnings": 0, "files": 0, "by_module": {}}
    nm = os.path.join(editor, "node_modules")           # dependencias compartidas
    if not os.path.exists(nm):
        os.symlink(ESLINT_MODULES, nm)
    rp = os.path.join(workdir, "eslint.json")
    env = dict(os.environ, ESLINT_USE_FLAT_CONFIG="true")
    subprocess.run([ESLINT_BIN, ".", "-f", "json", "-o", rp],
                   cwd=editor, capture_output=True, text=True, env=env)
    res = []
    if os.path.exists(rp):
        try:
            res = json.load(open(rp))
        except Exception:
            res = []
    if os.path.islink(nm):
        os.unlink(nm)
    shutil.rmtree(tree, ignore_errors=True)
    errs = sum(f.get("errorCount", 0) for f in res)
    warns = sum(f.get("warningCount", 0) for f in res)
    bym = Counter()
    for f in res:
        p = f.get("filePath", "")
        if tree in p:
            p = os.path.relpath(p, tree)
        p = p.lstrip("/")
        if p.startswith("./"):
            p = p[2:]
        bym[module_of(p)] += f.get("errorCount", 0) + f.get("warningCount", 0) * 0.25
    return {"errors": errs, "warnings": warns,
            "files": sum(1 for f in res if f.get("errorCount") or f.get("warningCount")),
            "by_module": {k: round(v, 1) for k, v in bym.items() if v}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="all",
                    choices=["all", "gitleaks", "semgrep", "pmd", "eslint"])
    ap.add_argument("--dates", default="", help="lista separada por comas; por defecto todos")
    a = ap.parse_args()

    all_dates = snapshot_dates()
    de = day_end_cores()
    dates = [d for d in (a.dates.split(",") if a.dates else all_dates) if d]
    print(f"cortes: {len(dates)}  gitleaks={'ok' if GITLEAKS else '-'}  "
          f"semgrep={'ok' if SEMGREP else '-'}  "
          f"pmd={'ok' if (PMD and JAVA_HOME) else '-'}  "
          f"eslint={'ok' if ESLINT_BIN else '-'}")

    if a.only in ("all", "gitleaks"):
        run_gitleaks(all_dates)

    if a.only in ("all", "semgrep"):
        work = tempfile.mkdtemp(dir=TOOLS, prefix="scan-")
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
        work = tempfile.mkdtemp(dir=TOOLS, prefix="pmd-")
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

    if a.only in ("all", "eslint"):
        work = tempfile.mkdtemp(dir=TOOLS, prefix="eslint-")
        for i, d in enumerate(dates):
            commit = de.get(d)
            if not commit:
                continue
            r = run_eslint(d, commit, work)
            if r:
                merge(d, {"eslint": r})
                print(f"  [{i+1}/{len(dates)}] {d}  errores={r['errors']:<4} "
                      f"avisos={r['warnings']:<4} ficheros={r['files']}")
        shutil.rmtree(work, ignore_errors=True)

    print("OK -> data/tools/<fecha>.json")


if __name__ == "__main__":
    main()
