Actúa como un ingeniero experto en sistemas de baja latencia, desarrollo en C/Java y arquitectura de redes de alta eficiencia. Necesito que diseñes e implementes un sistema de telemetría minimalista, auto-hospedado (local), sin dependencias externas y estrictamente binario.


# Especificaciones:

## A. Tabla del formato binario:
Offset,Campo,Tipo C / Java,Rango / Formato
0x00,Magic Header,uint16_t / short,2 Bytes (ej. 0x785F)
0x02,Client UUID Hash,uint64_t / long,8 Bytes (Hash de 64 bits)
0x0A,Timestamp Compacto,uint32_t / int,4 Bytes (Unix Epoch Seconds)
0x0E,OS & Arch Bitmask,uint8_t / byte,1 Byte (Bits empaquetados)
0x0F,CPU Cores,uint8_t / byte,1 Byte (Saturado en 255)
0x10,RAM in MB,uint16_t / short,"2 Bytes (Unsigned, hasta 65,535 MB)"
0x12,Online Players,uint16_t / short,"2 Bytes (Unsigned, hasta 65,535 jugadores)"
0x14,Active Modules Mask,uint32_t / int,4 Bytes (Bitmask para los 30 módulos)
0x18,Contadores (x4),uint16_t[4] / short[4],"8 Bytes (4 x 2B saturados en 65,535)"

## B. Requisitos técnicos obligatorios:

* Protocolo Binario Estricto (descrito en la tabla A).
* Total exacto: 32 bytes de payload en red.
* Saturated clamping (saturación por límites): En caso de desbordamiento numérico positivo, fijar en el valor máximo admisible del tipo; en caso de desbordamiento negativo, fijar en el valor mínimo.

## C. Cliente - Emisor UDP con Jitter de 24 Horas:

* Módulo adicional escrito en Java: `/src/metrics/client`.
* Utilizar `java.nio.ByteBuffer` con ordenamiento `ByteOrder.LITTLE_ENDIAN` para empaquetar los datos en crudo.
* Enviar mediante un `DatagramSocket` resolviendo el hostname `node1.freemyip.com` en el puerto `32768`.
* Implementar bucle de reintento/programación con un intervalo base de 24 horas + un jitter aleatorio de rango completo ($[0, 86400]$ segundos) recalculado tras cada envío exitoso, evitando el efecto estampida (*thundering herd*) y manteniendo una tasa teórica plana de $\approx 11.57 \text{ pps}$ a escala global.

## D. Servidor - Receptor ligero:

* Programa independiente escrito en C: `/src/metrics/server`.
* Un socket UDP dedicado escuchando en el puerto `32768`.
* Lee el datagrama de red, valida el *Magic Header* de integridad y extrae el payload binario.
* **Registro ampliado:** Añade un timestamp epoch local de alta precisión (8 bytes, milisegundos) al payload recibido para la persistencia en disco, formando un registro estructurado fijo.
* **Almacenamiento rotativo y persistente:** Rotación automática de archivos basada en fecha del sistema (ej. por año/mes/día, independiente del runtime en memoria para resistir reinicios o caídas).
* **Agregación y Compresión Zstd nativa:** Los paquetes recibidos se acumulan en un búfer de escritura por lotes o se anexan en bloques temporales que aprovechan la **concatenación nativa de tramas de zstd** (*frame concatenation*), permitiendo al backend adjuntar nuevos bloques comprimidos al final del archivo en disco (`append`) sin necesidad de ciclos de descompresión intermedia.

---

# Demostracion matematica de viabilidad.

## 1. Definición de Variables Base

### A. Capacidad Física de la Red Doméstica ($B$)

La conexión a Internet de la máquina receptora opera en un rango de ancho de banda de subida/bajada efectivo de:

* $B_{\min} = 150 \text{ Kbps} = 150,000 \text{ bits/s} = 18,750 \text{ bytes/s}$
* $B_{\max} = 300 \text{ Kbps} = 300,000 \text{ bits/s} = 37,500 \text{ bytes/s}$

### B. Tamaño del Paquete Físico en el Cable ($P$)

Aunque el *payload* útil ($L$) es de **32 bytes** para los formatos binarios y de **~750 bytes** para JSON, el tamaño real en la red ($P$) varía de forma estricta según el protocolo y formato elegido:

* **Opción 1: UDP (Binario Puro):**

$$P_{\text{UDP}} = L (32\text{B}) + \text{IP}_{\text{header}} (20\text{B}) + \text{UDP}_{\text{header}} (8\text{B}) = \mathbf{60 \text{ bytes}} = 480 \text{ bits}$$


* **Opción 2: TCP Puro (Socket Binario sobre TCP):**

