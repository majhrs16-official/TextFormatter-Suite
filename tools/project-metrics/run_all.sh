#!/usr/bin/env bash
# Reproduce todas las graficas del estado del proyecto desde cero.
#
#   ./run_all.sh
#
# Variables opcionales: PM_REPO, PM_CHAT_LOG, PM_DATA, PM_IMG  (ver paths.py)
#   PM_SCAN=1  ejecuta ademas los analizadores reales (semgrep/gitleaks) si estan.
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1/8  metricas del repositorio (git) =="
python3 extract_git.py

echo "== 2/8  metricas del chat (narrativa) =="
python3 extract_chat.py

echo "== 3/8  union commit <-> chat =="
python3 join_commits.py

echo "== 4/8  analizadores reales (semgrep/gitleaks) [opcional] =="
if [ "${PM_SCAN:-0}" = "1" ]; then
  python3 scan_tools.py
else
  echo "   (omitido; usa PM_SCAN=1 o 'python3 scan_tools.py' para activarlo)"
fi

echo "== 5/8  rubrica de madurez (12 familias) =="
python3 maturity.py

echo "== 6/8  paneles (PIL) =="
python3 render_pil.py

echo "== 7/8  heatmap y crecimiento =="
python3 render_extra.py

echo "== 8/8  rubrica (matriz + evolucion) =="
python3 render_maturity.py

echo
echo "Listo. Imagenes en: $(python3 -c 'import paths; print(paths.IMG)')"
