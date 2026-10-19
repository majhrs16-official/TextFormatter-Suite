# REINGENIERÍA DOCUMENTAL — README PRINCIPAL DE TEXTFORMATTER SUITE

## 1. Objetivo

Reestructura y mejora el archivo raíz `/README.md` de **TextFormatter Suite** para convertirlo en una presentación pública del proyecto: clara, profesional, atractiva, honesta y útil como primera impresión para alguien que descubre el repositorio por primera vez.

El README principal debe explicar qué es el proyecto, qué pretende conseguir, qué lo diferencia, qué funcionalidades ofrece realmente, en qué estado se encuentra y cómo empezar a explorarlo, instalarlo, compilarlo o contribuir a su desarrollo.

No quiero simplemente corregir la redacción del README existente ni añadir secciones sin criterio. Quiero revisar la organización de la documentación para que cada archivo tenga una responsabilidad clara y el README raíz actúe como punto de entrada al resto.

**La documentación debe reflejar el estado real del código y de las pruebas, no el estado ideal al que aspira el proyecto.**

No inventes funcionalidades, resultados de pruebas, compatibilidades, instrucciones de instalación ni garantías que no hayas verificado.

## 2. Inspección previa obligatoria

Antes de modificar archivos, inspecciona sistemáticamente el repositorio y construye una visión suficiente de su estado real.

Como mínimo, revisa:

- El `/README.md` actual.
- `/src/README.md`, respetando su función actual.
- `/docs/PLAN.md`.
- Los archivos Markdown principales de `/docs/wiki/`, identificando el documento que deba funcionar como entrada principal de la wiki.
- La estructura general de módulos y subproyectos.
- Los archivos de construcción y configuración de Gradle.
- Los puntos de entrada de las plataformas soportadas.
- Las funcionalidades implementadas y sus tests.
- Las funcionalidades incompletas, pendientes o documentadas pero no implementadas.
- Las instrucciones existentes de instalación, importación en IDE y compilación.
- Los archivos de contribución, convenciones y arquitectura que ya existan.
- Los enlaces internos y externos presentes en la documentación.

No deduzcas que una funcionalidad funciona simplemente porque existe una clase, una interfaz, un comando o una entrada en el plan.

Distingue entre implementación existente, implementación integrada, comportamiento probado y comportamiento pendiente de validación.

Si encuentras contradicciones entre el código, los tests, el README y el plan del proyecto, documéntalas y resuélvelas con base en evidencia verificable.

## 3. Filosofía editorial del README principal

El README raíz debe funcionar como una página de presentación y orientación, no como una especificación técnica exhaustiva.

Debe ser accesible para alguien que conoce Minecraft y sus plugins, pero que no necesariamente conoce la arquitectura interna de TextFormatter Suite.

La presentación debe transmitir con claridad:

- Qué es TextFormatter Suite.
- Qué problemas pretende resolver.
- Qué enfoque adopta para resolverlos.
- Qué lo hace distinto de una colección convencional de funcionalidades independientes.
- Qué partes están implementadas y qué partes siguen en desarrollo.
- Qué puede hacer actualmente una persona que quiera probarlo, estudiarlo o contribuir.
- Dónde encontrar los detalles técnicos.

Evita dos extremos:

1. Un README excesivamente técnico que obligue al visitante a comprender la arquitectura interna antes de entender el propósito del proyecto.
2. Un README publicitario que prometa capacidades, estabilidad o compatibilidad que el proyecto todavía no puede garantizar.

La documentación debe ser profesional sin perder la identidad técnica real del proyecto.

## 4. Estado del proyecto: advertencia visible

El README debe incluir, cerca del comienzo y de forma inequívoca, un subtítulo o aviso con este texto:

**Actualmente en desarrollo intenso — No apto para producción**

Debe quedar claro que el proyecto evoluciona activamente y que las APIs, funcionalidades, configuraciones o mecanismos internos pueden cambiar.

No presentes esta advertencia como una nota secundaria al final del documento.

Explica brevemente qué implica en la práctica: posibles cambios incompatibles, funcionalidades incompletas, documentación en evolución y ausencia de garantías de estabilidad que no hayan sido establecidas formalmente.

No exageres los riesgos ni inventes problemas concretos. La advertencia debe corresponderse con el estado real del repositorio.

## 5. Funcionalidades y estado de verificación

Incluye una sección que describa las capacidades del proyecto con información verificable.

Clasifica las funcionalidades utilizando categorías claramente diferenciadas, como:

