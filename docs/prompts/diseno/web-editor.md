# Web Editor — Diseño (F6, revisión 2: GIMP/Grafana + canvas de nodos)

> **Estado: diseño cerrado.** Traslación directa al implementar: sin build,
> sin framework, HTML+CSS+JS vanilla, artefacto único para GitHub Pages.
> Esta revisión sustituye a la v1 (rail de 5 módulos + status bar inferior).
> Toda decisión nueva se anota en §10 (changelog).

## 1. Principios

- **Un solo artefacto estático** (GitHub Pages). HTML + CSS + JS vanilla. Nada
  de red obligatoria; solo traducción viva (opcional, degradable).
- **Canvas de celdas/nodos** como centro de edición; la suite se configura en
  celdas (TextFormatter) y grafos (iFlow) con **puertos arriba (entradas) y
  abajo (salidas)**.
- **El layout es del usuario**: paneles **extraíbles y reordenables**. El
  ajuste de layout, tema (oscuro elegante por defecto) e idioma se guardan en
  la configuración del navegador (localStorage).
- **La configuración YAML es la fuente única de verdad**: el round-trip
  import→panel→export es **exacto**. Aplica al proyecto completo (celdas,
  grafo, permisos, sync, velocity). Prohibido hardcodear campos o atajos: lo
  que no cabe en YAML es falta de precisión del schema o potencia del motor.
- **El schema va primero** (v2.2, §8): define los archivos exactos sobre los
  que editor y host negocian. El editor no dibuja nada que el schema no
  represente.
- **El preview replica el pipeline del motor** (port JS + fixtures dorados
  validados contra el host Java), para simular sin red.
- **Idioma UI: inglés por defecto.** Catálogos en/es empaquetados; traducción
  viva opcional a otros idiomas con **pool de concurrencia + rate-limit**
  (peticiones paralelas acotadas); ante fallo/CORS → inglés.

## 2. Layout (wireframe)

```
+──────────────────────────────────────────────────────────────────────────────────+
│ HEADER [◧ Suite] ▸ navbar: grupo › submenu › sección        [🔍][🌙][es|en]      │
+──────┬───────────────────────┬───────────────────────────────────────────────────+
│ RAIL │ T B A R (fila 1) ↶ ↷ ⟲  ⧉ duplicar  ✓ validar  ••• sección        [🔵]-> ⎤
│      │ (fila 2 = PALETTE contextual: [celda] [bucle] [condición] [célula]…)      ⎥ ⏷
+ Cfg  │                                                                           │ ▣
│ Txf  │  W O R K S P A C E   canvas con zoom (ctrl+rueda), pan (espacio+bloque)   │ previs
│ iFlo │  ┌─────────────────────────────────┐  ┌────────────┐                      │  ▣ downl
│ Loud │  │ [Entrada]    ▼                 µ │  │ minimapa    │                      │
│ Sync │  │  ┌────┐     ┌─────┐     ┌────┐ │  └────────────┘                      │
│ Perm │  │  │ celda ▶ ──▼ cond ▶ ──▶ celda │◀┐   │  paneles extraíbles            │
│ Nodo │  │  └────┘     └─────┘     └────┘ │└── bucle ──┘                         │
│ …    │  │      │ salida ▼                  …                                     │
│      │  └─────────────────────────────────┘                                      │
+──────┴──────────────┬──────────────────────────┬─────────────────────────────────┘
STATUS: 12 celdas · 9 aristas · 3 reglas default · config 2.4 KB  [✓ ok · ✗ 2 err]
```

- **Header/barra superior de doble fila.**
  - Fila 1 (acciones): undo/redo, reset, duplicar, validar, y a la derecha
    **🔵 Previsualizar** y **⏷ Descargar (verde claro)** en la misma altura.
  - Fila 2 (**palette contextual**): items arrastrables a imagen del canvas;
    cambia según la sección activa.
- **Navbar**: ruta actual `grupo › submenu › sección` (breadcrumb).
- **Sidebar izquierda** (GIMP/Blender/Grafana): grupos + submenús con badges
  (nº celdas, nº errores rojos).
- **Canvas**: zoom `ctrl+rueda` (min 25%, máx 400%), pan `espacio+arrastre`,
  snap a rejilla 20px, botón "ajustar a la vista", minimapa.
