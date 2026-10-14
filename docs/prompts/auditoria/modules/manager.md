# AUDITORÍA TÉCNICA EXHAUSTIVA DEL MODULE MANAGER — TEXTFORMATTER SUITE

## 1. Objetivo

Realiza una auditoría técnica, arquitectónica y funcional exhaustiva del módulo **Manager** de TextFormatter Suite, utilizando como especificación de referencia el documento **«REINGENIERÍA COMPLETA DEL MODULE MANAGER — TEXTFORMATTER SUITE»** proporcionado a continuación.

Repositorio: https://github.com/majhrs16-official/TextFormatter-Suite

**El objetivo es evaluar la implementación existente, no modificarla.** Determina si el Manager actual cumple realmente la especificación, qué partes están bien diseñadas e implementadas, qué funcionalidades faltan, qué errores existen, qué comportamientos son inesperados y qué comportamientos esperados están correctamente garantizados.

No implementes el nuevo manager, no refactorices código, no modifiques archivos y no conviertas la auditoría en un plan de implementación. El resultado debe ser un diagnóstico fundamentado en el código real del repositorio.

La especificación de reingeniería constituye el contrato funcional y arquitectónico contra el que se debe evaluar el sistema actual. Sin embargo, distingue entre:

- Incumplimientos explícitos de la especificación.
- Errores reales de implementación.
- Defectos arquitectónicos que dificultan o impiden el comportamiento requerido.
- Riesgos de seguridad e integridad.
- Limitaciones técnicas legítimas de la arquitectura existente.
- Decisiones de diseño que son correctas y conviene conservar.
- Funcionalidades que todavía no existen y no deben presentarse como implementadas.

No confundas la ausencia de una funcionalidad con un bug de una funcionalidad existente. Ambas pueden constituir incumplimientos de la especificación, pero deben clasificarse por separado.

## 2. Regla fundamental: inspeccionar antes de concluir

Antes de emitir cualquier conclusión, inspecciona sistemáticamente el repositorio real.

No bases la auditoría únicamente en el README, los nombres de las clases, los comentarios, los documentos de planificación ni la estructura de directorios. Debes seguir el flujo de ejecución y verificar cómo se comporta realmente el código.

Inspecciona, como mínimo:

1. El árbol completo de archivos relevantes.
2. La implementación actual del Manager y todos sus subcomponentes.
3. Los modelos de módulos, paquetes, dependencias y compatibilidad.
4. Las interfaces públicas y sus consumidores.
5. La configuración y los mecanismos de persistencia.
6. Los parsers y serializadores.
7. Los mecanismos de descarga y validación de artefactos.
8. La instalación, actualización y eliminación.
9. La resolución de dependencias.
10. El sistema de comandos y sus permisos.
11. Los puntos de entrada de Bukkit, Fabric, Forge u otras plataformas realmente soportadas.
12. Los mecanismos de carga, activación y desactivación.
13. El sistema de construcción, los módulos Gradle, las dependencias y las tareas disponibles.
14. Los tests existentes, sus fixtures y su cobertura efectiva.
15. La documentación y el empaquetado del proyecto.
16. El historial de Git cuando sea útil para determinar regresiones, implementaciones incompletas o cambios de contrato.

Identifica primero los archivos concretos que forman el Manager y después sigue sus dependencias y consumidores. Amplía la inspección cuando una clase invoque código externo al módulo que pueda alterar el resultado.

No afirmes haber inspeccionado todo el repositorio si no has podido acceder a todos los archivos pertinentes. Si el acceso al repositorio, a su historial o a la ejecución de tests está limitado, indícalo explícitamente y ajusta el alcance de las conclusiones.

## 3. Modelo de arquitectura actual

Reconstruye el diseño real del Manager antes de compararlo con la especificación.

Documenta:

