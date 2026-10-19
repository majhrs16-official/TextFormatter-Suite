#!/usr/bin/env bash
# Reproduce todas las graficas del estado del proyecto desde cero.
#
#   ./run_all.sh
#
# Variables opcionales: PM_REPO, PM_CHAT_LOG, PM_DATA, PM_IMG  (ver paths.py)
#   PM_SCAN=1  ejecuta ademas los analizadores reales (semgrep/gitleaks) si estan.
set -euo pipefail
cd "$(dirname "$0")"

echo "== 1/9  metricas del repositorio (git) =="
python3 extract_git.py

echo "== 2/9  metricas del chat (narrativa) =="
python3 extract_chat.py

echo "== 3/9  union commit <-> chat =="
python3 join_commits.py

echo "== 4/9  analizadores reales (semgrep/gitleaks) [opcional] =="
if [ "${PM_SCAN:-0}" = "1" ]; then
  python3 scan_tools.py
else
  echo "   (omitido; usa PM_SCAN=1 o 'python3 scan_tools.py' para activarlo)"
fi

echo "== 5/9  evidencia y veredictos del juez =="
python3 judge.py                          # regenera data/evidence/ (determinista)
if [ -f judge_scores.csv ]; then
  python3 judge.py --import-scores judge_scores.csv   # aplica los veredictos commiteados
else
  echo "   (sin judge_scores.csv: familias 3 y 11 usan el proxy determinista;"
  echo "    pidelo al agente con el skill @juzgar-rubrica)"
fi

echo "== 6/9  rubrica de madurez (12 familias) =="
python3 maturity.py

echo "== 7/9  paneles (PIL) =="
python3 render_pil.py

echo "== 8/9  heatmap y crecimiento =="
python3 render_extra.py

echo "== 9/9  rubrica (matriz + evolucion) =="
python3 render_maturity.py

echo
echo "Listo. Imagenes en: $(python3 -c 'import paths; print(paths.IMG)')"
