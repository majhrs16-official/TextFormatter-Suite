Proyecto actual: /home/majhrs16/Documentos/textformatter-suite + https://github.com/majhrs16-official/TextFormatter-Suite
Proyecto original: /home/majhrs16/Documentos/chattranslator + /home/majhrs16/Documentos/chattranslator.wiki + https://github.com/majhrs16-official/ChatTranslator

Objetivos:
- Extremadamente potente: Diseñado sobre componentes con responsabilidad atómica en instrucciones/configuración (sin implicar sintaxis de bajo nivel), permitiendo cualquier combinación compleja.
- Extremadamente personalizable/configurable: Ante múltiples opciones de diseño en la UI o parámetros de configuración para el usuario final, implementar ambas alternativas y permitir que la comunidad decida.
- Extremadamente modular: Todo componente desacoplable debe constituir un módulo/extensión independiente.
- Clean + Hexagonal Architecture real: Core agnóstico a la plataforma base.
- Reconstrucción moderna: Replantear el proyecto original con bases y prácticas modernas, manteniendo o superando sus resultados mediante una nueva sintaxis.
- Estándar empresarial: Arquitectura no bloqueante, altamente escalable (vertical y horizontalmente) con rendimiento optimizado tanto monohilo como en paralelo.

Régimen vigente (ADR-034):
- El proyecto está en pre-release. No existe versión estable ni producción.
- Toda la suite se identifica con la base `1.0`: módulos, contratos, artefactos y esquemas. `1.0.0` es la etiqueta de release de la base integrada; `1.0` se declara estable solo cuando el proyecto completo está consolidado y sin errores críticos de diseño.
- No se mantiene retrocompatibilidad de features ni de contratos en pre-release: no hay adaptadores, aliases, shims, dobles lectores, rutas legacy ni migraciones en runtime.
- Los módulos legacy que se conservan por utilidad (`iflow-firewall`, `coretranslator`) son implementaciones independientes, no puentes de compatibilidad.
- El betatesting/E2E real se ejecuta después de `1.0.0`; sus fallos se corrigen en la línea beta.

Reglas:
- Cero valores hardcodeados: Todo comportamiento debe parametrizarse mediante configuración.
- **Primero las bases**: Manager de módulos, SPI de descubrimiento de servicios, kernel en runtime y packaging/CI van antes que las features. El estado y el orden vigente viven en `docs/workdir/PLAN.md`.
- **Documento vivo**: ante un cambio importante de objetivo, actualizar este archivo. La fuente de verdad operativa es `docs/workdir/PLAN.md`; las decisiones de arquitectura, `docs/workdir/ADR.md`.
- **Precedencia documental**: ADR aceptado > especificación vigente > código (el código divergente es un bug) > PLAN > skills > documentos históricos. La wiki no es fuente de verdad.

Ecosistema y Módulos (Suite):
El proyecto se concibe como una suite donde cada motor es una extensión descargable e instalable desde el Manager. Las actualizaciones de módulos requieren reiniciar el servidor; las reconfiguraciones de parámetros admiten Hot-Reload.
- iFlow: hub de routing/firewall para eventos de texto. Los motores `iflow-vm`, `iflow-wasm` e `iflow-firewall` pueden coexistir instalados; el hub activa uno solo (ADR-032).
- TextFormatter: Motor principal de procesamiento de texto.
- GTranslate: Motor de traducción vía web scraping directo a Google Translate (incluye mitigadores como rotación de User-Agents y límites de peticiones configurables para evitar bloqueos por IP/CAPTCHA).
- LTranslate: Motor de traducción basado en LibreTranslate.
- ChatTranslator: Fachada de traducción y ciclo de vida de proveedores.
- CoreTranslator: Expansión PlaceholderAPI legacy `%cot_*`, Bukkit-only, *deprecated* y mantenida (ADR-035).
- Editor: Aplicación web estática (`src/web-editor/`) que actúa como compilador visual de configuraciones YAML.

