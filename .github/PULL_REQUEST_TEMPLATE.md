# Pull Request

## Qué cambia

<!-- Resumen breve y enlace al issue si existe. -->

## Por qué

<!-- Motivo, contexto y alternativa descartada. -->

## Módulos afectados

<!-- src/<modulo>, docs, web-editor, CI... -->

## Verificación

- [ ] `./gradlew :src:<modulo>:test --no-daemon`
- [ ] `./gradlew checkLocks --no-daemon`
- [ ] `./gradlew :src:host:verifySchema --no-daemon` (si toca configuración/schema)
- [ ] `./gradlew buildAll --no-daemon` (si el cambio es amplio)
- [ ] `cd src/web-editor && npm run check && npm run test:integration` (si toca el editor)
- [ ] No se han commiteado `build/`, `node_modules/`, logs ni secretos
- [ ] Documentación actualizada (`README.md`, `src/<modulo>/README.md` o `docs/`)

## Notas

<!-- Riesgos, deuda pendiente, capturas o detalles de diseño. No incluyas tokens. -->
