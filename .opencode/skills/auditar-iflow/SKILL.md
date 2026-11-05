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

1. Alcance mínimo: `src/iflow-vm/` (implementación actual v2 de 16 bits, objetivo v3
   de 32 bits), `tools/iflow-compiler/`, sus tests, el ABI `HOST` v2 que implementa
   el código y los módulos que interactúan con la VM. `src/iflow-wasm/` queda
   **fuera de alcance**: es la línea WebAssembly/Chicory y se audita por separado.
2. Contrasta cada contrato con la especificación técnica de referencia v3
   `docs/prompts/diseno/iflow-cpu-implement.md` y con la implementación real
   documentada en `src/iflow-vm/README.md` y `tools/iflow-compiler/README.md`.
   `docs/prompts/diseno/iflow-cpu-design.md` es un **documento histórico**: sirve
   como contexto de partida, nunca como contrato vigente. Una discrepancia entre
   la spec v3, el README de implementación y el código es un hallazgo, no un
   detalle.
3. Aporta evidencia reproducible: fichero y línea, programa ensamblado, comando o
   test. Los números de línea deben proceder del código que realmente inspeccionaste.

## Salida

Informe en el orden que fija la referencia, declarando la cobertura y sin afirmar
nada que no se haya demostrado.

## Reglas

- No modifiques el código: es un diagnóstico.
- Evalúa las decisiones de diseño por sus propiedades reales, no por su
  sofisticación ni por la intención que las justifica.
