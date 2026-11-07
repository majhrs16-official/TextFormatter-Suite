# core-render — puertos de renderizado y entrega

> **Purpose**: contratos neutrales que transportan componentes Adventure sin
> contaminar `core-api`, que debe permanecer sin dependencias.

---

## 1. Responsibilities

- **`MessageComponentRenderer`** — renderiza un `Message` para un destinatario
  con idiomas de origen/destino ya resueltos. No enruta, no comprueba permisos
  y no entrega.
- **`ChatDelivery`** — puerto de salida que implementa cada host para mostrar
  componentes, escribir en consola y reproducir sonidos.

## 2. Non-responsibilities

- No traduce ni resuelve idiomas: recibe `Language` resueltos.
- No enruta, no filtra por permisos y no decide canales.
- No conoce Bukkit, Fabric, Velocity ni el runtime de módulos.
- No implementa el render: solo publica los contratos; los adaptadores y
  `textformatter` aportan las implementaciones.

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message`, `Actor`, `Language`, `SoundSpec` |
| `adventure-api` | Compile | `Component` en la firma de los puertos |

Java 17. El módulo se publica como `suite-core-render` en el repositorio Maven
local del build.

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | La etapa `format` y `DeliveryExecutor` reciben ambos puertos |
| `textformatter` | Implementa `MessageComponentRenderer` |
| `spigot-host` / `fabric-host` | Implementan `ChatDelivery` |
| `suite-runtime` | Cablea las implementaciones activas |

## 5. Main Components

| Component | Role |
|-----------|------|
| `MessageComponentRenderer` | `render(message, recipient, source, target)` y sobrecarga con fallback de idiomas |
| `ChatDelivery` | `deliver`, `deliverConsole`, `playSound`, `hasSound` |

## 6. Threading

El motor puede llamar desde cualquier hilo. La implementación de `ChatDelivery`
decide si necesita volver al hilo principal de la plataforma; el contrato no
impone un hilo concreto.
