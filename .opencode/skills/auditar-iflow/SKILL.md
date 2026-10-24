---
name: Auditar iFlow
description: Auditoría forense del módulo iFlow y su compilador (ISA, memoria, ejecución, servicios HOST, seguridad, formato binario, integración, corrección funcional) con hallazgos verificables. Úsala cuando el usuario pida auditar iFlow, la VM, el ISA, la paginación, el bytecode o el compilador.
---

# Auditar iFlow (VM + compiler)

## Cuándo usarla

- El usuario pide una auditoría de iFlow, de la VM, del ISA, de la memoria, del
  compilador o del formato binario.
- Para el proyecto completo, usa `auditar-proyecto`.

## Referencia obligatoria

`references/auditoria-iflow.md` fija el alcance, las reglas anti-alucinación, la
estructura del informe y el control de calidad previo a la entrega. **Léelo
entero antes de concluir.**

## Cómo

1. Alcance mínimo: `src/iflow/` (base de `iflow-vm` 32 bits), `tools/iflow-compiler/`,
   sus tests, los contratos del ABI v2 y los módulos que interactúan con la VM.
   `src/iflow-wasm/` queda **fuera de alcance**: es la línea WebAssembly/Chicory y
   se audita por separado.
2. Contrasta cada contrato con `docs/prompts/diseno/iflow-cpu-design.md`, la
   especificación técnica de referencia v3 `docs/prompts/diseno/iflow-cpu-implement.md` y los contratos
   ABI v2 documentados en `src/iflow/README.md` y `tools/iflow-compiler/README.md`.
   Una discrepancia entre esa documentación y el código es un hallazgo, no un detalle.
3. Aporta evidencia reproducible: fichero y línea, programa ensamblado, comando o
   test. Los números de línea deben proceder del código que realmente inspeccionaste.

## Salida

Informe en el orden que fija la referencia, declarando la cobertura y sin afirmar
nada que no se haya demostrado.

## Reglas

- No modifiques el código: es un diagnóstico.
- Evalúa las decisiones de diseño por sus propiedades reales, no por su
  sofisticación ni por la intención que las justifica.