- Qué componentes existen y cuáles son sus responsabilidades reales.
- Cómo se inicializa el Manager.
- Quién construye sus objetos y servicios.
- Qué dependencias necesita para funcionar.
- Qué datos mantiene en memoria.
- Qué datos persiste en disco.
- Cómo se relaciona con los módulos de TextFormatter Suite.
- Cómo se conecta con los puntos de entrada de cada plataforma.
- Qué interfaces y contratos públicos expone.
- Qué componentes dependen directamente de Bukkit, Fabric, Forge u otros adaptadores.
- Qué operaciones pueden ejecutarse sin conexión.
- Qué operaciones dependen de Internet.
- Qué mecanismos de sincronización, concurrencia y recuperación existen.
- Qué partes están terminadas, cuáles son parciales y cuáles son únicamente esqueletos.

Representa el flujo real de las operaciones principales, desde el comando o punto de entrada hasta la modificación del estado, pasando por las validaciones y los servicios involucrados.

Diferencia las dependencias reales de las dependencias que solamente parecen existir por nombres, comentarios o documentación.

### Restricción arquitectónica importante

Comprueba expresamente el papel de `Module.java` y de cualquier bootstrap relacionado. Si `Module.java` funciona como descriptor del grafo de módulos y dependencias, verifica que el Manager no lo trate incorrectamente como una clase de servicio que debe instanciarse reflectivamente.

No des por supuesto que existe un problema: demuestra si sucede, en qué ruta de ejecución y con qué consecuencias.

## 4. Comparación con la especificación de reingeniería

Utiliza el documento completo **«REINGENIERÍA COMPLETA DEL MODULE MANAGER — TEXTFORMATTER SUITE»** como especificación normativa.

Evalúa individualmente sus secciones y requisitos. No reduzcas el análisis a una lista superficial de funcionalidades.

Para cada requisito, determina:

- Si está implementado.
- Si la implementación es correcta.
- Si está integrado en el flujo real de ejecución.
- Si existen condiciones bajo las que deja de funcionar.
- Si su comportamiento está probado.
- Si existen contradicciones con otros componentes.
- Si su implementación es segura y coherente con el resto del sistema.

Utiliza las siguientes clasificaciones:

| Estado | Significado |
|---|---|
| CUMPLIDO | Implementación presente, correcta e integrada; existe evidencia suficiente. |
| PARCIAL | Implementación presente, pero incompleta o con limitaciones relevantes. |
| AUSENTE | La funcionalidad requerida no existe. |
| INCORRECTO | Existe una implementación, pero incumple el contrato o produce resultados incorrectos. |
| REGRESIÓN | Un cambio identificable ha roto un comportamiento anteriormente funcional. |
| NO VERIFICADO | No existe evidencia suficiente para confirmar el comportamiento. |
| NO APLICABLE | El requisito no corresponde a las capacidades o al alcance real del proyecto; justifica el motivo. |

No uses CUMPLIDO basándote exclusivamente en que existe una clase o un método con el nombre esperado.

### 4.1. Separación APT/dpkg

Comprueba si existe una separación funcional y verificable entre:

**Gestión de repositorios y adquisición, equivalente a APT:**

- Configuración de repositorios.
- Descarga y almacenamiento de índices.
- Actualización de índices.
- Selección de candidatos.
- Resolución de dependencias.
- Planificación de operaciones.
- Descarga y verificación de artefactos.

**Gestión local, equivalente a dpkg:**

- Instalación desde un ZIP local.
- Validación del paquete.
- Registro del estado instalado.
- Inventario de archivos.
- Eliminación segura.
- Gestión de dependencias inversas.
- Recuperación y consistencia del estado local.

Determina si estas responsabilidades están realmente separadas, si se encuentran mezcladas o si únicamente existe una separación nominal entre clases.

Verifica que APT no sea la fuente de verdad sobre los paquetes instalados y que las operaciones locales no dependan innecesariamente de la disponibilidad de los repositorios.

### 4.2. Configuración, índices y metadatos

Examina los contratos reales de:

- `repositories.yml`.
- `packages.list`.
- `METADATA/package.list`.
- `status`.