- **Implementadas y probadas:** existe implementación integrada y evidencia de pruebas pertinentes.
- **Implementadas, pero no verificadas suficientemente:** existe código funcional en apariencia, pero faltan pruebas o validación suficiente para garantizar su comportamiento.
- **En desarrollo:** existe trabajo parcial o una implementación incompleta.
- **Pendientes:** están previstas, documentadas o identificadas como necesarias, pero todavía no están implementadas.
- **No verificadas en determinados entornos:** la funcionalidad existe, pero su compatibilidad con ciertas plataformas, versiones o configuraciones no se ha confirmado.

Adapta estas categorías al estado real del proyecto. No es obligatorio usar exactamente cinco si otras distinciones representan mejor la situación.

Cada afirmación importante debe tener respaldo en el código, los tests, la documentación o resultados de ejecución verificables.

No clasifiques automáticamente como probada una funcionalidad porque compile o porque tenga un test superficial. Tampoco clasifiques como incompleta una funcionalidad simplemente porque no tenga un test dedicado si existe otra evidencia sólida de su funcionamiento; explica el criterio utilizado.

Si resulta útil, vincula cada funcionalidad con el módulo responsable, su documentación o los tests correspondientes.

Evita que esta sección se convierta en una lista interminable de clases y métodos. El objetivo es informar sobre las capacidades del producto, no reproducir el árbol del código.

## 6. Enfoque, alcance y objetivos del proyecto

Explica qué pretende ser TextFormatter Suite y qué no pretende ser.

Describe su filosofía a partir de la arquitectura y las decisiones reales del repositorio, no de ideales genéricos.

La sección debe responder preguntas como:

- ¿Qué necesidad intenta resolver el proyecto?
- ¿Qué relación existe entre sus distintos módulos?
- ¿Qué busca centralizar o reutilizar?
- ¿Qué responsabilidades deben permanecer independientes?
- ¿Qué objetivos arquitectónicos orientan su evolución?
- ¿Qué problemas están expresamente fuera de su alcance?
- ¿Qué no debe interpretarse como una promesa de compatibilidad, estabilidad o funcionalidad?

Utiliza ejemplos concretos cuando ayuden a comprender el propósito, pero no presentes planes futuros como características actuales.

No inventes una filosofía de diseño que contradiga las decisiones arquitectónicas del código existente.

## 7. Catálogo de módulos

Incluye una sección que presente los módulos identificables del proyecto.

Para cada módulo relevante, indica brevemente:

- Nombre.
- Propósito.
- Funcionalidad que aporta.
- Estado de desarrollo o verificación, cuando sea significativo.
- Relación con otros módulos, si ayuda a entender el conjunto.
- Enlace a la documentación específica, si existe.

Prioriza una descripción orientada a las capacidades y responsabilidades de cada módulo, en lugar de copiar nombres de paquetes Java o rutas de clases.

Distingue los módulos existentes de los planificados. No incluyas como disponibles módulos que solo aparecen en un plan o diseño.

Si hay numerosos módulos, agrúpalos por responsabilidad para que el catálogo siga siendo fácil de recorrer.

## 8. Instalación y primeros pasos

Incluye una guía de inicio que sea suficientemente detallada para que un visitante pueda orientarse sin verse obligado a leer documentación interna desde el principio.

Antes de redactarla, determina cuál es el procedimiento de instalación que realmente admite el proyecto.

Distingue claramente entre:

- Instalación para utilizar el plugin, si existe una distribución utilizable.
- Instalación desde una compilación propia.
- Preparación del entorno para desarrollar.
- Ejecución de pruebas o tareas de verificación.

Documenta únicamente los procedimientos que puedas respaldar con los archivos de construcción, la distribución existente o una ejecución verificable.

Cuando corresponda, explica:

1. Requisitos previos.
2. Versiones de Java y herramientas necesarias.
3. Cómo obtener el código.
4. Cómo identificar el artefacto correcto.
5. Dónde debe colocarse y qué configuración necesita.
6. Cómo verificar que se ha cargado correctamente.
7. Qué limitaciones deben conocerse antes de probarlo.

No inventes enlaces de descarga, artefactos publicados, versiones estables, comandos ni requisitos.

Si el proyecto todavía no dispone de un procedimiento de instalación fiable para usuarios finales, dilo explícitamente y ofrece las instrucciones verificadas para compilarlo o ejecutarlo en un entorno de desarrollo.

