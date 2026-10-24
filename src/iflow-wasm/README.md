# iflow-wasm — motor de políticas WebAssembly (Chicory)

Módulo de la línea **`iflow-wasm`**: políticas compiladas a WebAssembly (`.wasm`) y ejecutadas en la
JVM con **Chicory**. Es una línea paralela e independiente de **`iflow-vm`** (la VM propia de 32
bits); no hereda el ISA `.flow`, el ABI `HOST` v2 ni el modelo BIOS/kernel.

## Estado

Scaffold inicial (**W0**). El módulo ya declara la dependencia de Chicory y expone un loader mínimo.
Todavía **no** está conectado a `Router`/`MessageDispatcher`, no valida imports/exports y no aplica
capabilities ni límites de ejecución.

| Pieza | Estado |
|---|---|
| Módulo Gradle `:src:iflow-wasm` | ✅ creado |
| Dependencia `com.dylibso.chicory:runtime:1.4.0` | ✅ declarada |
| `WasmModuleLoader` (parsear / instanciar) | ✅ mínimo |
| Contrato de imports/exports | ⏳ W1 |
| Validación de artefactos y límites | ⏳ W1/W2 |
| Integración con el pipeline de mensajes | ⏳ W3 |

## Estructura

```text
src/iflow-wasm/
├── build.gradle
├── README.md
└── src/
    ├── main/java/me/majhrs16/suite/iflow/wasm/
    │   └── WasmModuleLoader.java
    └── test/java/me/majhrs16/suite/iflow/wasm/
        └── WasmModuleLoaderTest.java
```

## Uso actual

```java
var loader = new WasmModuleLoader();
var module = loader.load(Path.of("policy.wasm"));
var instance = loader.instantiate(module);
```

Cada llamada a `instantiate` crea una instancia Chicory nueva con su propia memoria lineal. El loader
no implementa todavía el ABI de host ni el contrato de políticas.

## Reglas de diseño

1. **Sin WASI de sistema por defecto.** El guest no recibe filesystem, red, procesos ni entorno del
   host. Solo imports explícitos y capability-gated.
2. **Sin estado compartido entre instancias.** La persistencia, si se necesita, se modela como estado
   de host explícito.
3. **Artefacto verificable.** W1/W2 deben validar magic/version, límites de memoria, imports
   permitidos, exports requeridos y hash antes de ejecutar.
4. **Un ABI de host versionado.** No se replica el ABI `HOST` v2 de iFlow.
5. **Autoría multi-lenguaje.** Cualquier toolchain que produzca `.wasm` (Rust, C/C++, Zig,
   AssemblyScript, TinyGo, WAT) es válida; el servidor no ejecuta toolchains nativas arbitrarias.

## Build

```bash
./gradlew :src:iflow-wasm:test
```

> **Pendiente de entorno:** el lockfile y las entradas de `gradle/verification-metadata.xml` para
> Chicory se añadieron de forma inicial, pero deben regenerarse/validarse con Gradle en un entorno
> con JDK antes de declarar W0 cerrado. En esta sesión no hay `java` disponible.

## Próximos pasos

1. ADR de `iflow-wasm`: imports/exports, capabilities, límites, errores y ciclo de vida.
2. Validador de artefactos y política de imports permitidos.
3. `WasmPolicyEngine` conectado a `Router`/`RouteDecision`/`RouteOutcome`.
4. Test de integración con un `.wasm` mínimo compilado desde WAT.
5. Ejemplos en al menos dos lenguajes y guía de autoría.

## Relación con `iflow-vm`

- `iflow-vm`: VM propia de 32 bits, ISA `.flow`, BIOS/kernel, compilador `tools/iflow-compiler/`.
  Su especificación técnica de referencia v3 vive en [`docs/prompts/diseno/iflow-cpu-implement.md`](../../docs/prompts/diseno/iflow-cpu-implement.md).
- `iflow-wasm`: este módulo. No hereda requisitos, ISA ni ABI de `iflow-vm`.

La convivencia y la selección de motor por configuración se deciden en el ADR de integración.