Si alguno no existe, documenta la ausencia en lugar de inventar su contenido.

Para cada formato presente, comprueba:

- Gramática exacta.
- Campos obligatorios y opcionales.
- Separadores.
- Valores vacíos.
- Reglas de escape.
- Validación de tipos y rangos.
- Detección de duplicados.
- Tratamiento de entradas desconocidas.
- Manejo de errores.
- Compatibilidad entre versiones del formato.
- Coherencia entre productores y consumidores.

En `METADATA/package.list`, verifica especialmente que la cabecera describa el paquete actual y no se descarte incorrectamente como separador de registros.

Comprueba también la semántica de `Name`, `Version`, `Channel`, `Type`, `Dependencies`, `Arch`, `Platform`, `URL`, `OS` y `SHA256`.

No consideres que un campo está implementado funcionalmente porque se pueda parsear: comprueba si interviene en las decisiones que le corresponden.

### 4.3. Versiones y compatibilidad

Audita:

- Comparación de versiones.
- Restricciones de versiones.
- Selección de canales.
- Preferencias entre repositorios.
- Compatibilidad de arquitectura.
- Compatibilidad del sistema operativo.
- Compatibilidad de plataforma.
- Tratamiento de `none`.
- Casos de compatibilidad desconocida.
- Determinismo en la selección de candidatos.

Busca específicamente comparaciones lexicográficas incorrectas, selección accidental por orden de iteración, incompatibilidades ignoradas y versiones instaladas que se acepten indebidamente.

### 4.4. Resolver de dependencias

Comprueba si el resolver implementa realmente un grafo de dependencias o si se limita a buscar paquetes individualmente.

Analiza:

- Dependencias directas y transitivas.
- Restricciones de versiones.
- Reutilización de dependencias instaladas.
- Dependencias compartidas.
- Ciclos.
- Dependencias ausentes.
- Conflictos entre versiones.
- Conflictos entre archivos.
- Compatibilidad de todos los candidatos.
- Selección entre múltiples repositorios.
- Duplicación de descargas o instalaciones.
- Determinismo.
- Diagnósticos de resolución fallida.
- Generación de un plan previo a la modificación del estado.

Determina si el sistema puede resolver correctamente una operación que involucre varios paquetes y si un fallo en una dependencia puede dejar parcialmente instalado el conjunto solicitado.

No des por hecho que un resolver es correcto porque encuentre una solución para un único paquete sin dependencias.

### 4.5. Descargas e integridad

Inspecciona el flujo completo de descarga.

Verifica:

- Validación de URLs y protocolos.
- Respuestas HTTP.
- Códigos de error.
- Descargas incompletas.
- Límites de tamaño.
- Comprobación del tamaño exacto del ZIP.
- Verificación de SHA-256.
- Gestión de temporales.
- Limpieza tras errores.
- Archivos preexistentes.
- Reintentos y concurrencia, si existen.
- Correspondencia entre índice, metadatos y artefacto.
- Riesgos de que una descarga fallida se interprete como válida.

Comprueba que el hash se calcule sobre los bytes correctos y en el momento adecuado.

Distingue integridad frente a un hash esperado de autenticación del editor: no atribuyas protección contra un repositorio malicioso si no existe un mecanismo que permita autenticar el origen de confianza.

### 4.6. Seguridad del ZIP y del inventario

Inspecciona cómo se enumeran, validan, extraen, instalan y eliminan los archivos.

Busca:

- ZIP Slip.
- Path traversal.
- Rutas absolutas.
- Separadores alternativos.
- Rutas ambiguas.
- Entradas duplicadas.
- Colisiones de nombres.
- Enlaces simbólicos u otras entradas especiales.
- Archivos que escapen del directorio autorizado.
- Bombas de descompresión.
- Archivos excesivamente grandes.
- Inconsistencias entre el inventario declarado y el contenido real.
- Sobrescritura de archivos ajenos.
- Eliminación de archivos compartidos.
- Manipulación local de archivos administrados.

