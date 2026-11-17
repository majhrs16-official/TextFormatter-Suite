### Plan de Acción & ADR: Arquitectura de Relay de Archivos ZIP E2EE via WebSockets Públicos

#### Arquitectura del Sistema de Comunicación y Fallback

```
+---------------------------------------------------------------------------------+
|                             1. Generación E2EE                                  |
|  - Topic (8 bytes SecureRandom -> 16 hex chars)                                 |
|  - Zip Key (16 bytes SecureRandom -> 32 hex chars)                              |
|  - IV AES-GCM (12 bytes aleatorios prepended al payload cifrado)                |
|  - String Compartida = Topic Hex + Key Hex (48 chars hex concatenados)          |
+---------------------------------------------------------------------------------+
                                       │
                                       ▼
+---------------------------------------------------------------------------------+
|                         2. Cascada de Conexión WebSocket                        |
|  - Dominios: broker.hivemq.com, test.mosquitto.org, broker.emqx.io              |
|  - Puertos: [WSS Secure (8884/8081/8084), WS Standard (solo en backend Java)]   |
|  - Filtro Cliente Web: Si HTTPS activo -> omitir WS plano (evitar Mixed Content)|
+---------------------------------------------------------------------------------+
                                       │
            ┌──────────────────────────┴──────────────────────────┐
            ▼ (Éxito en conexión)                                 ▼ (Fallo total)
+---------------------------------------+       +---------------------------------+
|      3. Transmisión Fragmentada       |       |          4. Fallback Nativo     |
|  - Compresión del directorio a ZIP    |       |  - Reutilización del sistema    |
|  - Cifrado AES-GCM + IV (12 bytes)    |       |    actual de exportación ZIP    |
|  - Chunks 4KB + Cabecera de control   |       |    por archivo local.           |
|  - Throttling (15-20ms) + QoS 1 + ACK |       +---------------------------------+
+---------------------------------------+

```

### Plan de Acción (Prioridad Media)

#### Fase 1: Generación de Criptografía y Metadatos (Java & Frontend)

1. **Generación de Bytes Aleatorios:**

   * Utilizar `SecureRandom` (equivalente a `/dev/urandom`) tanto en el servidor Java como en el cliente para generar 8 bytes para el identificador del *topic* y 16 bytes para la clave de cifrado simétrico del archivo ZIP.

2. **Formateo Hexadecimal y Concatenación:**

   * Convertir los arreglos de bytes a cadenas hexadecimales puras (16 caracteres para el topic, 32 caracteres para la clave de cifrado).

   * Generar la cadena unificada para compartir manualmente entre ambos extremos (longitud total de 48 caracteres hexadecimales).

3. **Cifrado E2EE del ZIP con AES-GCM:**

   * Generar un Vector de Inicialización (**IV**) aleatorio de 12 bytes por cada transferencia.

   * Aplicar cifrado **AES-GCM** (128 bits usando la clave de 16 bytes) sobre el flujo binario del ZIP.

   * Anteponer (*prepend*) los 12 bytes del IV al inicio del binario cifrado antes de fragmentarlo, permitiendo que el receptor extraiga el IV dinámicamente para descifrar y validar la integridad del archivo.

#### Fase 2: Implementación de la Cascada de Conexión WebSocket

1. **Definición de Topología de Brokers y Puertos:**

   * **Dominio 1:** `broker.hivemq.com` (WSS: `8884`, WS: `8000`)

   * **Dominio 2:** `test.mosquitto.org` (WSS: `8081`, WS: `8080`)

   * **Dominio 3:** `broker.emqx.io` (WSS: `8084`, WS: `8083`)

2. **Algoritmo de Reintento en Cascada y Prevención de *Mixed Content*:**

   * **En el Frontend Web:** Detectar `window.location.protocol`. Si la página corre bajo `https:`, filtrar y omitir los puertos `ws://` sin cifrar para evitar bloqueos por *Mixed Content* del navegador, probando únicamente los endpoints `wss://` en cascada.

   * **En el Servidor Java:** Iterar sobre cada dominio intentando primero el puerto `wss://` y, ante fallo o *timeout*, el puerto `ws://` antes de pasar al siguiente dominio (los brokers enrutan el topic internamente sin importar si un extremo usa WS y el otro WSS).

   * Si se agotan todos los dominios y puertos externos sin éxito, activar la bandera de *fallback*.

#### Fase 3: Protocolo de Fragmentación Binaria, Control de Flujo y Fallback