Evita presentar una compilación local como una distribución oficial.

## 9. Importación y desarrollo en IDE

Incluye una sección para preparar el proyecto en un entorno de desarrollo.

### IntelliJ IDEA

Explica el procedimiento apropiado para importar el proyecto como un proyecto Gradle, cuando esa sea la estructura real del repositorio.

Incluye los pasos relevantes para:

- Abrir el proyecto desde la raíz correcta.
- Seleccionar una versión compatible del JDK.
- Importar y sincronizar Gradle.
- Esperar a que se resuelvan las dependencias.
- Identificar los módulos o subproyectos.
- Ejecutar las tareas pertinentes.
- Diagnosticar los problemas de importación más comunes que estén respaldados por la configuración del proyecto.

### Visual Studio Code

Explica cómo abrir la raíz del proyecto y preparar las extensiones o herramientas realmente necesarias para trabajar con Java y Gradle.

Incluye los pasos pertinentes para:

- Instalar o seleccionar el JDK requerido.
- Instalar las extensiones necesarias, si corresponde.
- Importar o sincronizar el proyecto.
- Identificar las tareas de Gradle.
- Ejecutar compilaciones y pruebas.
- Reconocer los problemas habituales de configuración del entorno.

No recomiendes extensiones como requisitos obligatorios si el proyecto puede utilizarse sin ellas.

### Desarrollo sin IDE

Incluye también el procedimiento de compilación y pruebas desde la terminal, sin depender de IntelliJ IDEA ni de Visual Studio Code.

Los comandos deben corresponder a las tareas reales definidas por el repositorio.

No inventes tareas Gradle ni asumas que todos los subproyectos admiten las mismas operaciones.

Si hay diferencias entre los comandos para compilar, probar, empaquetar o ejecutar, explícalas brevemente.

## 10. Compilación y verificación

Incluye una sección concisa con los comandos de compilación y pruebas más útiles.

Inspecciona `settings.gradle`, los archivos `build.gradle` y las tareas disponibles para determinar qué comandos corresponden al proyecto real.

Distingue entre:

- Compilación del proyecto completo.
- Compilación de un módulo concreto.
- Ejecución de tests.
- Generación de artefactos.
- Tareas adicionales de validación, si existen.

No declares que una tarea funciona si no has podido comprobarlo o verificar su definición y requisitos.

Si existen limitaciones conocidas en el proceso de construcción, documenta las que sean relevantes para una primera experiencia de desarrollo.

No conviertas el README en un manual completo de Gradle: ofrece los comandos habituales y enlaza a la documentación técnica para los casos avanzados.

## 11. Contribución al proyecto

Incluye una sección que explique cómo participar en el desarrollo.

Debe orientar a una persona que quiera:

- Investigar un problema.
- Corregir un error.
- Añadir una funcionalidad.
- Trabajar en un módulo.
- Mejorar los tests.
- Actualizar la documentación.
- Proponer cambios arquitectónicos.

Describe el flujo de contribución que realmente encaje con el repositorio y las prácticas existentes.

Si no existe una política formal de contribuciones, establece únicamente orientaciones conservadoras y compatibles con el proyecto. No inventes reglas de revisión, ramas protegidas, responsables, etiquetas ni procesos de publicación.

Cuando sea pertinente, explica cómo consultar el plan de trabajo, localizar el módulo responsable, ejecutar las pruebas y verificar los efectos de un cambio antes de proponerlo.

Incluye un enlace a `CONTRIBUTING.md` u otro documento equivalente solo si existe y es relevante. No crees referencias rotas para completar la sección.

## 12. Convenciones de desarrollo

Documenta las convenciones que un colaborador debería conocer antes de modificar el código.

Investiga las prácticas reales del repositorio, incluyendo cuando corresponda:

- Organización de módulos y subproyectos.
- Separación de responsabilidades.
- Convenciones de nombres.
- Uso de interfaces y abstracciones.
- Gestión de dependencias.
- Compatibilidad entre plataformas.
- Pruebas y validación.
- Documentación de APIs.
- Tratamiento de errores.
- Configuración y persistencia.
- Criterios para añadir nuevas funcionalidades.

Distingue entre convenciones que ya se aplican, objetivos arquitectónicos documentados y recomendaciones nuevas.

No presentes una recomendación como una regla histórica del proyecto si no existe evidencia de ello.

Si las convenciones son demasiado extensas para el README, resume los principios fundamentales y enlaza a un documento técnico específico.

## 13. Organización de la documentación