Determina si las rutas se normalizan y validan antes de utilizarlas, y si las comprobaciones siguen siendo válidas frente a enlaces simbólicos y cambios concurrentes cuando corresponda.

No declares una vulnerabilidad explotable sin explicar las condiciones necesarias y el flujo de código que la permite.

### 4.7. Estado local y transacciones

Examina la implementación de `status` o el mecanismo equivalente que exista realmente.

Determina:

- Qué información se persiste.
- Si el estado instalado se puede reconstruir sin conexión.
- Si las escrituras son atómicas.
- Qué sucede si el proceso se interrumpe durante una operación.
- Cómo se detecta un registro corrupto.
- Cómo se recuperan operaciones incompletas.
- Si el estado en memoria puede divergir del estado en disco.
- Si existen bloqueos frente a operaciones concurrentes.
- Si una operación multi-paquete puede dejar instalaciones parciales.
- Si las eliminaciones son recuperables.
- Si se distingue entre instalado y activado.

Comprueba las garantías reales de las operaciones sobre el sistema de archivos. No confundas escribir un archivo temporal y renombrarlo con disponer de una transacción atómica completa para todos los archivos y paquetes afectados.

### 4.8. Dependencias compartidas y eliminación

Verifica cómo se representa la propiedad de los archivos y la relación entre paquetes consumidores y dependencias.

Comprueba:

- Si una dependencia compartida se instala una sola vez cuando corresponde.
- Si pueden coexistir versiones diferentes.
- Si el sistema detecta cuando no puede soportar esa coexistencia.
- Si la eliminación comprueba las dependencias inversas.
- Si se conservan las dependencias todavía necesarias.
- Si existen referencias obsoletas.
- Si las dependencias huérfanas se identifican de forma segura.
- Si un paquete puede borrar archivos propiedad de otro.
- Si la eliminación detecta modificaciones locales.
- Si el sistema puede mantener un estado coherente tras un fallo.

Distingue entre un comportamiento deliberadamente no soportado y un comportamiento que parece soportado, pero falla silenciosamente.

### 4.9. Integración con el runtime y las plataformas

Inspecciona los puntos de entrada y adaptadores reales de las plataformas soportadas.

Comprueba:

- Qué significa instalar un paquete.
- Dónde se almacenan los artefactos.
- Si se confunde instalación con activación.
- Si existe un contrato de ciclo de vida.
- Si se intenta cargar un JAR únicamente por estar instalado.
- Si se respetan los puntos de entrada existentes.
- Si el Manager está acoplado a APIs de plataforma sin necesidad.
- Si se preserva el modelo de módulos y dependencias de TxF.
- Si se exige reiniciar para determinadas operaciones.
- Si una actualización puede dejar código antiguo activo con archivos nuevos en disco.
- Si se gestionan correctamente listeners, tareas, threads y recursos durante la desactivación.

No presupongas que Bukkit, Fabric y Forge tienen idénticas capacidades de carga dinámica o descarga en caliente.

Si no existe un contrato de activación, determina las consecuencias reales y el alcance de la limitación.

### 4.10. Comandos y experiencia operativa

Audita los comandos existentes, no solamente los comandos que la especificación desea introducir.

Para cada comando, comprueba:

- Registro.
- Permisos.
- Validación de argumentos.
- Autocompletado.
- Comportamiento en consola y dentro del juego.
- Errores y mensajes.
- Manejo de operaciones asíncronas.
- Confirmación de éxito.
- Coherencia entre la respuesta del comando y el estado real.

Contrasta sus contratos con las operaciones requeridas:

- `/suite update`
- `/suite install`
- `/suite remove`
- `/suite list`
- `/suite files`
- `/suite dpkg -i`
- `/suite dpkg -r`

Identifica los comandos ausentes, incompletos o con semántica distinta. No inventes que existen rutas de comandos que no hayas encontrado.

## 5. Análisis de bugs y comportamientos

Identifica individualmente los defectos demostrables.

Cada hallazgo debe incluir:

