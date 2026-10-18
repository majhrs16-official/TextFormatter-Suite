#!/usr/bin/env python3
"""
Etapa A (v3): metricas deterministas del repositorio por snapshot diario y modulo.

Filtra generados/vendored (node_modules, dist, build, .bak, lockfiles, min, maps).
Distingue LOC de fuente vs LOC de todo el texto. Anade "senales" que alimentan
la rubrica de madurez (concurrencia, manejo de errores, puertos/adaptadores,
timeouts, tests, documentacion).

Salida:
  - git_long.csv   : date,module,metric,value
  - git_daily.csv  : date + metricas del proyecto entero (dias rellenados)
"""
import subprocess
import sys
import os
import csv
from collections import defaultdict
from datetime import date as _date, timedelta

import paths

REPO = sys.argv[1] if len(sys.argv) > 1 else paths.REPO
OUTDIR = sys.argv[2] if len(sys.argv) > 2 else paths.DATA

SOURCE_EXT = (".java", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".py",
              ".gradle", ".groovy", ".rs", ".go", ".c", ".h", ".cpp", ".cs",
              ".rb", ".php", ".sh", ".sql", ".vue", ".svelte")
GENERATED_DIRS = ("/node_modules/", "/dist/", "/build/", "/out/", "/target/",
                  "/vendor/", "/.gradle/", "/coverage/", "/_site/", "/.next/")
GENERATED_SUBSTR = (".min.js", ".min.css", ".map", ".bak", ".lock",
                    "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
                    "verification-metadata.xml")

# metricas de contenido/senales (solo ficheros fuente)
CONTENT_PATTERNS = {
    "comment_lines":  r"^[[:space:]]*(//|/\*|\*|#|<!--)",
    "todo":           r"TODO|FIXME|XXX|HACK",
    "stubs":          r"UnsupportedOperationException|NotImplementedError|not implemented",
    "public_api":     r"^[[:space:]]*(public|export)[[:space:]]",
    "interfaces":     r"^[[:space:]]*(public[[:space:]]+)?interface[[:space:]]",
    "concurrency":    r"synchronized|Concurrent[A-Z]|volatile|Atomic[A-Z]|ReentrantLock|Semaphore|CopyOnWrite",
    "error_handling": r"catch[[:space:]]*\(|throws[[:space:]]",
    "bare_catch":     r"catch[[:space:]]*\([^)]*\)[[:space:]]*\{[[:space:]]*\}",
    "timeouts":       r"[Tt]imeout|[Rr]etry|backoff|deadline",
    "ports":          r"\b(Port|Adapter|Spi|SPI|Gateway|Provider|Repository)[A-Za-z]*\b",
    "test_annotations": r"@Test|@ParameterizedTest|describe\(|it\(|test\(",
}


def git(*args, check=True):
    return subprocess.run(["git", "-C", REPO, *args],
                          capture_output=True, text=True, check=check)


def is_generated(path):
    p = "/" + path
    if any(g in p for g in GENERATED_DIRS):
        return True
    low = path.lower()
    return any(s in low for s in GENERATED_SUBSTR)


def is_source(path):
    return path.endswith(SOURCE_EXT) and not is_generated(path)


def is_test(path):
    p = "/" + path.lower()
    return ("/test/" in p or "/tests/" in p
            or path.endswith("Test.java") or path.endswith("Tests.java")
            or path.endswith(".test.ts") or path.endswith(".spec.ts")
            or path.endswith("_test.py") or path.endswith(".test.js"))


def module_of(path):
    parts = path.split("/")
    if parts[0] == "src" and len(parts) >= 2:
        return parts[1]
    if parts[0] == "tools" and len(parts) >= 2:
        return "tools:" + parts[1]
    if len(parts) == 1:
        return "(root)"
    return parts[0]


