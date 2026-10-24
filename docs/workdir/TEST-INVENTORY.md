# Inventario de Tests y Cobertura — 2026-10-23

Generado automáticamente con ./gradlew testInventory.

| Módulo | Test Dir | Archivos Test | Líneas Prod | Líneas Test | Cobertura Línea | Cobertura Rama | Gaps Críticos |
|---|---|---:|---:|---:|---:|---:|---|
| :src:common-legacy | ❌ FALTA | 0 | N/A | N/A | N/A | N/A | SIN TESTS |
| :src:core-api | ✅ OK | 2 | N/A | 259 | N/A | N/A | Pocos tests (<3) |
| :src:example-extension | ✅ OK | 1 | N/A | 45 | N/A | N/A | Pocos tests (<3) |
| :src:extension-api | ✅ OK | 2 | N/A | 132 | N/A | N/A | Pocos tests (<3) |
| :src:fabric-host | ⚠️ VACÍO | 0 | N/A | N/A | N/A | N/A | SIN TESTS |
| :src:gtranslate | ✅ OK | 3 | N/A | 229 | N/A | N/A |  |
| :src:host | ✅ OK | 9 | 1503 | 1628 | 30.9% | 23.8% | Cobertura línea <70%; Cobertura rama <50% |
| :src:iflow-vm | ✅ OK | 27 | 2618 | 6271 | 86.2% | 71.3% |  |
| :src:iflow-wasm | ✅ OK | 1 | N/A | 32 | N/A | N/A | Pocos tests (<3) |
| :src:inworld | ✅ OK | 1 | N/A | 30 | N/A | N/A | Pocos tests (<3) |
| :src:kernel | ✅ OK | 4 | 202 | 243 | 91.1% | 82.0% |  |
| :src:loadtest | ✅ OK | 4 | N/A | 1120 | N/A | N/A |  |
| :src:logger | ✅ OK | 4 | 75 | 405 | 93.3% | 78.6% |  |
| :src:ltranslate | ✅ OK | 3 | N/A | 288 | N/A | N/A |  |
| :src:manager-apt | ✅ OK | 3 | N/A | 317 | N/A | N/A |  |
| :src:manager-core | ✅ OK | 3 | N/A | 683 | N/A | N/A |  |
| :src:manager-spi | ✅ OK | 2 | N/A | 323 | N/A | N/A | Pocos tests (<3) |
| :src:messages | ✅ OK | 1 | 30 | 53 | 93.3% | 75.0% | Pocos tests (<3) |
| :src:observability | ✅ OK | 2 | N/A | 105 | N/A | N/A | Pocos tests (<3) |
| :src:performance | ✅ OK | 2 | N/A | 135 | N/A | N/A | Pocos tests (<3) |
| :src:presets | ✅ OK | 1 | N/A | 60 | N/A | N/A | Pocos tests (<3) |
| :src:spigot-host | ✅ OK | 2 | 1438 | 161 | 2.5% | 4.6% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |
| :src:sync-bus | ✅ OK | 1 | N/A | 116 | N/A | N/A | Pocos tests (<3) |
| :src:sync-discord | ✅ OK | 3 | N/A | 476 | N/A | N/A |  |
| :src:sync-http | ✅ OK | 3 | N/A | 192 | N/A | N/A |  |
| :src:sync-tcpudp | ✅ OK | 3 | N/A | 202 | N/A | N/A |  |
| :src:sync-telegram | ✅ OK | 2 | N/A | 173 | N/A | N/A | Pocos tests (<3) |
| :src:sync-velocity | ✅ OK | 1 | N/A | 305 | N/A | N/A | Pocos tests (<3) |
| :src:sync-websocket | ✅ OK | 1 | N/A | 26 | N/A | N/A | Pocos tests (<3) |
| :src:tester | ⚠️ VACÍO | 0 | N/A | N/A | N/A | N/A | SIN TESTS |
| :src:textformatter | ✅ OK | 6 | 282 | 512 | 81.6% | 66.7% |  |
| :src:transport | ✅ OK | 1 | 351 | 22 | 6.0% | 1.0% | Pocos tests (<3); Cobertura línea <70%; Cobertura rama <50% |

---

## Resumen

- **Total módulos Java**: 32
- **Con tests**: 29
- **Sin directorio de tests**: 1
- **Directorio de tests vacío**: 2

> **Nota**: Cobertura N/A = módulo sin tests o sin reporte JaCoCo. Ejecuta ./gradlew jacocoTestReport antes para datos frescos.