1. **Identificador:** `MGR-001`, `MGR-002`, etc.
2. **Título:** descripción breve y precisa.
3. **Severidad:** crítica, alta, media, baja o informativa.
4. **Categoría:** bug funcional, seguridad, integridad, arquitectura, concurrencia, compatibilidad, mantenimiento o incumplimiento de especificación.
5. **Estado:** confirmado, probable o pendiente de reproducir.
6. **Ubicación:** archivo, clase, método y líneas exactas cuando estén disponibles.
7. **Evidencia:** fragmento relevante o descripción precisa del flujo de ejecución.
8. **Precondiciones:** qué debe ocurrir para activar el problema.
9. **Comportamiento actual:** qué hace realmente el código.
10. **Comportamiento esperado:** qué debería ocurrir según el contrato aplicable.
11. **Consecuencia:** corrupción de estado, fallo de instalación, pérdida de archivos, incompatibilidad, bloqueo, falso éxito u otra consecuencia concreta.
12. **Reproducción:** pasos o escenario mínimo para observarlo.
13. **Confianza:** fundamento de la conclusión y cualquier incertidumbre relevante.

Prioriza la severidad según el impacto real, no según lo llamativo del nombre.

Diferencia explícitamente:

- **Bug confirmado:** la implementación contiene una ruta defectuosa demostrable.
- **Bug reproducido:** además se ha observado el fallo mediante una prueba o ejecución.
- **Riesgo:** existe una condición peligrosa, pero no se ha demostrado una explotación o fallo efectivo.
- **Limitación:** el comportamiento no está soportado o requiere condiciones que la implementación no garantiza.
- **Funcionalidad ausente:** no existe la capacidad solicitada.
- **Sospecha:** hay indicios insuficientes para afirmar un defecto.

No presentes una sospecha como un bug confirmado.

### Comportamientos inesperados

Busca comportamientos que no necesariamente produzcan una excepción, pero que violen las expectativas del operador, por ejemplo:

- Una operación informa éxito antes de terminar.
- Un paquete se considera instalado sin estarlo completamente.
- Se selecciona una versión incompatible.
- Un fallo deja archivos residuales.
- Una eliminación borra más archivos de los autorizados.
- Una dependencia compartida se elimina prematuramente.
- Una instalación aparentemente correcta no se activa.
- El estado en memoria difiere de `status`.
- Una actualización de índices modifica indebidamente el estado instalado.
- El resultado cambia según el orden accidental de los repositorios.

Estos son ejemplos de escenarios a investigar, no afirmaciones sobre el código actual.

## 6. Qué está bien implementado

No conviertas la auditoría en una lista exclusivamente negativa.

Identifica también los componentes que están correctamente diseñados e implementados, aunque el sistema global no cumpla toda la especificación.

Para cada aspecto positivo, explica:

- Qué responsabilidad cumple.
- Qué código lo implementa.
- Qué propiedades están garantizadas.
- Qué pruebas o evidencias lo respaldan.
- Qué partes de su diseño merecen conservarse conceptualmente.

Distingue una buena decisión arquitectónica de una implementación efectivamente correcta.

Si existe un parser robusto, una interfaz bien desacoplada, una validación correcta o un mecanismo de persistencia fiable, reconócelo con evidencia. No propongas reemplazar indiscriminadamente componentes que ya cumplen su contrato.

## 7. Evaluación de pruebas y compilación

Examina los tests existentes y determina qué verifican realmente.

Comprueba si las pruebas cubren:

- Casos normales.
- Entradas inválidas.
- Errores de red.
- Fallos de instalación.
- Integridad de artefactos.
- Dependencias transitivas.
- Ciclos y conflictos.
- Dependencias compartidas.
- Recuperación de operaciones.
- Estado corrupto.
- Eliminación segura.
- Compatibilidad de plataformas.
- Fallos de activación.

Cuando sea posible, ejecuta los tests relevantes y los comandos de compilación correspondientes al Manager.

