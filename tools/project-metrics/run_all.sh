#!/usr/bin/env bash
# Reproduce todas las graficas del estado del proyecto desde cero.
#
#   ./run_all.sh
#
# Variables opcionales: PM_REPO, PM_CHAT_LOG, PM_DATA, PM_IMG  (ver paths.py)
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1/7  metricas del repositorio (git) =="
python3 extract_git.py

echo "== 2/7  metricas del chat (narrativa) =="
python3 extract_chat.py

echo "== 3/7  union commit <-> chat =="
python3 join_commits.py

echo "== 4/7  rubrica de madurez (12 familias) =="
python3 maturity.py

echo "== 5/7  paneles (PIL) =="
python3 render_pil.py

echo "== 6/7  heatmap y crecimiento =="
python3 render_extra.py

echo "== 7/7  rubrica (matriz + evolucion) =="
python3 render_maturity.py

echo
echo "Listo. Imagenes en: $(python3 -c 'import paths; print(paths.IMG)')"
