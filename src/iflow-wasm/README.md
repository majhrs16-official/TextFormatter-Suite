# iflow-wasm — motor de políticas WebAssembly (Chicory)

Módulo de la línea **`iflow-wasm`**: políticas compiladas a WebAssembly (`.wasm`) y ejecutadas en la
JVM con **Chicory**. Es una línea paralela e independiente de **`iflow-vm`** (la VM propia de 32
bits); no hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

## Estado

**W1 cerrado; W2 cerrado en su alcance de motor aislado.** `WasmContract.policyV3()` declara el
módulo de imports `iflow`, sus firmas y el export `decide`; `WasmModuleLoader` valida nombres,
firmas, memoria, `start` y tamaño, y permite instanciar con `ImportValues`. `TlvCodec` implementa
los buffers; `PolicySession` mantiene el journal read-your-writes y la decisión única;
`PolicyHost` enlaza las host functions `iflow.*`; `WasmPolicyEngine` evalúa un `.wasm` y devuelve
un `PolicyOutcome`. La integración con `Router`/`MessageDispatcher`, el hash/hot-reload y la matriz
de paridad `.flow` v3 siguen pendientes (W3/W3.5).

| Pieza | Estado |
|---|---|
| Módulo Gradle `:src:iflow-wasm` | ✅ creado |
| Dependencia `com.dylibso.chicory:runtime:1.4.0` | ✅ declarada |
| `WasmModuleLoader` (parsear / validar / instanciar / invocar) | ✅ W2 |
| `WasmContract` + `ValidationResult` | ✅ W2; firmas de imports/exports en W1 |
| `PolicyAbiV3` (nombres, firmas, estados, acciones, propiedades) | ✅ W1 |
| `TlvCodec` (UTF-8 estricto, nulo vs vacío) | ✅ W1 |
| Host functions `iflow.*` sobre `PolicySession` | ✅ W2 |
| `WasmPolicyEngine` (evaluación aislada) | ✅ W2 |
| `WasmPolicyEngine` conectado a `Router`/`MessageDispatcher` | ⏳ W3 |
| Hash/firma del artefacto y hot-reload | ⏳ W3 |
| Matriz de paridad `.flow` v3 vs `.wasm` | ⏳ W3.5 |

## Estructura

```text
src/iflow-wasm/
├── build.gradle
├── README.md
└── src/
    ├── main/java/me/majhrs16/suite/iflow/wasm/
    │   ├── WasmModuleLoader.java
    │   ├── WasmContract.java
    │   ├── ValidationResult.java
    │   ├── PolicyAbiV3.java
    │   ├── TlvCodec.java
    │   ├── PolicyRequest.java
    │   ├── PolicySession.java
    │   ├── PolicyHost.java
    │   ├── PolicyAction.java
    │   ├── PolicyOutcome.java
    │   ├── PolicyException.java
    │   └── WasmPolicyEngine.java
    └── test/java/me/majhrs16/suite/iflow/wasm/
        ├── WasmModuleLoaderTest.java
        ├── WasmTestModule.java
        ├── TlvCodecTest.java
        ├── PolicySessionTest.java
        └── WasmPolicyEngineTest.java
```

## Contrato v3 (`WasmContract.policyV3()`)

El contrato se define en `PolicyAbiV3` y se aplica con `WasmContract.policyV3()`. El guest importa
el módulo `iflow`; puede usar un subconjunto de imports, pero cada import usado debe coincidir
exactamente en nombre y firma.

### Export obligatorio

| Export | Firma | Significado |
|---|---|---|
| `decide` | `() -> i32` | Ejecuta la política una vez por mensaje. `0` = OK; otro valor = error de política. |

La decisión de routing **no** se toma por el valor de retorno: se registra llamando a
`iflow.decision(...)`. Esto permite distinguir “la política no decidió” de “la política falló” y
evita que un `decide` vacío entregue por defecto.

### Imports del host

Todas las firmas usan `i32` salvo `now_nanos`, que devuelve `i64`.

| Import | Firma | Uso |
|---|---|---|
| `read_property` | `(scope, property, outPtr, outCap) -> status` | Lee una propiedad y escribe un valor TLV en memoria del guest. |
| `write_property` | `(scope, property, inPtr, inLen) -> status` | Aplica una propiedad editable desde un valor TLV. |
| `transform_apply` | `(kind, inPtr, inLen, outPtr, outCap) -> status` | Resuelve placeholders o detecta idioma; devuelve TLV string. |
| `set_channel` | `(namePtr, nameLen) -> status` | Cambia el canal validando existencia y permiso de recepción. |
| `permission_check` | `(actorPtr, actorLen, nodePtr, nodeLen) -> i32` | `1` si el actor tiene el permiso; `0` si no. |
| `channel_policy` | `(namePtr, nameLen, kind, outPtr, outCap) -> status` | Lee política de envío/recepción/rate de un canal. |
| `decision` | `(action, targetPtr, targetLen, reasonPtr, reasonLen) -> status` | Registra la decisión única de la ejecución. |
| `now_nanos` | `() -> i64` | Reloj monótono para políticas temporales. |
| `log` | `(level, msgPtr, msgLen) -> status` | Auditoría acotada; no entrega ni modifica el mensaje. |