$$P_{\text{TCP}} = L (32\text{B}) + \text{IP}_{\text{header}} (20\text{B}) + \text{TCP}_{\text{header}} (20\text{B}) = \mathbf{72 \text{ bytes}} = 576 \text{ bits}$$


* **Opción 3: HTTP / JSON (API REST Estándar):**

$$P_{\text{HTTP/JSON}} = L_{\text{JSON}} (750\text{B}) + \text{IP}_{\text{header}} (20\text{B}) + \text{TCP}_{\text{header}} (20\text{B}) + \text{HTTP}_{\text{headers}} (110\text{B}) = \mathbf{900 \text{ bytes}} = 7,200 \text{ bits}$$



---

## 2. Capacidad Máxima de Procesamiento Instantáneo (PPS)

El número máximo de paquetes por segundo ($\text{PPS}_{\max}$) que la línea física puede recibir en $1 \text{ segundo}$ sin saturarse se calcula como:

$$\text{PPS}_{\max} = \frac{B}{P}$$

### Matriz de Rendimiento Máximo por Segundo (Separada por Protocolo):

| Protocolo y Formato | Tamaño Real ($P$) | Ancho de Banda: 150 Kbps | Ancho de Banda: 300 Kbps |
| --- | --- | --- | --- |
| **UDP (Binario)** | $60 \text{ bytes}$ | $\frac{18,750}{60} = \mathbf{312.5 \text{ pps}}$ | $\frac{37,500}{60} = \mathbf{625.0 \text{ pps}}$ |
| **TCP Puro (Binario)** | $72 \text{ bytes}$ | $\frac{18,750}{72} = \mathbf{260.4 \text{ pps}}$ | $\frac{37,500}{72} = \mathbf{520.8 \text{ pps}}$ |
| **HTTP / JSON (REST)** | $900 \text{ bytes}$ | $\frac{18,750}{900} = \mathbf{20.8 \text{ pps}}$ | $\frac{37,500}{900} = \mathbf{41.6 \text{ pps}}$ |

---

## 3. Análisis Estadístico por Escenarios ($N = 1,000,000 \text{ Instancias}$)

Evaluamos el comportamiento de $1,000,000$ de servidores enviando su paquete de telemetría bajo distintas configuraciones de tiempo, sincronización y protocolos.

---

### Escenario A: Intervalo de 1 Hora, SIN Aleatoriedad (Sin Jitter)

Si $1,000,000$ de instancias programan su envío cada $3,600 \text{ segundos}$ basadas en un reloj del sistema sin variación (ej. a las `:00` de cada hora):

* **Tasa instantánea ($t = 0$):**

$$\text{PPS}_{\text{inst}} = \frac{1,000,000 \text{ paquetes}}{1 \text{ segundo}} = 1,000,000 \text{ pps}$$


* **Requerimiento de ancho de banda por protocolo:**
* **UDP:** $1,000,000 \times 60 \text{ bytes} = 60,000,000 \text{ bytes/s} = \mathbf{480 \text{ Mbps}}$
* **TCP Puro:** $1,000,000 \times 72 \text{ bytes} = 72,000,000 \text{ bytes/s} = \mathbf{576 \text{ Mbps}}$
* **HTTP/JSON:** $1,000,000 \times 900 \text{ bytes} = 900,000,000 \text{ bytes/s} = \mathbf{7.2 \text{ Gbps}}$


* **Evaluación:** Cualquier valor supera por miles de órdenes de magnitud los $0.3 \text{ Mbps}$ disponibles. **Colapso inmediato.**

---

### Escenario B: Intervalo de 1 Hora, CON Aleatoriedad (Jitter de $3,600\text{s}$)

Los $1,000,000$ de servidores eligen un segundo aleatorio dentro de un rango de $3,600 \text{ segundos}$.

* **Tasa promedio global ($\mu$):**

$$\mu = \frac{N}{T} = \frac{1,000,000 \text{ instancias}}{3,600 \text{ segundos}} \approx \mathbf{277.77 \text{ pps}}$$



#### Modelado estocástico (Distribución de Poisson):

$$\sigma = \sqrt{\mu} = \sqrt{277.77} \approx 16.66 \text{ pps}$$

$$\text{PPS}_{\text{pico}} = \mu + 3\sigma = 277.77 + 3(16.66) \approx \mathbf{327.75 \text{ pps}}$$

#### Evaluación por Protocolo:

* **UDP a 300 Kbps (Límite: $625 \text{ pps}$):** Soporta los picos de $327.75 \text{ pps}$, consumiendo el $52.4\%$ del ancho de banda local.
* **TCP Puro a 300 Kbps (Límite: $520.8 \text{ pps}$):** Soporta los picos, pero con menor margen debido al incremento del 20% en las cabeceras TCP.
* **HTTP / JSON a 300 Kbps (Límite: $41.6 \text{ pps}$):** **Colapso absoluto**, ya que el promedio requerido ($277.77 \text{ pps}$) es casi 7 veces superior al límite físico de la red.

