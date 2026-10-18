#!/usr/bin/env python3
"""Etapa C: une los commits detectados en el chat con el historial real de git."""
import csv
import os
import subprocess
import sys

import paths

REPO = sys.argv[1] if len(sys.argv) > 1 else paths.REPO
DIR = paths.DATA


def git(*a):
    return subprocess.run(["git", "-C", REPO, *a], capture_output=True, text=True).stdout


repo = set(git("rev-list", "HEAD").split())
# metadatos de cada commit del repo
meta = {}
cur = None
for line in git("log", "--date=short", "--format=@@%H|%ad|%s").splitlines():
    if line.startswith("@@"):
        h, d, s = line[2:].split("|", 2)
        meta[h] = (d, s)

rows = list(csv.DictReader(open(os.path.join(DIR, "chat_commits.csv"))))
uniq = {}
for r in rows:
    h = (r["commit"] or "").strip()
    if h and h not in uniq:
        uniq[h] = r

full = {}
unresolved = []
for h, r in uniq.items():
    out = git("rev-parse", "--verify", "--quiet", f"{h}^{{commit}}").strip()
    if out and out in repo:
        full[out] = r
    else:
        unresolved.append(h)

def clean(s):
    return " ".join((s or "").split())[:200]

with open(os.path.join(DIR, "commit_chat.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["commit", "date", "session", "message", "repo_subject"])
    for h in sorted(full, key=lambda x: meta.get(x, ("", ""))[0]):
        r = full[h]
        d, s = meta.get(h, ("?", "?"))
        w.writerow([h[:10], d, r["session"], clean(r["message"]), clean(s)])

print(f"commits unicos detectados en chat : {len(uniq)}")
print(f"resueltos a un commit real de git : {len(full)}")
print(f"sin resolver                      : {len(unresolved)} {unresolved[:5]}")
print(f"commits totales del repo          : {len(repo)}")
print(f"cobertura del repo por el chat    : {len(full)/len(repo)*100:.1f}%  "
      f"({len(full)}/{len(repo)})")
print("\n-> commit_chat.csv escrito (mapeo commit <-> sesion de chat)")
