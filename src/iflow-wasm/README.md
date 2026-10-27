# iflow-wasm — motor de políticas WebAssembly (Chicory)

Módulo de la línea **`iflow-wasm`**: políticas compiladas a WebAssembly (`.wasm`) y ejecutadas en la
JVM con **Chicory**. Es una línea paralela e independiente de **`iflow-vm`** (la VM propia de 32
bits); no hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

## Estado

**W2 — runtime mínimo.** El módulo carga, valida, instancia e invoca un `.wasm` bajo un contrato
restrictivo (`WasmContract`). Todavía **no** está conectado a `Router`/`MessageDispatcher` y no
implementa los imports de host de la spec v3 (W3/W3.5).

| Pieza | Estado |
|---|---|
| Módulo Gradle `:src:iflow-wasm` | ✅ creado |
| Dependencia `com.dylibso.chicory:runtime:1.4.0` | ✅ declarada |
| `WasmModuleLoader` (parsear / validar / instanciar / invocar) | ✅ W2 |
| `WasmContract` + `ValidationResult` | ✅ W2 |
| Contrato de imports/exports v3 | 📝 ADR-W1 propuesto (inline en `docs/workdir/PLAN.md`) |
| Validación de firma de exports y hash del artefacto | ⏳ W3 |
| Integración con el pipeline de mensajes | ⏳ W3 |

## Estructura

```text
src/iflow-wasm/
├── build.gradle
├── README.md
└── src/
    ├── main/java/me/majhrs16/suite/iflow/wasm/
    │   ├── WasmModuleLoader.java
    │   ├── WasmContract.java
    │   └── ValidationResult.java
    └── test/java/me/majhrs16/suite/iflow/wasm/
        └── WasmModuleLoaderTest.java
```

## Contrato actual (`WasmContract.policyV3()`)

| Regla | Valor | Motivo |
|---|---|---|
| Export obligatorio | `decide` | Punto de entrada de la política |
| Imports permitidos | ninguno | Sin WASI ni acceso al host hasta W3 |
| Memoria máxima | 256 páginas (16 MiB) | Acota el crecimiento lineal |
| Sección `start` | denegada | Evita ejecución al instanciar |
| Tamaño del artefacto | 4 MiB | Evita parsear artefactos desproporcionados |
| Memorias por módulo | 1 | Excluye multi-memory hasta que el contrato lo defina |

La validación acumula todos los incumplimientos en `ValidationResult.errors()`. Un módulo inválido
no se instancia: `instantiate(module, contract)` lanza `IllegalArgumentException`.

## Uso actual

```java
var loader = new WasmModuleLoader();
WasmModule module = loader.load(Path.of("policy.wasm"));

ValidationResult check = loader.validate(module, WasmContract.policyV3());
if (!check.valid()) {
    throw new IllegalArgumentException(check.errors().toString());
}

Instance instance = loader.instantiate(module, WasmContract.policyV3());
long[] result = loader.invoke(instance, "decide");
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
   memoria, imports permitidos, exports requeridos y `start`. El hash/firma queda para W3.
4. **Un ABI de host versionado.** No se replica el ABI `HOST` v2 de iFlow.
5. **Autoría multi-lenguaje.** Cualquier toolchain que produzca `.wasm` (Rust, C/C++, Zig,
   AssemblyScript, TinyGo, WAT) es válida; el servidor no ejecuta toolchains nativas arbitrarias.

## Build

```bash
./gradlew :src:iflow-wasm:build
```

> **Validación:** `:src:iflow-wasm:build` pasa con JDK 21 y Chicory `1.4.0`. El lockfile está
> resuelto y `gradle/verification-metadata.xml` incluye la verificación SHA-256/SHA-512 de
> `asm-bom:9.8`, transitiva de Chicory. Los tests de W2 usan módulos binarios mínimos escritos a
> mano (sin toolchain WAT).

## Próximos pasos

1. Validar la firma del export `decide` con `Instance.exportType(name)`.
2. Definir los imports `iflow.*` de la spec v3 y su lista blanca por capability.
3. `WasmPolicyEngine` conectado a `Router`/`RouteDecision`/`RouteOutcome`.
4. Hash/firma del artefacto y política de hot-reload.
5. Test de integración con un `.wasm` mínimo compilado desde WAT.
6. Ejemplos en al menos dos lenguajes y guía de autoría.

## Relación con `iflow-vm`

- `iflow-vm`: VM propia de 32 bits, ISA `.flow`, BIOS/kernel, compilador `tools/iflow-compiler/`.
  Su especificación técnica de referencia v3 vive en [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md).
- `iflow-wasm`: este módulo. No hereda requisitos, ISA ni ABI de `iflow-vm`.

La convivencia y la selección de motor por configuración se deciden en el ADR de integración (W3).
