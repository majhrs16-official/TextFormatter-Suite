#!/usr/bin/env python3
"""Valida judge_scores.csv antes de importarlo con judge.py --import-scores.

Comprueba cabecera exacta, cinco columnas por fila (el fallo tipico es una coma
sin escapar dentro de `reason`), familias conocidas, notas enteras en 0-100,
`reason` no vacia y que cada fecha traiga las dos familias.

Uso: validate_scores.py [ruta/al/judge_scores.csv]
"""
import collections
import csv
import sys

FAMS = {"documentacion", "implementacion_real"}
HEADER = ["date", "family", "score", "reason", "juez"]


def main(path):
    with open(path, newline="") as f:
        rows = list(csv.reader(f))
    if not rows:
        print(f"{path}: vacio")
        return 1
    if rows[0] != HEADER:
        print(f"cabecera incorrecta:\n  obtenida: {rows[0]}\n  esperada: {HEADER}")
        return 1

    body = rows[1:]
    problems = []
    seen = collections.defaultdict(set)
    for i, r in enumerate(body, start=2):
        if len(r) != 5:
            problems.append(f"linea {i}: {len(r)} columnas "
                            "(¿coma sin escapar dentro de reason?)")
            continue
        date, fam, score, reason, _juez = r
        if fam not in FAMS:
            problems.append(f"linea {i}: familia desconocida {fam!r}")
        if not date.strip():
            problems.append(f"linea {i}: fecha vacia")
        try:
            s = int(float(score))
        except ValueError:
            problems.append(f"linea {i}: nota no numerica {score!r}")
            continue
        if not 0 <= s <= 100:
            problems.append(f"linea {i}: nota fuera de 0-100 ({s})")
        if not reason.strip():
            problems.append(f"linea {i}: reason vacia")
        seen[date].add(fam)

    for date, fams in sorted(seen.items()):
        if fams != FAMS:
            problems.append(f"{date}: familias {sorted(fams)} (se esperan las dos)")

    print(f"filas={len(body)}  fechas={len(seen)}  problemas={len(problems)}")
    for p in problems[:20]:
        print(f"  ! {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "judge_scores.csv"))
