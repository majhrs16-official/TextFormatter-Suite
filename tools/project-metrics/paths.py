#!/usr/bin/env python3
"""
paths.py - rutas del pipeline de metricas del proyecto.

Todo es configurable por variables de entorno, de modo que cualquiera puede
reproducir las graficas en local desde un clon del repositorio:

    PM_REPO       ruta al repositorio git a analizar   (por defecto: la raiz del repo)
    PM_CHAT_LOG   exportacion de los chats de OpenCode  (por defecto: ~/chat.log)
    PM_DATA       carpeta de datos intermedios (CSV)    (por defecto: ./data)
    PM_IMG        carpeta de imagenes generadas (PNG)   (por defecto: <repo>/docs/metrics)

El unico fichero de entrada de la capa narrativa es `chat.log`: la exportacion
de las sesiones de OpenCode (JSONL, una sesion por linea). NO se sube a GitHub;
(si estuviera dentro del repo, la regla `*.log` de la raiz ya lo ignoraria).
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

REPO = os.environ.get("PM_REPO") or ROOT
CHAT_LOG = os.environ.get("PM_CHAT_LOG") or os.path.expanduser("~/chat.log")
DATA = os.environ.get("PM_DATA") or os.path.join(HERE, "data")
IMG = os.environ.get("PM_IMG") or os.path.join(ROOT, "docs", "metrics")

for d in (DATA, IMG):
    os.makedirs(d, exist_ok=True)