Revisa la documentación existente y convierte el README raíz en el punto de entrada hacia ella.

Como mínimo, evalúa los siguientes destinos:

- `/src/README.md`
- `/docs/PLAN.md`
- El documento principal o de entrada de `/docs/wiki/`
- La documentación de arquitectura.
- Las guías de instalación o desarrollo existentes.
- Los documentos de contribución y convenciones, si existen.

No asumas que el primer archivo alfabético de `/docs/wiki/` es necesariamente el principal. Inspecciona su contenido y determina cuál sirve mejor como entrada general.

### 13.1. Responsabilidad del README raíz

`/README.md` debe priorizar la presentación del proyecto, sus capacidades, su estado, la instalación, la contribución y los enlaces a documentación más especializada.

Debe ser suficientemente completo para funcionar como página principal sin reproducir toda la documentación técnica.

### 13.2. Responsabilidad de `/src/README.md`

`/src/README.md` ya está en uso y no debe reemplazarse ni vaciarse por conveniencia.

Inspecciona su contenido y conserva su finalidad actual.

Si contiene una guía de arquitectura, una explicación de los módulos o información técnica útil que resulte demasiado detallada para el README principal, mantenla en ese archivo cuando corresponda.

El README raíz puede enlazar a `/src/README.md` como índice técnico o punto de entrada a esa documentación.

### 13.3. Documentación técnica separada

Si la documentación técnica del README raíz necesita trasladarse, crea o utiliza un archivo específico dentro de `/src/`, con un nombre que describa su función real.

No utilices `/src/README.md` como destino de ese traslado, dado que ya tiene una responsabilidad propia.

Antes de crear un documento nuevo, comprueba si existe otro que cubra ya la misma materia. Evita duplicar documentación y establecer dos fuentes de verdad.

Cuando muevas contenido:

- Conserva la información técnica que siga siendo válida.
- Actualiza los enlaces entrantes y salientes afectados.
- Elimina las duplicaciones innecesarias.
- Mantén las rutas relativas correctas.
- Comprueba que los enlaces funcionan desde GitHub y desde las ubicaciones de los archivos.
- No elimines contenido útil sin justificarlo.

### 13.4. Enlaces de navegación

El README raíz debe incluir enlaces Markdown a los documentos relevantes, con una descripción breve de lo que aporta cada uno.

No te limites a listar nombres de archivos. El visitante debe saber por qué le conviene abrir cada documento.

Prioriza los enlaces realmente útiles y evita una tabla de contenidos documental excesivamente extensa.

## 14. Organización y presentación visual

La estructura final debe favorecer la lectura progresiva.

Puedes reorganizar las secciones propuestas según lo que resulte más natural para un visitante. No es obligatorio conservar el orden de este prompt.

Considera, entre otros elementos:

- Un título y una descripción inicial claros.
- La advertencia de desarrollo intenso en una posición visible.
- Una explicación breve del propósito.
- Un resumen de capacidades.
- El estado real de las funcionalidades.
- Un catálogo de módulos.
- Una guía de primeros pasos.
- Instrucciones de desarrollo y compilación.
- Información para colaboradores.
- Enlaces a la documentación técnica.

Utiliza Markdown de forma coherente: encabezados jerárquicos, listas legibles, tablas cuando realmente mejoren la comparación y bloques de código para los comandos.

Los badges solo deben incluirse si aportan información verificable, como un estado real de compilación o una licencia confirmada. No añadas badges decorativos que sugieran estabilidad, cobertura de tests o compatibilidad no demostradas.

No añadas imágenes, diagramas, capturas ni logotipos inventados. Si el repositorio ya contiene recursos visuales legítimos, evalúa si mejoran la presentación sin distraer del contenido.

El README debe poder leerse cómodamente en GitHub, tanto desde escritorio como desde dispositivos móviles.

## 15. Precisión, enlaces y mantenimiento

Toda la documentación modificada debe cumplir estas reglas:

- No afirmar que una funcionalidad está probada sin evidencia.
- No confundir una funcionalidad planificada con una implementada.
- No presentar versiones experimentales como estables.
- No inventar compatibilidades, requisitos, descargas o comandos.
- No crear enlaces a archivos inexistentes.
- No dejar enlaces relativos rotos tras mover contenido.
- No duplicar extensamente la misma explicación en varios archivos.
- No eliminar información técnica útil solo para acortar el README.
- No cambiar el código del producto para que coincida con una descripción editorial, salvo que se identifique y justifique un error real fuera del alcance documental.
- No modificar el comportamiento de la aplicación como parte de esta tarea, salvo que sea estrictamente necesario para corregir una referencia documental verificablemente incorrecta y se justifique expresamente.