### Estados

| Código | Nombre | Significado |
|---|---|---|
| `0` | `OK` | Operación completada. |
| `1` | `NOT_FOUND` | Propiedad/canal/valor inexistente. |
| `2` | `BUFFER_TOO_SMALL` | El buffer de salida no cabe; si hay espacio para 4 bytes, se escribe la longitud requerida en little-endian. |
| `3` | `INVALID_ARGUMENT` | Scope, propiedad, tipo TLV o UTF-8 inválido. |
| `4` | `DENIED` | Propiedad de solo lectura o permiso insuficiente. |
| `5` | `CONFLICT` | Segunda llamada a `decision` en la misma ejecución. |
| `6` | `HOST_FAILURE` | Error interno del host; el mensaje no se entrega. |

### Acciones de decisión

| Código | Acción | Efecto |
|---|---|---|
| `0` | `DELIVER` | Entrega al recipient actual. |
| `1` | `CANCEL_EVENT` | Cancela el evento de plataforma; no hay entrega. |
| `2` | `ERROR` | Error controlado; no hay entrega y se registra la razón. |

No existen `REDIRECT` ni `CHANNEL_REDIRECT`: el cambio de canal se expresa con `set_channel`, que
valida el canal y el permiso antes de modificar el mensaje.

### Scopes y propiedades v3

| Scope | Código | Propiedades |
|---|---|---|
| `MESSAGE` | `1` | `CONTENT` (`0x01`, string, RW), `CHANNEL` (`0x02`, string, RO), `DIRECTION` (`0x03`, string, RO), `TYPE` (`0x04`, string, RO), `LANG_SOURCE` (`0x05`, string, RW), `LANG_TARGET` (`0x06`, string, RW), `COLOR_MODE` (`0x07`, string, RW), `FORMAT_PAPI` (`0x08`, bool, RW), `TRANSLATE` (`0x09`, bool, RW). |
| `EMITTER` / `RECIPIENT` | `2` / `3` | `NAME` (`0x01`, string, RO), `UUID` (`0x02`, string, RO), `LANGUAGE` (`0x03`, string, RO). |
| `CHANNEL` | `4` | `NAME` (`0x01`, string, RO), `RATE` (`0x02`, i64, RO), `SEND_POLICY` (`0x03`, string, RO), `RECEIVE_POLICY` (`0x04`, string, RO). |
| `SERVER` | `5` | Reservado; sin propiedades en W1. |

Los ids son propios de v3. No se reutilizan `PROP_SOUNDS`, `PROP_PROCESSED` ni `PROP_ACTOR_WORLD`
de la VM v2; `TRANSLATE` es un booleano validado.

### TLV

Un valor se codifica como:

```text
[type:u8][length:u32 little-endian][payload]
```

| Tipo | Código | Payload |
|---|---|---|
| `NULL` | `0` | Vacío; ausencia explícita, distinta de string vacío. |
| `STRING` | `1` | UTF-8 estricto; se rechazan secuencias inválidas y surrogates sueltos. |
| `I64` | `2` | Exactamente 8 bytes little-endian. |
| `BOOL` | `3` | Un byte `0` o `1`. |
| `BYTES` | `4` | Bytes opacos. |

`TlvCodec` copia defensivamente el payload y no trunca caracteres multibyte.

### Límites

| Regla | Valor | Motivo |
|---|---|---|
| Memoria máxima | 256 páginas (16 MiB) | Acota el crecimiento lineal. |
| Sección `start` | denegada | Evita ejecución al instanciar. |
| Tamaño del artefacto | 4 MiB | Evita parsear artefactos desproporcionados. |
| Memorias por módulo | 1 | Excluye multi-memory hasta que el contrato lo defina. |

La validación acumula todos los incumplimientos en `ValidationResult.errors()`. Un módulo inválido
no se instancia: `instantiate(module, contract)` lanza `IllegalArgumentException`.

## Motor y host functions (W2)

`WasmPolicyEngine.evaluate(module, request)` crea una `PolicySession`, enlaza `PolicyHost` e invoca
`decide`. El resultado es un `PolicyOutcome` con la acción, el destino informativo, la razón y el
mensaje transformado.

- **Journal read-your-writes:** `write_property` actualiza el mensaje de trabajo y las lecturas
  posteriores ven el cambio. El `Message` original no se muta.
- **Decisión única:** la primera llamada a `decision` gana; la segunda devuelve `CONFLICT` y el
  outcome final es `ERROR`. Si no hay decisión, el outcome también es `ERROR` (deny-by-default).
- **`set_channel` validado:** comprueba que el canal exista y que el emisor tenga el permiso de
  envío y el recipient el de recepción. No hay `REDIRECT` ni `CHANNEL_REDIRECT`.
- **Transformaciones:** `transform_apply` resuelve placeholders con el recipient original o detecta
  idioma; el resultado se escribe como TLV string en la memoria del guest.
