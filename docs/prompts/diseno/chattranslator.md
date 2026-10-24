Actúa como un arquitecto de software experto en diseño modular, sistemas desacoplados y gestión de dependencias (Inversion of Control / Dependency Injection). Necesito que diseñes y documentes la arquitectura e implementación inicial del nuevo módulo central: "ChatTranslator".

### 1. El Por Qué y El Para Qué (Justificación Arquitectónica)
Actualmente, el ecosistema cuenta con implementaciones de traducción individuales y acopladas (como `gtranslate`, `ltranslate`, u otros futuros backends). Esto genera duplicidad en la interfaz de usuario, configuración dispersa y falta de estandarización.
- **Por qué:** Necesitamos un punto de entrada único y unificado para cualquier funcionalidad de traducción en la suite, separando la interfaz (frontend) de los motores de procesamiento (backends).
- **Para Qué:** 
  - Permitir que el usuario configure un proveedor global de traducción de forma sencilla mediante un archivo `.yml` o que los desarrolladores puedan forzar un backend específico mediante flags de contexto en tiempo de ejecución.
  - Asegurar que agregar un nuevo backend de traducción en el futuro sea tan simple como implementar una interfaz estandarizada, sin tocar la lógica principal de la aplicación.
  - Resolver la jerarquía de dependencias en el gestor de módulos (`manager`), garantizando que los backends dependan del frontend y nunca al revés.

---

### 2. El Cómo (Diseño y Principios de Funcionamiento)

#### A. Arquitectura Frontend / Backend
- **ChatTranslator (Frontend):** 
  - Actúa como la fachada (*Facade*) y el contrato estandarizado (`ITranslationProvider`).
  - Es el único módulo visible o invocado por el resto de la suite para tareas de traducción.
  - Se encarga de evaluar la prioridad de selección del backend:
    1. **Prioridad Alta:** Flag de contexto explícita en tiempo de ejecución (ej. pasada en el mapa de contexto de la petición/comando).
    2. **Prioridad Baja (Fallback):** Configuración estática definida en el archivo de configuración (`config.yml`).
  - No contiene lógica de traducción ni maneja APIs de terceros de forma directa; delega esta responsabilidad al backend seleccionado.

- **Los Backends (gtranslate, ltranslate, etc.):**
  - Son módulos independientes que implementan el contrato estándar definido por el frontend.
  - Realizan el trabajo pesado: serialización de payloads, escape de caracteres especiales, llamadas a APIs o procesamiento local, y deserialización de la respuesta.
  - **Restricción de Dependencias:** Todo backend depende estrictamente del frontend (`ChatTranslator`) para registrarse ante el gestor de módulos y cumplir con la interfaz.

#### B. Resolución de Dependencias en el Manager
- El sistema de carga y inicialización (`manager`) debe respetar el sentido estricto del acoplamiento:
  1. Se carga e inicializa primero el frontend (`ChatTranslator`).
  2. Posteriormente se cargan los backends registrados. Durante su arranque, cada backend se auto-registra en el registro interno del frontend mediante inyección o registro estático de proveedores.

---

### 3. Especificación Técnica de la Interfaz Estándar (Contrato)

Define el contrato base que todo backend debe cumplir (ej. en seudo-código Java, conceptual según el stack del proyecto):

- **Métodos obligatorios del contrato:** (no usar literalmente, es solo un ejemplo ilustrativo.)
  - `String translate(String text, String sourceLanguage, String targetLanguage)`
  - `String translate(String text, String sourceLanguage, String targetLanguage, Context flags)`
  - `String getBackend()` (ej. `"gtranslate"`)
  - `String[] getBackends()` (ej. `["gtranslate", "ltranslate"]`)

- **Sistema de Selección (Resolución de Backend):**
  ```text
  [Solicitud de Traducción] 
          │
          ▼
  ¿Existe flag de contexto activa? ──(Sí)──► [Usar Backend indicado por Flag]
          │
        (No)
          ▼
  [Leer config.yml (Default Backend)] ──► [Usar Backend configurado]
          │
          ▼
  [Delegar serialización, escape y traducción al Backend seleccionado]
  ```
