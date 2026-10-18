#!/usr/bin/env bash
# Reproduce todas las graficas del estado del proyecto desde cero.
#
#   ./run_all.sh
#
# Variables opcionales: PM_REPO, PM_CHATS, PM_CHAT_LOG, PM_DATA, PM_IMG  (ver paths.py)
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1/5  metricas del repositorio (git) =="
python3 extract_git.py

echo "== 2/5  metricas del chat (narrativa) =="
python3 extract_chat.py

echo "== 3/5  union commit <-> chat =="
python3 join_commits.py

echo "== 4/5  paneles (PIL) =="
python3 render_pil.py

echo "== 5/5  heatmap y crecimiento =="
python3 render_extra.py

echo
echo "Listo. Imagenes en: $(python3 -c 'import paths; print(paths.IMG)')"