- **Status bar**: nº de celdas, aristas, reglas default, tamaño de la config
  (bytes/KB), conteo de errores. Elementos rojos (rings/badges/toast) rastrean
  fallos puntuales. `● sin guardar` tras edición.

## 3. Sidebar: grupos y submenús

| Grupo | Submenús |
|---|---|
| Configuración general | plugin (`config.yml`): idioma default, quick-look, sonido on/off, `engine.parallel`, … |
| TextFormatter | Canales (celdas); Plantillas (mensajes/tooltips con tokens); Sonidos; Idiomas default; Rate-limit |
| iFlow | Grafo (canvas); Default-policy por canal; Targets; Tests de regla |
| Traductores | Google; Libre (base-url + api-key); preferencia de provider; Test A→B |
| Sync | Discord; Telegram; HTTP; TCP-UDP; **Velocity** (interconexión entre servidores) |
| Permisos | Tabla **rol ↔ permiso** (qué permiso vinculado a qué rol) |
| Kernel/Estado | Detección de módulos, handshake (contractVersion/jvmRange), registro |

**Velocity** (nuevo): módulo de proxy (tipo Velocity/BungeeCord) para
**interconectar chats entre varios servidores**. Submenu: toggle activación,
secret/token, listado de servidores, mapeo servidor→canal local, hub. El
runtime del plugin es scope de motor (F7+); el editor solo configura el YAML.

## 4. Celdas (TextFormatter = canales)

- Celdas rectangulares redondeadas, arrastrables al canvas y renombrables.
  **TxF no se interconecta**: la celda define propiedades.
- Propiedades de celda: permisos (base/send/receive), plantillas
  (mensajes/tooltips con tokens), **sonidos (multi, listbox con check a la
  izquierda)**, idiomas source/target, mostrar-sender, rate-limit.
- **Identidad única de canal**: renombrar propaga a iFlow (nodo) y a Sync
  (mapeo remoto→local). **Borrar un canal referenciado auto-limpia** las
  interconexiones (aristas) dependientes.
- **Tabla default** (estilo *chain default* de iptables): una cadena/rejilla
  base con reglas por defecto. Arrastrar una fila fuera de la tabla la
  convierte en **canal nuevo que hereda las reglas por defecto**; el canal
  aparece automáticamente en iFlow con esa chain default aplicada.

## 5. Grafo iFlow (canvas de nodos)

### 5.1 Tipos de nodo (palette de iFlow)
`célula` (canal), `bucle`, `condición`, `redirección` (target), `canal` de
salida, `log/test`, `sleep/delay`, y **`transform`** (modifica el
evento/mensaje: cambia el texto traducible, lo reformatea, agrega o quita
varios sonidos — listbox con check). El `transform` es la feature original
(reformateo de grupos de formato = canales); requiere motor F7+ y se marca en
el `manifest.json` si el engine no lo soporta aún (export forward-compat).

### 5.2 Semántica de conexión
- **Entradas múltiples = merge implícito (mux)**: cada mensaje que llega por
  cualquier puerto se procesa de forma independiente. No hay combinación.
- **Salidas múltiples = broadcast (fan-out)**: el mensaje se propaga a todos
  los puertos inferiores.
- **Ramificación = condición que filtra por camino** (si no coincide, esa
  copia se DROP-edita silenciosamente). Los `transform` no condicionan.
- **Ciclos permitidos**: un arco de salida→entrada realimenta con una
  condición de salida + `sleep/delay` (estructura sugerida, no obligatoria:
  libertad total).
- **Duplicación por fan-out**: si dos caminos llevan al mismo destino, el
  mensaje llega duplicado (N copias). El engine **deduplica por
  (mensaje, camino→destino)** y el preview muestra las N copias explícitamente.
- Nodos sin salida no son inválidos (términos/léxicos que no se extienden).
- **Puertos**: entradas arriba, salidas abajo (estilo helvum/qpwgraph rotado).

### 5.3 Orden y guardas
- **Prioridad = BFS por capas desde los nodos de entrada**; empates por índice
  de creación. Los ciclos rompen el BFS solo para numerar.
- En runtime el motor ejecuta por flujo (no por prioridad); la prioridad solo
  desempata reglas concurrentes del Router.
