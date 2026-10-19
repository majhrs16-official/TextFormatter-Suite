---
name: Juzgar rúbrica
description: Evalúa las dos familias cualitativas de la rúbrica de madurez (documentación e implementación real) leyendo los paquetes de evidencia y escribiendo tools/project-metrics/judge_scores.csv. Úsalo cuando el usuario pida juzgar, reevaluar o refrescar la rúbrica.
---

# Juzgar la rúbrica de madurez

Este es el paso que un LLM hace mejor que cualquier proxy determinista: puntuar
**documentación** (familia 3) e **implementación real** (familia 11). Todo lo
demás del pipeline es determinista y no te necesita.

El veredicto se produce una vez y se **commitea** como dato
(`judge_scores.csv`), de modo que las imágenes se reproduzcan sin depender de ti.
Trabaja siempre desde `tools/project-metrics/`.

## Procedimiento

1. **Genera la evidencia** (determinista e instantáneo):

   ```bash
   cd tools/project-metrics && python3 judge.py
   ```

   Escribe 70 paquetes: `data/evidence/<fecha>.documentacion.md` e
   `<fecha>.implementacion_real.md`, uno por cada corte diario (35 cortes).

2. **Léelos todos antes de puntuar.** Son cortos (~10 líneas cada uno). No
   puntúes por inercia: si la evidencia difiere, la nota tiene que diferir.

3. **Puntúa con la escala completa** (ver más abajo). El error clásico es
   comprimirlo todo en 70-90, que es justo lo que hacía inútil al proxy.

4. **Escribe `tools/project-metrics/judge_scores.csv`** con la cabecera exacta
   `date,family,score,reason,juez`, una línea por corte y familia (70 en total),
   con los IDs `documentacion` e `implementacion_real` y `juez=opencode-agent`.
   **Sin comas dentro de `reason`** (usa `;`): es un CSV.

5. **Valida antes de importar:**

   ```bash
   python3 .opencode/skills/juzgar-rubrica/scripts/validate_scores.py \
       tools/project-metrics/judge_scores.csv
   ```

   Debe informar de 70 filas, 35 fechas y 0 problemas.

6. **Aplica y regenera:**

   ```bash
   cd tools/project-metrics
   python3 judge.py --import-scores judge_scores.csv
   python3 maturity.py && python3 render_maturity.py
   ```

   Comprueba que en `data/maturity_final.csv` las dos familias ya no valen lo
   mismo que el proxy y que las fuentes incluyen `juez:opencode-agent`.

7. **Resume al usuario**: nota por familia, rango, y qué cortes se movieron más.
   No hagas commit ni push: eso lo decide él.

## Qué mirar en cada familia

**documentación** — importa menos el volumen que su *sincronización* con el código:

- `doc/src`: proporción de documentación frente a código.
- `modulos con doc=0: X de Y`: la señal más fuerte. Con 45 de 48 módulos sin
  doc, la nota no puede subir aunque el ratio se sostenga.
- `ficheros .md tocados hoy`: si la documentación se mantiene viva.
- `comment/src`: densidad de comentarios.

**implementación real** — real y funcional frente a teórica:

- `stubs`: esqueletos sin implementar (peso alto).
- `todo/fixme`: trabajo pendiente reconocido.
- `public_api`, `interfaces`, `ports`: superficie realmente construida.
- `error_handling` frente a `bare_catch`: si el código se toma en serio los fallos.
- `analisis estatico`: hallazgos de semgrep y violaciones de PMD.
- Pondera por tamaño: 10 stubs en 63.000 líneas no es lo mismo que en 2.000.

## Escala (obligatoria, úsala entera)

| Banda  | Documentación                        | Implementación real                    |
| ------ | ------------------------------------ | -------------------------------------- |
| 0-20   | inexistente o engañosa               | casi todo son esqueletos o promesas    |
| 21-40  | muy deficiente                       | mayoritariamente incompleto            |
| 41-60  | parcial, con lagunas importantes o datos obsoletos | mezcla de partes reales y sin terminar |
| 61-80  | bueno, con lagunas menores           | implementado y funcional, con flecos   |
| 81-100 | excelente, completo y sincronizado   | sólido, completo y en uso              |

Justifica cada nota en **una frase con un dato concreto** de la evidencia.

## Reglas

- Nunca cargues `~/chat.log`: la evidencia ya viene destilada.
- No modifiques ningún otro fichero — tu única salida es `judge_scores.csv`.
- El veredicto es de **nivel proyecto** (`(total)`); no puntúes módulo a módulo.
- El juicio no lo puede hacer el pipeline: por eso es un skill y no un paso de
  `run_all.sh`. Si no hay veredicto, las familias 3 y 11 caen al proxy
  determinista y la rúbrica se genera igual.