Documentación:
- `/README.md`
- `/docs/README.md`
- `/docs/prompts/README.md` (índice de prompts y skills)
- `/docs/prompts/contexto/proyecto.md` (este archivo)
- `/docs/prompts/diseno/` (filosofía, iFlow, storage, manager, web editor, logger, chattranslator, metrics)
- `/docs/prompts/especificaciones/` (contratos de `commands.yml`, `extension.yml` y config schema)
- `/docs/workdir/PLAN.md` (estado y hoja de ruta)
- `/docs/workdir/ADR.md` (decisiones de arquitectura)
- `/docs/workdir/AUDITORIA.md` (auditoría vigente)
- `/src/README.md` (CodeGuide del código)

Repositorio:
- Proyecto bajo git con remoto GitHub: `origin` → git@github.com:majhrs16-official/TextFormatter-Suite.git, rama `main`.
- Web: https://github.com/majhrs16-official/TextFormatter-Suite
- El historial preserva el código eliminado (ej. trío monolítico: `git show 787b795:<ruta>`).
- Política: commits convencionales cortos en español agrupados por tema; el agente NO commitea ni pushea sin autorización explícita del usuario.
- Al cerrar cada sesión importante: verificar `git status`, informar qué queda sin commitear y recomendar commit+push (el trabajo existe solo en disco local hasta pushear).

Estado actual:
- El trío monolítico `/common`, `/spigot`, `/fabric-1.20.6` fue eliminado (2026-08-24). La paridad es funcional con el proyecto original ChatTranslator, no con código intermedio.
- Los hosts Spigot/Paper y Fabric existen como adaptadores; el Manager de módulos está implementado según ADR-019/ADR-027. El estado exacto y la deuda viven en `PLAN.md`.
- La suite no se ha validado con un E2E real en servidor. La prueba de Paper 1.20.6 fue un smoke test de carga, no un E2E.
- El Editor Web tiene base y tests; su alcance vigente está en `PLAN.md`.
- `CoreTranslator` se restauró como expansión PAPI legacy; su prueba real con Bukkit/Paper + PlaceholderAPI pertenece al E2E posterior a `1.0.0`.

Prácticas recomendadas:
- Leer el código y la documentación directamente; no fiarse de resúmenes ni de rutas citadas de memoria. Verificar que cada archivo referenciado existe antes de usarlo como evidencia.
- Usar sub-agentes en paralelo para investigación y validación, con instrucciones explícitas y sin delegar decisiones de arquitectura.
- Cada cambio deja el gate verde: código, tests, documentación y eliminación del camino viejo forman una sola unidad de trabajo.
- Un bug necesita test de regresión; una decisión de arquitectura necesita ADR.

Roadmap:
- El orden vigente es: purga P0 → W3.5 (paridad `.flow` v3/VM) → V6 (compilador C). No se inician features nuevas antes de cerrar la purga.
- La distribución inicial es el Manager como host; los motores son módulos-plugin separados que el Manager instala y selecciona resolviendo dependencias contra el entorno actual.
- Ideas diferidas (Fabric para mundos locales, Forge/Fabric 1.16.5, investigación legacy 1.0–1.7) no condicionan la arquitectura y solo se abordan cuando la integración principal esté cerrada. No son compromisos de release.

Manejo de Configuración y E/S (Disk/Cache Policy):
- Estrategia de lectura directa: Se delega el rendimiento de la E/S de archivos al Page Cache del Sistema Operativo en lugar de mantener un Watcher activo o sistemas de polling complejos.
- Prevención de latencia en la JVM: Aunque el archivo se consulte directamente en RAM, el plugin DEBE evitar el re-parsing (YAML/JSON) innecesario en cada evento. Se debe verificar si el archivo ha cambiado mediante la comprobación de timestamp (`lastModified()`) antes de reconstruir las estructuras de datos en memoria RAM, garantizando actualización instantánea tras guardar el archivo ("Guardar -> Aplicar") sin sobrecargar la CPU o el Garbage Collector.
- Todo `.yml` declara `config-version: 1.0` en la raíz, preferentemente al final. Si falta o no coincide, el parser falla con un error explícito; no se migra en runtime.

Protocolo de trabajo:
- Antes de tocar código o documentación, leer `docs/prompts/diseno/philosophy.md`, `docs/workdir/PLAN.md` y el ADR aplicable.
- No crear planes ni fases dentro de ADR; el estado y la hoja de ruta viven en `PLAN.md`.
- No modificar `AUDITORIA.md` para dejar notas de metodología: contiene la auditoría vigente y se reemplaza por completo al cerrar el ciclo.