def parse_grep(res):
    out = {}
    for ln in res.splitlines():
        if ":" not in ln:
            continue
        _rev, rest = ln.split(":", 1)
        if ":" not in rest:
            continue
        path, cnt = rest.rsplit(":", 1)
        try:
            out[path] = int(cnt)
        except ValueError:
            pass
    return out


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    log = git("log", "--reverse", "--date=short", "--format=%H|%ad").stdout
    commits = [tuple(l.split("|", 1)) for l in log.strip().splitlines() if "|" in l]
    day_end = {}
    for h, d in commits:
        day_end[d] = h
    days = sorted(day_end)

    numstat = git("log", "--reverse", "--date=short", "--numstat", "--format=@@%H|%ad").stdout
    activity = defaultdict(lambda: defaultdict(int))
    cur = None
    for line in numstat.splitlines():
        if line.startswith("@@"):
            cur = line[2:].split("|", 1)[1]
            activity[cur]["commits"] += 1
            continue
        if not line.strip() or cur is None:
            continue
        cols = line.split("\t")
        if len(cols) != 3:
            continue
        a, x, path = cols
        if is_generated(path):
            continue
        activity[cur]["added_lines"] += int(a) if a.isdigit() else 0
        activity[cur]["deleted_lines"] += int(x) if x.isdigit() else 0

    metrics_mod = ["src_loc", "src_files", "test_files", "test_loc", "doc_loc",
                   "doc_files"] + list(CONTENT_PATTERNS)
    STATE = metrics_mod + ["loc_all", "files_all"]
    ACTIVITY = ["commits", "added_lines", "deleted_lines"]
    metrics_tot = STATE + ACTIVITY

    long_rows = []
    totals = {}
    for i, d in enumerate(days):
        h = day_end[d]
        loc = parse_grep(git("grep", "-c", "-I", "-E", "-e", "^", h, check=False).stdout)
        doc = parse_grep(git("grep", "-c", "-I", "-E", "-e", "^", h, "--", "*.md",
                             check=False).stdout)
        mod = defaultdict(lambda: defaultdict(int))
        tot = defaultdict(int)
        for path, lines in loc.items():
            m = module_of(path)
            tot["loc_all"] += lines
            tot["files_all"] += 1
            mod[m]["loc_all"] += lines
            if is_source(path):
                tot["src_loc"] += lines
                tot["src_files"] += 1
                mod[m]["src_loc"] += lines
                mod[m]["src_files"] += 1
                if is_test(path):
                    tot["test_loc"] += lines
                    tot["test_files"] += 1
                    mod[m]["test_loc"] += lines
                    mod[m]["test_files"] += 1
        for path, lines in doc.items():
            if is_generated(path):
                continue
            m = module_of(path)
            tot["doc_loc"] += lines
            tot["doc_files"] += 1
            mod[m]["doc_loc"] += lines
            mod[m]["doc_files"] += 1
        for metric, pattern in CONTENT_PATTERNS.items():
            res = git("grep", "-c", "-I", "-E", "-e", pattern, h, check=False).stdout
            for path, c in parse_grep(res).items():
                if not is_source(path):
                    continue
                m = module_of(path)
                tot[metric] += c
                mod[m][metric] += c
        for k, v in activity.get(d, {}).items():
            tot[k] = v
        totals[d] = tot
        for m, vals in mod.items():
            for met in metrics_mod + ["loc_all", "files_all"]:
                if vals.get(met):
                    long_rows.append([d, m, met, vals[met]])
        print(f"  [{i+1}/{len(days)}] {d} modulos={len(mod)} src_loc={tot['src_loc']}",
              file=sys.stderr)

    for d in days:
        for met in metrics_tot:
            long_rows.append([d, "(total)", met, totals[d].get(met, 0)])

    with open(os.path.join(OUTDIR, "git_long.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["date", "module", "metric", "value"])
        w.writerows(long_rows)

    span = []
    cur_d = _date.fromisoformat(days[0])
    end_d = _date.fromisoformat(days[-1])
    state = {m: 0 for m in STATE}
    while cur_d <= end_d:
        ds = cur_d.isoformat()
        if ds in totals:
            for m in STATE:
                state[m] = totals[ds].get(m, 0)
            act = {m: totals[ds].get(m, 0) for m in ACTIVITY}
        else:
            act = {m: 0 for m in ACTIVITY}
        span.append([ds] + [state[m] for m in STATE] + [act[m] for m in ACTIVITY])
        cur_d += timedelta(days=1)
    with open(os.path.join(OUTDIR, "git_daily.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["date"] + metrics_tot)
        w.writerows(span)

    last = totals[days[-1]]
    print(f"OK: {len(days)} snapshots, {len(span)} dias. Ultimo: "
          f"src_loc={last['src_loc']} src_files={last['src_files']} "
          f"todo={last['todo']} stubs={last['stubs']} ports={last['ports']}")


if __name__ == "__main__":
    main()