- **Guard anti bucle**: máximo de pasos por mensaje (default 512), superado →
  DROP + log. Valores en schema y manifest.

## 6. Traductores y preview

- Provisores: Google (endpoint implícito) y Libre (base-url + api-key);
  preferencia de provider activo; test A→B.
- **🔵 Previsualizar** simula el pipeline completo: entrada → traducción →
  ruta iFlow + transforms → formato → salida. Reusa el port JS del motor
  (fixtures dorados compartidos con el host). Muestra el mensaje de ejemplo
  editable sobre un canal elegido, sonido WebAudio (multi) y tooltips.
- **🆕 Traducción viva de la página**: pool de peticiones con límite de
  concurrencia y rate-limit; asíncrona y por lotes; fallback a inglés.

## 7. Sync (bordes)

| Borde | Configuración |
|---|---|
| Discord | token (**pegar token** / **obtener token**, ambos grafito; obtener → abre dev portal de Discord en pestaña nueva; pegar = `navigator.clipboard.readText()` con fallback a enfocar campo), canal remoto→local |
| Telegram | token, chat-id, hub |
| HTTP | webhook url, puerto inbound, path |
| TCP-UDP | host, puertos, protocolo |
| Velocity | toggle, secret/token, servidores, mapeo servidor→canal, hub |

## 8. Schema v2.2 (fuente única de verdad, round-trip exacto)

- `config.yml` (config del plugin: idioma, quick-look, sonido, engine.parallel).
- `channels/*.yml`: propiedad de cada celda/canal, incl. permisos y sonidos
  multi y la herencia de la chain default.
- `rules.yml`: grafo iFlow → reglas (prioridad BFS, matchers,
  **steps transform**, sleep, condiciones, targets).
- `translators/*.yml`, `sync/*.yml` (incl. velocity).
- `manifest.json`: versiones + resultado de validación + **capabilities del
  engine** (e.g. `transforms: true/false`).
- Import/export exactos: el panel se reconstruye desde estos archivos y,
  re-exportado, produce bytes idénticos.

## 9. Validación y persistencia

- **Validación global**: pase por todo el proyecto → `[{nivel, grupo, ruta,
  mensaje}]`; alimenta badges, rings rojos, toasts y el manifest. **Nunca se
  descarga con errores bloqueantes.**
- Persistencia del navegador (localStorage): layout de paneles, tema
  claro/oscuro, idioma UI, y **autosave del proyecto** (celdas+grafo+config)
  independiente del layout.

## 10. Changelog de decisiones (esta revisión)

- Layout GIMP/Blender/Grafana: sidebar de grupos + toolbar doble + navbar.
- Celdas redondeadas (TxF incluidas); TxF no se interconecta (solo propiedades).
- Tabla default (chain default iptables): arrastrar fuera = canal nuevo con
  reglas default; aparece en iFlow automáticamente.
- Canvas: puertos arriba/abajo; mux/fan-out/condición-filtro; ciclos con guard;
  dedup fan-out; orphan permitido; prioridad BFS; zoom 25–400% (`ctrl+rueda`),
  pan espacio+arrastre, snap 20px, minimapa, ajustar a vista.
- `transform` (texto/reformatear/sonidos multi con check) en iFlow y en celda
  TxF; motor F7+, export forward-compat + flag en manifest.
- Permisos = tabla rol↔permiso en submenú propio.
- Previsualizar azul / Descargar verde claro arriba a la derecha (altura del
  toolbar). Preview = motor JS + fixtures dorados.
- Discord: pegar/obtener token grafito; obtener → dev portal en pestaña nueva.
- i18n: inglés por defecto; es/en; traducción viva con pool/rate-limit → fallback ingles.
- Status bar: conteos (celdas/aristas/reglas/tamaño config) + rojos de fallos.
- Borde **Velocity** (interconexión de chats entre servidores) añadido a Sync.
- Autosave localStorage del proyecto; layout/tema/idioma persistidos.

## 11. Fuera de alcance de F6 (posible F6.x / F7+)

Implementación del `transform` en el motor (F7+), plugin Velocity real (F7+),
reescritura en framework, deploy a servidores, simulación JMH en navegador,
editor tipo "zona de prueba" multiusuario.