Cuando no puedas verificar una afirmación, omítela, márcala como no verificada o enlaza a la fuente que permita investigarla.

## 16. Validación obligatoria

Después de editar la documentación:

1. Revisa el README raíz completo como si fueras un usuario nuevo.
2. Comprueba que el propósito del proyecto se entiende sin conocer su arquitectura interna.
3. Verifica que la advertencia de desarrollo intenso es visible.
4. Contrasta las afirmaciones sobre funcionalidades con el código, los tests y el plan.
5. Verifica que las instrucciones de instalación corresponden a capacidades reales.
6. Comprueba los comandos de compilación y pruebas contra las tareas de Gradle existentes.
7. Comprueba los enlaces internos y las rutas relativas.
8. Comprueba que `/src/README.md` conserva su responsabilidad original.
9. Comprueba que no se ha duplicado innecesariamente la documentación técnica.
10. Comprueba que los archivos Markdown modificados conservan una estructura válida y legible.
11. Ejecuta las verificaciones automatizables disponibles para los enlaces y la documentación.
12. Revisa el diff completo para asegurarte de que los cambios se limitan al objetivo documental.

Si alguna verificación no puede ejecutarse, indícalo en el informe final. No afirmes que se ha validado algo que solo se ha revisado visualmente.

## 17. Criterios de aceptación

El trabajo se considera terminado cuando:

1. `/README.md` presenta el proyecto de forma clara, profesional y comprensible.
2. Incluye el aviso **«Actualmente en desarrollo intenso — No apto para producción»** en una posición visible.
3. Las funcionalidades se describen con estados de verificación honestos y respaldados por evidencia.
4. Se explica el propósito, el enfoque, los objetivos y los límites del proyecto.
5. Existe un catálogo comprensible de los módulos reales.
6. Las instrucciones de instalación distinguen entre el uso del producto y la preparación de un entorno de desarrollo.
7. Se documentan la importación en IntelliJ IDEA y Visual Studio Code, además de la compilación desde la terminal, en la medida que permita verificarlo el repositorio.
8. Se explica cómo contribuir y dónde consultar las convenciones.
9. `/src/README.md` conserva su responsabilidad y puede enlazarse desde el README raíz.
10. La documentación técnica extensa se encuentra en archivos especializados cuando corresponda.
11. Los enlaces a `docs/PLAN.md`, la wiki y otros documentos son válidos y útiles.
12. No existen duplicaciones documentales innecesarias ni enlaces rotos introducidos por los cambios.
13. No se han inventado capacidades, resultados de pruebas, requisitos ni procedimientos.
14. El diff final se limita a la reorganización y mejora documental solicitada, salvo las correcciones justificadas expresamente.

## 18. Forma de trabajo y entrega

Trabaja directamente sobre el repositorio.

Sigue esta secuencia:

1. Inspecciona la documentación y la estructura del proyecto.
2. Identifica el estado real de las funcionalidades y las fuentes que respaldan las afirmaciones.
3. Define la organización documental y las responsabilidades de cada archivo.
4. Reescribe `/README.md` como página principal del proyecto.
5. Reubica o reorganiza la documentación técnica cuando sea necesario, sin apropiarte de `/src/README.md`.
6. Actualiza los enlaces y las referencias afectadas.
7. Valida el contenido, las rutas, los comandos documentados y el diff final.
8. Corrige los problemas encontrados.

No te detengas en una propuesta de estructura ni entregues únicamente un borrador en la conversación. Implementa los cambios en los archivos del repositorio.

Al finalizar, entrega un informe conciso pero verificable que incluya:

- Archivos creados, modificados y movidos.
- Responsabilidad final de cada documento relevante.
- Principales secciones incorporadas al README.
- Criterios utilizados para clasificar las funcionalidades.
- Comandos de instalación, compilación o pruebas que se hayan verificado.
- Enlaces y referencias comprobados.
- Validaciones ejecutadas y sus resultados.
- Información que no pudo verificarse y que, por tanto, no se presenta como un hecho.

**El resultado debe funcionar como una buena primera impresión de TextFormatter Suite y, al mismo tiempo, como una guía honesta para saber qué es el proyecto hoy, cómo explorarlo y cómo participar en su desarrollo.**
