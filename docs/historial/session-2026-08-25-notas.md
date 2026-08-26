# Sesión 2026-08-25 — notas históricas

> Archivado desde PROMPT_NOW.md al reestructurar el plan. No es plan vigente.

## NOTAS PARA PRÓXIMA SESIÓN
- Leer este archivo al inicio; actualizar estados ✅/❌.
- **Prioridad inmediata = FASE 2B**: A1 persistencia idioma + `/suite lang`
  (port `UserLanguageStore`, backend YAML), A2 eventos join/quit/death,
  A3 claim-mode configurable (cancel-event|clear-recipients).
- Antes de codificar A3: decidir clave y semántica exacta en config.yml y
  propagarla a schema del editor (nueva mini-versión v2.2.1 o v2.3).
- Fat-jar spigot-host: regenerarlo si se toca cualquier jar hermano
  (procedimiento manual sin red en historial de sesión).
- Git: ~140 cambios sin commitear (incluye eliminación del trío) — pedir
  autorización para commitear/pushear por temas.
- Auditoría integral + veredictos: docs/AUDITORIA-2026-08-24.md (fuente de
  verdad de prioridades FASE 2B).
- Deuda menor conocida: ventana de shutdown en delivery (P4), consola
  fail-closed en permisos (documentado), `pool.max-concurrent` sin consumir.
- Con spigot-host la suite YA corre en Spigot como plugin propio
  (coexistiendo con el legacy). Falta validación en servidor y Fabric.
