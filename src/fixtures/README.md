# fixtures — datos de referencia para tests

> **Purpose**: artefactos compartidos de referencia (*golden files*) que usan
> los tests y las herramientas de validación.

---

## 1. Contents

| Path | Content |
|------|---------|
| `golden/render-cases.json` | Casos de referencia para el renderizado de mensajes |

## 2. Nature

No es un módulo Gradle: no tiene `build.gradle`, código Java ni tests propios.
Es un directorio de datos. Cualquier módulo que lo consuma debe declarar la
ruta desde la raíz del repositorio y no copiarlo a producción.

## 3. Rules

- Los archivos son inmutables una vez publicados; un cambio de contrato se
  refleja en un archivo nuevo o en una revisión explícita del caso.
- No se incluyen secretos, rutas absolutas ni datos personales.
- Si un test necesita un caso nuevo, se añade aquí y se referencia desde el
  test; no se duplica el JSON en el módulo.