- **Permisos:** `permission_check` responde `1`/`0`; los actores conocidos se resuelven por nombre
  contra emisor/recipient y el resto se representa como `Actor.unknown`.
- **Fallos:** cualquier error de carga, instanciación o ejecución se convierte en
  `PolicyOutcome.error(...)`; el host no recibe excepciones de un artefacto no confiable.

## Uso actual

```java
var loader = new WasmModuleLoader();
WasmModule module = loader.load(Path.of("policy.wasm"));

ValidationResult check = loader.validate(module, WasmContract.policyV3());
if (!check.valid()) {
    throw new IllegalArgumentException(check.errors().toString());
}

// Sin imports: módulo mínimo de W2.
Instance instance = loader.instantiate(module, WasmContract.policyV3());
long[] result = loader.invoke(instance, "decide");

// Con host functions: el engine construirá ImportValues en W2/W3.
ImportValues imports = ImportValues.builder()
    .addFunction(readPropertyHostFunction)
    .build();
Instance connected = loader.instantiate(module, WasmContract.policyV3(), imports);

// Camino de alto nivel: sesión + host functions + decisión.
WasmPolicyEngine engine = new WasmPolicyEngine(loader);
PolicyOutcome outcome = engine.evaluate(module, PolicyRequest.of(
    message, emitter, recipient, channels));
if (outcome.delivered()) {
    deliver(outcome.message());
}
```

Cada llamada a `instantiate` crea una instancia Chicory nueva con su propia memoria lineal. La
memoria se instancia con `withMemoryLimits(initial, max)` y la sección `start` se controla con
`withStart(contract.allowStart())`.

## Reglas de diseño

1. **Sin WASI de sistema por defecto.** El guest no recibe filesystem, red, procesos ni entorno del
   host. Solo imports explícitos y capability-gated.
2. **Sin estado compartido entre instancias.** La persistencia, si se necesita, se modela como estado
   de host explícito.
3. **Artefacto verificable.** El contrato valida magic/version (vía Chicory), tamaño, límites de
   memoria, imports permitidos y firmas, exports requeridos y `start`. El hash/firma queda para W3.
4. **Un ABI de host versionado.** No se replica el ABI `HOST` v2 de iFlow.
5. **Autoría multi-lenguaje.** Cualquier toolchain que produzca `.wasm` (Rust, C/C++, Zig,
   AssemblyScript, TinyGo, WAT) es válida; el servidor no ejecuta toolchains nativas arbitrarias.
6. **Una decisión por ejecución.** La segunda llamada a `decision` devuelve `CONFLICT`; no se
   sobrescribe silenciosamente.

## Build

```bash
./gradlew :src:iflow-wasm:build
```

> **Validación:** `:src:iflow-wasm:build` pasa con JDK 21 y Chicory `1.4.0`. El lockfile está
> resuelto y `gradle/verification-metadata.xml` incluye la verificación SHA-256/SHA-512 de
> `asm-bom:9.8`, transitiva de Chicory. Los tests usan módulos binarios mínimos generados por
> `WasmTestModule` (sin toolchain WAT).

## Tests

```bash
./gradlew :src:iflow-wasm:test
```

Cobertura actual:

- `WasmModuleLoaderTest`: parseo, límites de tamaño/memoria, `start`, imports/exports obligatorios,
  firmas de imports y exports, instanciación con `ImportValues` e invocación de `decide`.
- `TlvCodecTest`: round-trip de string/i64/bool/bytes/nulo, distinción nulo vs string vacío, UTF-8
  estricto, longitudes inválidas, bytes sobrantes y copia defensiva.
- `PolicySessionTest`: lectura/escritura de propiedades, journal read-your-writes, canales y
  permisos, transformaciones, decisión única y deny-by-default, logs acotados.
- `WasmPolicyEngineTest`: módulos binarios que llaman a `decision`, `write_property`,
  `set_channel` y `transform_apply`, incluyendo memoria lineal y data segments.
- `IflowWasmActivatorTest`: registro del loader y del contrato en el `ModuleContext`.

Para añadir un caso de contrato, usa `WasmTestModule` para emitir el módulo binario y valida con
`WasmContract.policyV3()`; no se necesita un compilador Wasm en el build.

## Próximos pasos

1. Conectar `WasmPolicyEngine` a `Router`/`MessageDispatcher` y mapear `PolicyOutcome` al pipeline
   existente sin reabrir el contrato v2 (W3).
2. Añadir hash/firma del artefacto y política de hot-reload (W3).
3. Construir la matriz de paridad `.flow` v3 (ROM) vs `.wasm` con el mismo resultado observable
   (W3.5), sin usar el ISA v2 como contrato.
4. Ejemplos en al menos dos lenguajes y guía de autoría (W4).

## Relación con `iflow-vm`

- `iflow-vm`: VM propia de 32 bits, ISA `.flow`, BIOS/kernel, compilador `tools/iflow-compiler/`.
  Su especificación técnica de referencia v3 vive en [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md).
- `iflow-wasm`: este módulo. No hereda requisitos, ISA ni ABI de `iflow-vm`.

La convivencia y la selección de motor por configuración se deciden en el ADR de integración (W3).
