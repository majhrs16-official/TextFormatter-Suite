#!/usr/bin/env python3
"""
Etapa D: rubrica de madurez, 12 familias, puntuacion 0-100.

Calcula la madurez por MODULO y por CORTE (fecha de commit) a partir de las
senales deterministas de git_long.csv. Si existen salidas de herramientas reales
(data/tools/<fecha>.json: semgrep, gitleaks, pmd, eslint, owasp), las usa para
sustituir la aproximacion determinista de las familias correspondientes.

Familias:
   1. calidad_codigo    (PMD/SpotBugs + ESLint)      -> defectos por kLOC
   2. cobertura_tests   (JaCoCo + test_ratio)        -> tests por kLOC, ratio
   3. documentacion     (README sincronizado)        -> doc_loc, comentarios
   4. seguridad         (OWASP dep-check + Semgrep)  -> defensa vs errores
   5. secretos          (gitleaks)                    -> fugas detectadas
   6. dependencias      (Gradle/JS, reproducibilidad)-> wrapper, lockfiles
   7. concurrencia      (thread-safety)              -> synchronized/Atomic...
   8. estabilidad       (resiliencia)                -> errores+timeouts
   9. rendimiento       (JMH/Gatling)                -> timeouts, concurrencia
  10. arquitectura      (hexagonal/puertos)          -> ports, interfaces, API
  11. implementacion_real (real vs teorico)          -> stubs, API publica
  12. integracion       (grafo Gradle)               -> API, ports, tests

Salida:
  - data/maturity_long.csv   : date,module,family,score,source
  - data/maturity_final.csv  : module,family,score  (ultimo corte, solo m/total)
"""
import csv
import json
import os
import subprocess
import sys
from collections import defaultdict

import paths

REPO = sys.argv[1] if len(sys.argv) > 1 else paths.REPO
DATA = sys.argv[2] if len(sys.argv) > 2 else paths.DATA

FAMILIES = [
    "calidad_codigo", "cobertura_tests", "documentacion", "seguridad",
    "secretos", "dependencias", "concurrencia", "estabilidad",
    "rendimiento", "arquitectura", "implementacion_real", "integracion",
]

# --- utilidades de puntuacion -------------------------------------------------

def clamp(x):
    return max(0.0, min(100.0, x))


def sat(x, target):
    """Saturacion lineal: 0 -> 0, target -> 100, >target -> 100."""
    if target <= 0:
        return 0.0
    return clamp(100.0 * x / target)


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args],
                          capture_output=True, text=True, check=False).stdout


def load_tools(date):
    """Salida de herramientas reales para esa fecha, si existe."""
    p = os.path.join(DATA, "tools", f"{date}.json")
    if os.path.exists(p):
        with open(p) as f:
            return json.load(f)
    return {}


def dep_score(files):
    """Reproducibilidad de dependencias a partir de ficheros presentes."""
    sc = 0
    if "gradlew" in files or "gradlew.bat" in files:
        sc += 25
    if any(f.startswith("gradle/wrapper/") for f in files):
        sc += 15
    if "settings.gradle.kts" in files or "settings.gradle" in files:
        sc += 15
    if any(f.endswith("verification-metadata.xml") for f in files):
        sc += 20
    if any(f.endswith("gradle.lockfile") or f.endswith(".lockfile") for f in files):
        sc += 15
    if any(f.endswith(("package-lock.json", "pnpm-lock.yaml", "yarn.lock")) for f in files):
        sc += 10
    return clamp(sc)


# --- nucleo de la rubrica -----------------------------------------------------