---

### Escenario C: Intervalo de 24 Horas, SIN Aleatoriedad (Efecto Despertador Masivo)

Si los $1,000,000$ de servidores esperan exactamente 24 horas desincronizados al inicio pero sin jitter continuo:


$$\text{PPS}_{\text{burst}} \ge 50,000 \text{ pps}$$


**Resultado:** Saturación masiva e impredecible del canal para cualquier protocolo.

---

### Escenario D: Intervalo de 24 Horas, CON Aleatoriedad (Jitter de $86,400\text{s}$) — *LA SOLUCIÓN OPTIMIZADA*

Cada servidor elige un segundo al azar en una ventana de $24 \text{ horas}$ ($T = 86,400 \text{ segundos}$).

* **Tasa promedio global ($\mu$):**

$$\mu = \frac{1,000,000 \text{ instancias}}{86,400 \text{ segundos}} = \mathbf{11.574 \text{ pps}}$$



#### Modelado estocástico:

$$\sigma = \sqrt{11.574} \approx 3.402 \text{ pps}$$

$$\text{PPS}_{\text{pico}} = \mu + 3\sigma = 11.574 + 3(3.402) = \mathbf{21.78 \text{ pps}}$$

#### Consumo real de red según protocolo:

* **1. Protocolo UDP (Binario - 60 bytes):**
* Tráfico promedio: $11.574 \text{ pps} \times 60 \text{ bytes} = \mathbf{694.44 \text{ bytes/s}} \approx \mathbf{5.55 \text{ Kbps}}$
* Tráfico en picos: $21.78 \text{ pps} \times 60 \text{ bytes} = \mathbf{1,306.8 \text{ bytes/s}} \approx \mathbf{10.45 \text{ Kbps}}$


* **2. Protocolo TCP Puro (Binario - 72 bytes):**
* Tráfico promedio: $11.574 \text{ pps} \times 72 \text{ bytes} = \mathbf{833.32 \text{ bytes/s}} \approx \mathbf{6.66 \text{ Kbps}}$
* Tráfico en picos: $21.78 \text{ pps} \times 72 \text{ bytes} = \mathbf{1,568.16 \text{ bytes/s}} \approx \mathbf{12.54 \text{ Kbps}}$


* **3. Protocolo HTTP / JSON (REST - 900 bytes):**
* Tráfico promedio: $11.574 \text{ pps} \times 900 \text{ bytes} = \mathbf{10,416.6 \text{ bytes/s}} \approx \mathbf{83.33 \text{ Kbps}}$
* Tráfico en picos: $21.78 \text{ pps} \times 900 \text{ bytes} = \mathbf{19,602 \text{ bytes/s}} \approx \mathbf{156.81 \text{ Kbps}}$ *(Usa más de la mitad del enlace de 300 Kbps solo para telemetría).*



---

## 4. Comparativa Final de Eficiencia

| Métrica de Rendimiento | HTTP / JSON (REST API) | TCP Puro (Binario) | UDP (Binario - Optimizado) |
| --- | --- | --- | --- |
| **Tamaño por paquete físico ($P$)** | $900 \text{ Bytes}$ | $72 \text{ Bytes}$ | **$60 \text{ Bytes}$** |
| **Flujo Promedio ($1\text{M}$ instancias / 24h)** | $11.57 \text{ pps}$ | $11.57 \text{ pps}$ | **$11.57 \text{ pps}$** |
| **Ancho de Banda Requerido (Promedio)** | $\sim 83.33 \text{ Kbps}$ | $\sim 6.66 \text{ Kbps}$ | **$\sim 5.55 \text{ Kbps}$** |
| **Uso de tu Red Doméstica ($300\text{ Kbps}$ max)** | $27.7\%$ (Pesado para la red) | $2.2\%$ (Eficiente) | **$1.85\%$ (Insignificante)** |
| **Capacidad Máxima Soportada (Diaria)** | $\sim 3.6 \text{ Millones}$ | $\sim 45 \text{ Millones}$ | **$\sim 54 \text{ Millones}$** |

### Conclusión Matemática

Implementando el esquema binario de **32 bytes** respaldado por una ventana de aleatoriedad de **24 horas ($Jitter = [0, 86400]$)**, la conexión casera de $150-300 \text{ Kbps}$ procesa el tráfico de **1,000,000 de instancias** utilizando apenas una fracción microscópica de su capacidad.

El uso de **UDP** se consolida como la alternativa perfecta por eliminar el *overhead* de conexión de TCP y el sobrepeso masivo de texto plano de JSON, permitiéndo escalar hasta más de **50 millones de servidores** sin poner en riesgo la estabilidad de tu infraestructura doméstica.