Prioriza pruebas deterministas, aisladas y sin necesidad de acceso a repositorios externos. No modifiques el código de producción para hacer pasar una prueba durante esta auditoría.

Registra:

- Comando exacto.
- Resultado real.
- Errores observados.
- Tests omitidos y motivo.
- Targets que no pudieron compilarse.
- Diferencia entre un fallo del Manager y un fallo preexistente del entorno o de otra parte del proyecto.

No afirmes que los tests pasan si no los has ejecutado. No inventes resultados de compilación ni cobertura.

Si la infraestructura lo permite y el entorno es seguro, utiliza pruebas temporales aisladas para reproducir defectos, sin modificar los archivos versionados.

## 8. Matriz de cumplimiento

Construye una matriz de trazabilidad entre los requisitos de la especificación y la implementación actual.

Incluye al menos las siguientes áreas:

| Área | Qué evaluar |
|---|---|
| Arquitectura | Separación de responsabilidades e integración con TxF |
| APT | Repositorios, índices, candidatos, descargas y planificación |
| dpkg | Instalación local, eliminación y estado instalado |
| Configuración | `repositories.yml` y `config.yml` |
| Índices | `packages.list` y validación de registros |
| Metadatos | `METADATA/package.list` y todos sus campos |
| Versiones | Comparación, restricciones y selección de candidatos |
| Compatibilidad | Arquitectura, sistema operativo y plataforma |
| Dependencias | Resolución, ciclos, conflictos y transitividad |
| Almacenamiento | `tmp/`, `lists/`, `status` y `library/` |
| Integridad | Tamaño, SHA-256 y coherencia de artefactos |
| Inventario | Propiedad de archivos y validación de rutas |
| Transacciones | Preparación, confirmación, rollback y recuperación |
| Eliminación | Dependencias inversas y archivos compartidos |
| Runtime | Instalación, activación y desactivación |
| Comandos | Contratos, permisos y diagnósticos |
| Seguridad | ZIP Slip, entradas maliciosas y concurrencia |
| Pruebas | Cobertura efectiva y reproducibilidad |
| Distribución | Construcción, empaquetado y compatibilidad |

Para cada área incluye:

- Estado.
- Evidencia concreta.
- Archivos implicados.
- Defectos encontrados.
- Requisitos pendientes.
- Nivel de confianza.

Cuando sea útil, divide una misma área en requisitos atómicos. No marques toda una sección como cumplida porque uno de sus componentes funcione.

## 9. Distinguir el problema arquitectónico de los problemas locales

Determina si las deficiencias detectadas proceden de:

- Un diseño incompatible con la especificación.
- Una arquitectura razonable con implementación incompleta.
- Bugs localizados.
- Contratos inconsistentes entre componentes.
- Integración incompleta.
- Ausencia de pruebas.
- Limitaciones de la plataforma.
- Documentación desactualizada.

Explica cuáles son las causas raíz y cuáles son consecuencias de otros defectos.

Busca dependencias circulares, duplicación de responsabilidades, múltiples fuentes de verdad, APIs redundantes, clases sin consumidores, rutas de ejecución alternativas incompatibles y estados que puedan divergir.

No recomiendes una reescritura total únicamente porque la especificación la solicita. La decisión sobre qué debe descartarse debe estar sustentada en el grado de incompatibilidad del diseño actual con el contrato requerido.

Del mismo modo, no recomiendes conservar el diseño actual si sus responsabilidades fundamentales impiden cumplir los requisitos.

## 10. Informe final obligatorio

Entrega el informe en el siguiente orden.

### A. Veredicto ejecutivo

Resume:

- Estado técnico actual del Manager.
- Grado de cumplimiento de la especificación.
- Principales defectos.
- Riesgos de mayor impacto.
- Calidad de la arquitectura existente.
- Si la implementación actual es correcta para el comportamiento que pretende ofrecer.
- Si puede evolucionar sobre sus componentes actuales o si existen incompatibilidades estructurales que justifican reemplazarlos.