def score_scope(m, dep=0.0, tools=None, module="(total)"):
    """Devuelve {familia: (score, fuente)} para un ambito (modulo o total)."""
    tools = tools or {}
    src = float(m.get("src_loc", 0))
    kl = max(src / 1000.0, 0.001)
    d = lambda k: float(m.get(k, 0)) / kl

    test_ratio = float(m.get("test_loc", 0)) / src if src else 0.0
    doc_ratio = float(m.get("doc_loc", 0)) / src if src else 0.0
    comment_ratio = float(m.get("comment_lines", 0)) / src if src else 0.0

    out = {}
    D = "determinista"

    # 1. calidad de codigo: ausencia de defectos por kLOC
    s = clamp(100 - min(40, d("stubs") * 40) - min(30, d("bare_catch") * 40)
              - min(20, d("todo") * 40))
    out["calidad_codigo"] = (s, D)
    if "pmd" in tools:
        p = tools["pmd"]
        by = p.get("by_module", {})
        if module == "(total)":
            v, ok = p.get("violations", 0), True
        else:
            v, ok = by.get(module, 0), module in by
        if ok:
            base = max(src, 5000) / 1000.0      # suelo: evita ruido en modulos pequenos
            out["calidad_codigo"] = (clamp(100 - min(100, v / base * 1.5)), "pmd")

    # 2. cobertura de tests
    s = 0.70 * sat(test_ratio, 0.30) + 0.30 * sat(d("test_annotations"), 20)
    out["cobertura_tests"] = (clamp(s), D)

    # 3. documentacion
    s = (0.55 * sat(doc_ratio, 0.50) + 0.25 * sat(comment_ratio, 0.15)
         + 0.20 * sat(float(m.get("doc_files", 0)), 10))
    out["documentacion"] = (clamp(s), D)

    # 4. seguridad (proxy defensivo; idealmente Semgrep + OWASP)
    s = 0.60 * (100 - min(50, d("bare_catch") * 50)) + 0.40 * sat(d("error_handling"), 25)
    out["seguridad"] = (clamp(s), D)
    if "semgrep" in tools:
        sg = tools["semgrep"]
        w = (sg.get("weight", sg.get("findings", 0)) if module == "(total)"
             else sg.get("by_module", {}).get(module, 0))
        out["seguridad"] = (clamp(100 - min(100, w)), "semgrep")

    # 5. gestion de secretos (gitleaks). Sin herramienta no hay deteccion real.
    sc, src_tag = (100.0, "asumido") if "gitleaks" not in tools else \
        (clamp(100 - min(100, tools["gitleaks"].get("leaks", 0) * 25)), "gitleaks")
    out["secretos"] = (sc, src_tag)

    # 6. dependencias y reproducibilidad (nivel proyecto)
    out["dependencias"] = (clamp(dep), "estructura")

    # 7. concurrencia / thread-safety
    s = 0.70 * sat(d("concurrency"), 12) + 0.30 * sat(d("interfaces"), 2)
    out["concurrencia"] = (clamp(s), D)

    # 8. estabilidad y resiliencia
    s = (0.50 * sat(d("error_handling"), 22) + 0.30 * sat(d("timeouts"), 6)
         + 0.20 * (100 - min(60, d("bare_catch") * 60)))
    out["estabilidad"] = (clamp(s), D)

    # 9. rendimiento y latencia
    s = 0.60 * sat(d("timeouts"), 6) + 0.40 * sat(d("concurrency"), 12)
    out["rendimiento"] = (clamp(s), D)

    # 10. arquitectura hexagonal / puertos y adaptadores
    s = (0.55 * sat(d("ports"), 5) + 0.25 * sat(d("interfaces"), 1.5)
         + 0.20 * sat(d("public_api"), 45))
    out["arquitectura"] = (clamp(s), D)

    # 11. implementacion real vs teorico
    s = 0.60 * (100 - min(60, d("stubs") * 60)) + 0.40 * sat(d("public_api"), 40)
    out["implementacion_real"] = (clamp(s), D)

    # 12. integracion entre componentes
    s = (0.40 * sat(d("public_api"), 40) + 0.30 * sat(d("ports"), 5)
         + 0.30 * sat(test_ratio, 0.30))
    out["integracion"] = (clamp(s), D)

    return out


def main():
    os.makedirs(DATA, exist_ok=True)

    # metricas por fecha y modulo
    by = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    with open(os.path.join(DATA, "git_long.csv")) as f:
        for r in csv.DictReader(f):
            by[r["date"]][r["module"]][r["metric"]] = int(r["value"])

    dates = sorted(by)
    print(f"cortes: {len(dates)}  ({dates[0]} -> {dates[-1]})")

    # commit de fin de cada dia (para leer el arbol de ficheros de cada corte)
    dep_by_date = {}
    log = git("log", "--reverse", "--date=short", "--format=%H|%ad").strip().splitlines()
    day_end = {}
    for ln in log:
        if "|" in ln:
            h, dd = ln.split("|", 1)
            day_end[dd] = h
    for d in dates:
        h = day_end.get(d, "HEAD")
        files = set(git("ls-tree", "-r", "--name-only", h).splitlines())
        dep_by_date[d] = dep_score(files)

    rows, final_rows = [], []
    for d in dates:
        tools = load_tools(d)
        for mod, met in by[d].items():
            sc = score_scope(met, dep_by_date[d], tools, mod)
            for fam in FAMILIES:
                v, tag = sc[fam]
                rows.append([d, mod, fam, round(v, 1), tag])
        # cabecera de resumen por corte
        tot = by[d].get("(total)", {})
        sc = score_scope(tot, dep_by_date[d], tools)
        media = sum(sc[f][0] for f in FAMILIES) / len(FAMILIES)
        print(f"  {d}  madurez_global={media:5.1f}  "
              f"tests={sc['cobertura_tests'][0]:4.1f} "
              f"arq={sc['arquitectura'][0]:4.1f} seg={sc['seguridad'][0]:4.1f}")

    with open(os.path.join(DATA, "maturity_long.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["date", "module", "family", "score", "source"])
        w.writerows(rows)

    last = dates[-1]
    tools = load_tools(last)
    with open(os.path.join(DATA, "maturity_final.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["module", "family", "score"])
        for mod, met in sorted(by[last].items()):
            sc = score_scope(met, dep_by_date[last], tools, mod)
            for fam in FAMILIES:
                w.writerow([mod, fam, round(sc[fam][0], 1)])

    print(f"OK: {len(rows)} filas -> data/maturity_long.csv + maturity_final.csv")


if __name__ == "__main__":
    main()
