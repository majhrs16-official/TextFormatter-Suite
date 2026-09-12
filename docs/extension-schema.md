# Schema extension.yml v1.0 — Validación de extensiones

> El editor y el runtime validan contra este schema. Round-trip exacto: panel → YAML → panel sin pérdida.

## Estructura general

```yaml
# extension.yml — Metadatos y configuración de la extensión
id: example-extension                    # obligatorio, único, kebab-case
name: Example Extension                  # obligatorio, nombre visible
version: "1.0.0"                         # obligatorio, semver
description: "Descripción de la extensión"
author: "Autor"
website: "https://github.com/..."
license: "GPL-3.0"
tags: ["example", "demo", "api"]         # opcional, para filtros
links:                                   # opcional
  wiki: "https://..."
  issues: "https://..."
minSuiteVersion: "2.1.0"                 # versión mínima de TextFormatter Suite
maxSuiteVersion: ""                      # opcional, versión máxima compatible
stable: true                             # true = release estable, false = beta/alpha
experimental: false                      # usa APIs experimentales

# Configuración de carga
mainClass: "me.majhrs16.suite.example.ExampleExtension"  # clase principal
autoLoad: true                           # cargar automáticamente al iniciar

# Dependencias
dependencies: []                         # lista de IDs de extensiones requeridas

# Capabilities (intercambio de servicios entre extensiones)
provides:                                # capabilities que esta extensión provee
  - capability: custom-command
    version: "1.0.0"
requires:                                # capabilities que esta extensión requiere
  - capability: custom-channel
    version: "1.0.0"

# Configuración editable por el usuario (schema JSON para web-editor)
configSchema:
  type: object
  properties:
    enabled:
      type: boolean
      default: true
      description: "Habilitar la extensión"
    logMessages:
      type: boolean
      default: false
      description: "Registrar mensajes en log"
    customMessage:
      type: string
      default: "Hello from example extension!"
      description: "Mensaje personalizado"
  required: ["enabled"]
```

## Reglas de validación

| Campo | Requerido | Tipo | Validación |
|-------|-----------|------|------------|
| id | ✅ | string | kebab-case, único, 1-64 chars |
| name | ✅ | string | 1-128 chars |
| version | ✅ | string | semver válido |
| description | ❌ | string | max 512 chars |
| author | ❌ | string | max 128 chars |
| website | ❌ | string | URL válida |
| license | ❌ | string | SPDX válido |
| tags | ❌ | array[string] | kebab-case, max 10 |
| links | ❌ | object | claves string, valores URLs |
| minSuiteVersion | ✅ | string | semver |
| maxSuiteVersion | ❌ | string | semver |
| stable | ✅ | boolean | |
| experimental | ✅ | boolean | |
| mainClass | ✅ | string | FQCN válido |
| autoLoad | ✅ | boolean | |
| dependencies | ❌ | array[string] | IDs válidos, no circulares |
| provides/requires | ❌ | array[capability] | capability válida |
| configSchema | ❌ | object | JSON Schema draft-07 |

## Capabilities estándar

| Capability | Descripción | Versión |
|------------|-------------|---------|
| custom-command | Comando personalizado /suite | 1.0.0 |
| custom-channel | Canal personalizado | 1.0.0 |
| translation-provider | Proveedor de traducción | 1.0.0 |
| sync-sink | Sink de sincronización | 1.0.0 |
| custom-event | Evento personalizado | 1.0.0 |

## Archivos de la extensión (ZIP)

```
example-extension.zip
├── extension.yml          # obligatorio
├── config.yml             # configuración por defecto
├── plugin.jar             # código compilado
└── assets/                # recursos opcionales
```

## Validación en runtime

1. **Descubrimiento**: leer `extension.yml` del JAR (MANIFEST.MF o archivo separado)
2. **Validación**: schema YAML + reglas de negocio (deps, capabilities, versiones)
3. **Resolución de dependencias**: orden topológico, detección de ciclos
4. **Carga**: classloader aislado, inyección de `ExtensionContext`
5. **Habilitación**: `onEnable(ExtensionContext)`
6. **Hot-reload**: `onConfigReload(ExtensionConfig)`

## Integración con web-editor

- **Panel Extensions**: lista, habilitar/deshabilitar, recargar, config
- **Import/Export**: incluye `extensions/*.yml` en el ZIP
- **Validación**: issues con shape `[{nivel, grupo, ruta, mensaje}]`
- **Schema UI**: genera formulario desde `configSchema` (JSON Schema draft-07)

## Ejemplo completo

```yaml
# extension.yml
id: custom-translator
name: Custom Translator
version: "1.2.0"
description: "Proveedor de traducción personalizado usando API externa"
author: "DevTeam"
website: "https://github.com/example/custom-translator"
license: "MIT"
tags: ["translation", "provider", "custom"]
links:
  wiki: "https://github.com/example/custom-translator/wiki"
  issues: "https://github.com/example/custom-translator/issues"
minSuiteVersion: "2.1.0"
maxSuiteVersion: ""
stable: true
experimental: false
mainClass: "com.example.CustomTranslatorExtension"
autoLoad: true
dependencies: []
provides:
  - capability: translation-provider
    version: "1.0.0"
requires: []
configSchema:
  type: object
  properties:
    apiKey:
      type: string
      description: "API Key del proveedor externo"
    endpoint:
      type: string
      format: uri
      default: "https://api.example.com/translate"
      description: "Endpoint de la API de traducción"
    languages:
      type: array
      items:
        type: string
      default: ["en", "es", "fr", "de"]
      description: "Idiomas soportados"
    timeout:
      type: integer
      minimum: 100
      maximum: 30000
      default: 5000
      description: "Timeout en milisegundos"
  required: ["apiKey"]
```