No utilices un porcentaje global de cumplimiento sin explicar cómo se ha calculado y qué requisitos se han evaluado.

### B. Mapa de arquitectura real

Describe los componentes existentes, sus responsabilidades, dependencias y flujos de ejecución.

Incluye un diagrama Mermaid si ayuda a mostrar el flujo real de una instalación, una actualización y una eliminación.

El diagrama debe representar la implementación encontrada, no la arquitectura deseada.

### C. Inventario de funcionalidades

Clasifica las funcionalidades existentes en:

- Correctas y verificadas.
- Parciales.
- Incorrectas.
- Ausentes.
- No verificadas.

Incluye las referencias a los archivos correspondientes.

### D. Hallazgos técnicos

Presenta todos los hallazgos relevantes, ordenados por severidad y con la evidencia exigida en la sección 5.

No dupliques el mismo defecto bajo varios identificadores. Si un problema afecta a varios requisitos, utiliza un hallazgo principal y referencia las consecuencias relacionadas.

### E. Comportamientos esperados frente a comportamientos reales

Incluye una comparación concreta de operaciones como instalación, actualización de índices, resolución de dependencias, eliminación y recuperación tras fallos.

Separa los comportamientos garantizados, los condicionados, los accidentales y los no soportados.

### F. Aspectos correctos que deben preservarse

Identifica las implementaciones sólidas y explica por qué son correctas.

### G. Matriz de cumplimiento

Incluye los requisitos de la especificación, su estado y las pruebas o referencias que justifican cada conclusión.

### H. Resultados de pruebas y compilación

Enumera los comandos ejecutados, los resultados reales, los errores y las limitaciones de verificación.

### I. Diagnóstico arquitectónico

Determina qué componentes son compatibles con el modelo APT/dpkg, cuáles requieren corrección localizada y cuáles presentan incompatibilidades estructurales.

Explica las razones técnicas. No implementes ni redactes todavía el plan de reingeniería.

### J. Conclusión

Responde de manera explícita:

1. ¿El Manager actual hace realmente lo que aparenta hacer?
2. ¿Qué operaciones funcionan correctamente y bajo qué condiciones?
3. ¿Cuáles son los bugs más importantes?
4. ¿Qué comportamientos inesperados puede observar un usuario?
5. ¿Qué requisitos de la especificación no están implementados?
6. ¿Qué componentes actuales son técnicamente sólidos?
7. ¿Qué problemas impiden considerar el Manager fiable?
8. ¿Qué evidencias faltan para llegar a una conclusión definitiva?

## 11. Reglas de rigor

- No modifiques el repositorio.
- No implementes la reingeniería.
- No inventes clases, métodos, archivos, APIs ni capacidades.
- No atribuyas comportamientos a un componente sin seguir su flujo de ejecución.
- No confundas comentarios con garantías de implementación.
- No confundas existencia de código con funcionamiento correcto.
- No confundas ausencia de tests con prueba de un bug.
- No confundas falta de acceso con ausencia de funcionalidad.
- No presentes limitaciones deliberadas como defectos de seguridad.
- No ocultes resultados negativos de compilación o pruebas.
- No afirmes que un requisito está cumplido sin evidencia suficiente.
- No uses documentación externa para sustituir la inspección del código del repositorio.
- No inventes repositorios remotos ni datos de distribución.
- Cita rutas y líneas concretas siempre que sea posible.
- Si una conclusión depende de un supuesto, identifica el supuesto.
- Si el código no permite determinar el comportamiento, marca el resultado como NO VERIFICADO.
- Si encuentras un problema que contradice una conclusión anterior, corrige la conclusión y explica la discrepancia.

**Resultado esperado:** una auditoría que permita conocer con precisión qué hace hoy el Module Manager, qué hace correctamente, qué hace mal, qué no hace y qué evidencia demuestra cada afirmación. La auditoría debe ser suficientemente rigurosa para servir como base técnica para decidir la posterior reingeniería completa del módulo.
