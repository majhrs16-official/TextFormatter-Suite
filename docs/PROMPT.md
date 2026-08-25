Proyecto actual: /home/majhrs16/Documentos/textformatter-suite + https://github.com/majhrs16-official/TextFormatter-Suite
Proyecto original: /home/majhrs16/Documentos/chattranslator + /home/majhrs16/Documentos/chattranslator.wiki + https://github.com/majhrs16-official/ChatTranslator

Objetivos:
- Extremadamente potente: Diseñado sobre componentes con responsabilidad atómica en instrucciones/configuración (sin implicar sintaxis de bajo nivel), permitiendo cualquier combinación compleja.
- Extremadamente personalizable/configurable: Ante múltiples opciones de diseño en la UI o parámetros de configuración para el usuario final, implementar ambas alternativas y permitir que la comunidad decida.
- Extremadamente modular: Todo componente desacoplable debe constituir un módulo/extensión independiente.
- Clean + Hexagonal Architecture real: Core agnóstico a la plataforma base.
- Reconstrucción moderna: Replantear el proyecto original con bases y prácticas modernas, manteniendo o superando sus resultados mediante una nueva sintaxis.
- Estándar empresarial: Arquitectura no bloqueante, altamente escalable (vertical y horizontalmente) con rendimiento optimizado tanto monohilo como en paralelo.

Reglas:
- Cero valores hardcodeados: Todo comportamiento debe parametrizarse mediante configuración.
- **Primero las bases**: Las BASES del sistema (Manager de módulos, SPI de descubrimiento de servicios, kernel en runtime, packaging/CI) van ANTES que las features — las features no son bases, y sin bases sólidas se construyen sobre arena. Diseño del Manager: FASE 5 en PROMPT_NOW.md.
- Sincronización del Prompt: Ante actualizaciones importantes del objetivo, actualizar este archivo (/docs/PROMPT.md) para mantener el contexto entre sesiones.

Ecosistema y Módulos (Suite):
El proyecto se concibe como una suite donde cada motor es una extensión descargable e instalable desde el Manager. Las actualizaciones de módulos requieren reiniciar el servidor; las reconfiguraciones de parámetros admiten Hot-Reload.
- iFlow: Router/firewall para eventos de texto basado en reglas (estilo iptables).
- TextFormatter: Motor principal de procesamiento de texto.
- GTranslate: Motor de traducción vía web scraping directo a Google Translate (incluye mitigadores como rotación de User-Agents y límites de peticiones configurables para evitar bloqueos por IP/CAPTCHA).
- GCloud: Adaptador para Google Cloud Translation API (desarrollado mediante interfaces/mocks para validación sin requerir credenciales activas).
- LTranslate: Motor de traducción basado en LibreTranslate.
- Editor: Aplicación web estática (GitHub Pages) que actúa como compilador visual de configuraciones YAML.

Documentación:
- /README.md
- /docs (PROMPT.md, PROMPT_NOW.md, ADR.md, PLAN.md, AUDITORIA.md)
- /docs/web-editor/

Repositorio:
- Proyecto bajo git con remoto GitHub: `origin` → git@github.com:majhrs16-official/TextFormatter-Suite.git, rama `main`.
- Web: https://github.com/majhrs16-official/TextFormatter-Suite
- El historial preserva el código eliminado (ej. trío monolítico: `git show 787b795:<ruta>`).
- Política: commits convencionales cortos en español agrupados por tema; el agente NO commitea ni pushea sin autorización explícita del usuario.
- Al cerrar cada sesión importante: verificar `git status`, informar qué queda sin commitear y recomendar commit+push (el trabajo existe solo en disco local hasta pushear).

Estado Actual y Advertencias:
- **El trío monolítico `/common`, `/spigot`, `/fabric-1.20.6` fue ELIMINADO** (2026-08-24): eran monolíticos, nunca se probaron en servidor y no deben existir como código. La retrocompatibilidad es FUNCIONAL con el proyecto original ChatTranslator (paridad de comportamiento), no con ningún código intermedio. Recuperable desde el historial git.
- La suite corre en Spigot mediante `suite/spigot-host` (plugin `TextFormatterSuite`, instalable, auditado; fat-jar construido). Pendiente: probar en servidor real.
- Pendiente: `fabric-host` (adapter Fabric), paridad funcional restante del original, Manager de módulos.
- Editor Web: base corregida y testeada (`npm run check` + integración en verde); pendiente ampliar opciones YAML para reglas complejas sin perder usabilidad.

Prácticas Recomendadas:
- Desplegar agentes para investigación profunda desde cero sobre Arquitectura Hexagonal, retrocompatibilidad funcional teórica con el proyecto original, auditoría de errores y búsqueda de mejoras.

Ideas Pendientes y Roadmap:
- Distribución: **1 solo jar inicial — el Manager** (host). Los motores son módulos-plugin separados que el Manager instala/selecciona, resolviendo dependencias CONTRA EL ENTORNO ACTUAL (no ciegamente contra latest; latest solo en instalación limpia). **Es una BASE: prioridad alta, antes que features** — diseño en FASE 5 de PROMPT_NOW.md.
- Compatibilidad del mod Fabric para clientes de Minecraft en mundos locales (Singleplayer / Open to LAN).
- Soporte para Forge y Fabric 1.16.5 únicamente cuando la integración principal (Spigot + Fabric) sea 100% funcional.
- Migración a CraftBukkit para maximizar la retrocompatibilidad base.
- Investigación Legacy (1.0 - 1.7): Exploración a futuro para reimplementar funciones faltantes (ej. Hover Events) mediante adaptadores de la arquitectura hexagonal, sin condicionar la arquitectura moderna, únicamente cuando la integración principal sea 100% funcional.

Manejo de Configuración y E/S (Disk/Cache Policy):
- Estrategia de lectura directa: Se delega el rendimiento de la E/S de archivos al Page Cache del Sistema Operativo en lugar de mantener un Watcher activo o sistemas de polling complejos.
- Prevención de latencia en la JVM: Aunque el archivo se consulte directamente en RAM, el plugin DEBE evitar el re-parsing (YAML/JSON) innecesario en cada evento. Se debe verificar si el archivo ha cambiado mediante la comprobación de timestamp (`lastModified()`) antes de reconstruir las estructuras de datos en memoria RAM, garantizando actualización instantánea tras guardar el archivo ("Guardar -> Aplicar") sin sobrecargar la CPU o el Garbage Collector.

PROMPT:
Análisis Profundo:
- Lee la documentación y comprende el código fuente leyéndolo directamente (evita atajos, suposiciones o resúmenes previos).
- Da continuidad fluida al trabajo realizado por el agente anterior.

Delegación y Paralelización: Utiliza **sub-agentes** en paralelo para optimizar la validación técnica:
- **Calidad de código:** Evalúa buenas prácticas, legibilidad y estándares.
- **Retrocompatibilidad:** Asegura compatibilidad total con el código original utilizando sintaxis moderna (prohibido recurrir a sintaxis obsoleta).
- **Arquitectura:** Valida estrictamente el cumplimiento de Clean Architecture y Arquitectura Hexagonal.

Plan de Acción Temporal (`docs/PROMPT_NOW.md`):
- Crea un archivo temporal en `docs/PROMPT_NOW.md` para volcar tu plan de acción a corto plazo y evitar perder el contexto.
- **Obligatorio:** Lee este archivo periódicamente en cada iteración importante para mantener la alineación con tus objetivos inmediatos.
