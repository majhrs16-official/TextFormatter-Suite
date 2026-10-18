#!/usr/bin/env python3
"""
consolidate.py - une los exports de sesiones de OpenCode en un unico chat.log.

Uso:
    consolidate.py -o ~/chat.log EXPORT1.json EXPORT2.json ...
    consolidate.py -o ~/chat.log CARPETA_CON_EXPORTS/

Cada sesion se escribe como una linea JSON (JSONL). El resultado es el unico
fichero que consume extract_chat.py (`chat.log`), y no se sube a GitHub.
"""
import argparse
import glob
import json
import os
import sys


def iter_sessions(path):
    """Itera las sesiones de un fichero: JSON unico, JSON en lista o JSONL."""
    with open(path) as f:
        first = f.readline()
        rest = f.read(1)
    try:
        obj = json.loads(first)
    except Exception:
        obj = None
    if isinstance(obj, dict):
        if not rest:
            yield obj                      # sesion unica compacta (una linea)
            return
        with open(path) as f:              # JSONL: una sesion por linea
            for line in f:
                line = line.strip()
                if line:
                    yield json.loads(line)
        return
    with open(path) as f:                  # JSON bonito o lista de sesiones
        data = json.load(f)
    if isinstance(data, list):
        yield from data
    elif isinstance(data, dict):
        if "messages" in data:
            yield data
        else:
            yield from data.get("sessions", [])


def main():
    ap = argparse.ArgumentParser(description="Consolida exports de OpenCode en chat.log (JSONL).")
    ap.add_argument("-o", "--out", default=os.path.expanduser("~/chat.log"))
    ap.add_argument("src", nargs="+", help="ficheros .json o carpetas que los contienen")
    a = ap.parse_args()

    files = []
    for s in a.src:
        s = os.path.expanduser(s)
        if os.path.isdir(s):
            files += sorted(glob.glob(os.path.join(s, "*.json")))
        else:
            files.append(s)
    if not files:
        sys.exit("no se encontraron ficheros de entrada")

    seen, n = set(), 0
    with open(a.out, "w") as out:
        for p in files:
            for s in iter_sessions(p):
                key = (s.get("info") or {}).get("id") or (s.get("info") or {}).get("title")
                if key and key in seen:
                    continue
                if key:
                    seen.add(key)
                out.write(json.dumps(s, ensure_ascii=False) + "\n")
                n += 1
    print(f"escrito {a.out}: {n} sesiones a partir de {len(files)} ficheros")


if __name__ == "__main__":
    main()