1. **Empaquetado de Chunks con Cabecera de Control:**

   * Dividir el archivo ZIP cifrado (IV + ciphertext) en bloques de datos de **4 KB** (4096 bytes).

   * Anteponer a cada chunk una **cabecera binaria de control de 11 bytes** para garantizar orden, completitud e integridad:

     * `MAGIC_BYTE` (1 byte): Identificador de trama válida (ej. `0x54`).

     * `TRANSFER_ID` (2 bytes): ID aleatorio de la transferencia en curso.

     * `CHUNK_INDEX` (2 bytes): Índice secuencial del fragmento actual (`0` a `N-1`).

     * `TOTAL_CHUNKS` (2 bytes): Cantidad total de fragmentos esperados.

     * `CRC32` (4 bytes): Suma de verificación del payload del chunk.

2. **Control de Tasa (*Throttling*) y Confirmación (*Handshake*):**

   * Transmitir los paquetes utilizando **MQTT QoS 1** con un retardo controlado de **15 a 20 ms** entre envíos para evitar desconexiones por *rate-limiting* en brokers públicos.

   * Implementar un mensaje de confirmación (**ACK**) cifrado de vuelta en el mismo topic una vez que el receptor (ya sea la web al importar o el servidor Java al recibir cambios) haya reensamblado, verificado los CRC32 y descifrado el ZIP con éxito.

3. **Activación del Fallback:**

   * En caso de que la cascada de WebSockets falle por completo, desviar el flujo hacia el sistema de exportación/importación de ZIP local existente en la aplicación.

#### Fase 4: Interfaz de Usuario en el Editor Web (Pendiente)

1. **Flujo de Entrada (Importación al abrir la página):**

   * Al cargar la aplicación web, presentar un modal o pantalla inicial que solicite al usuario ingresar el **"código de topic/clave"** (la cadena de 48 caracteres hexadecimales generada por el servidor).

   * Incluir debajo de dicho campo un botón secundario con el texto **"importar manualmente"** para disparar el flujo tradicional de carga de archivo ZIP local (*fallback*).

2. **Flujo de Salida (Exportación hacia el servidor):**

   * Adaptar la acción de guardar/exportar en el editor web para comprimir la configuración modificada en ZIP, cifrarla con la misma clave de sesión (generando un nuevo IV de 12 bytes) y transmitirla por chunks hacia el topic activo en el servidor Java, esperando el paquete `ACK` de confirmación.

### Architectural Decision Record (ADR)

* **Título:** ADR-0027: Arquitectura de Relay de Archivos ZIP E2EE mediante WebSockets Públicos con Cascada de Reintentos y Fallback Local.

* **Estado:** Propuesto (Prioridad Media).

* **Contexto:** Se requiere transferir un directorio comprimido (ZIP) bidireccionalmente entre un servidor Java y un navegador web sin disponer de un backend tradicional autoalojado ni servicios de pago. Los canales de mensajería tradicionales (como Pusher o Ably) imponen restricciones estrictas de tamaño de *payload* que impiden el paso directo de archivos.

* **Decisión:**

  1. Utilizar servidores WebSocket públicos y gratuitos sin registro (brokers MQTT expuestos por WS/WSS) para evitar la creación de cuentas y dependencia de tarjetas de crédito.

  2. Implementar cifrado de extremo a extremo (E2EE) mediante **AES-GCM** utilizando una clave de 16 bytes generada por `SecureRandom` e IVs únicos de 12 bytes por transferencia.

  3. Establecer un identificador de canal (*topic*) de 16 caracteres hexadecimales (8 bytes de `SecureRandom`) concatenado a la clave (48 caracteres hexadecimales en total).

  4. Desarrollar un sistema de conexión en cascada (`broker.hivemq.com`, `test.mosquitto.org`, `broker.emqx.io`) respetando las políticas de *Mixed Content* de navegadores HTTPS.

  5. Implementar un protocolo binario ligero sobre MQTT QoS 1 con cabeceras de control (índices, CRC32), *throttling* de envío (15-20 ms) y confirmación por `ACK`.

  6. Incorporar el sistema de exportación/importación ZIP local como mecanismo de *fallback* automático y manual.

* **Consecuencias:**

  * **Positivas:** Cero costes de infraestructura, total anonimato, ausencia de cuentas de terceros, protección contra pérdida/desorden de paquetes en brokers públicos y alta resiliencia mediante cascada multi-dominio y fallback local.

  * **Negativas:** Dependencia de la disponibilidad de redes de terceros y necesidad de mantener el empaquetador/desempaquetador binario de chunks tanto en Java como en TypeScript/JavaScript.