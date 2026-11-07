# chattranslator — Translation Facade

## Purpose

`chattranslator` owns the translation facade. It builds a `TranslatorManager`
from the `TranslatorProvider` services published by provider packages, registers
`ChatTranslator` and `TranslationService` in the module scope, keeps the manager
in sync with the module lifecycle, and publishes the `translate` pipeline stage
so translation runs per recipient before formatting.

## Runtime activation

- Entry point: `ChatTranslatorActivator`.
- Package type: `PACKAGE` (`modulePackage` produces the distribution ZIP).
- Configuration: `<data>/config/chattranslator/config.yml` and
  `<data>/config/chattranslator/translators/*.yml`.
- Providers: `gtranslate` and `ltranslate` are separate packages. They require
  `chattranslator` and publish `TranslatorProvider` in the `ServiceRegistry`.
- Hot refresh: the activator subscribes to `ModuleActivatedEvent` and
  `ModuleDeactivatedEvent`. When a provider package is installed, activated,
  unloaded or upgraded, `BackendConfig` reloads the configured backends and
  `TranslatorManager.replaceAll(...)` swaps the provider view without
  restarting the host.
- `ServiceLoader` remains a fallback for providers bundled in the facade JAR;
  runtime providers always come from the service registry.

## Key classes

| Class | Responsibility |
|-------|----------------|
| `ChatTranslatorActivator` | Registers the facade/service, tracks resources and subscribes to lifecycle events |
| `ChatTranslatorConfig` | Loads the facade configuration |
| `BackendConfig` | Reads `config/chattranslator/translators/*.yml`, validates provider config and creates translators |
| `ChatTranslator` | Public facade used by the host |
| `TranslationService` | Translation/detection service with caches and executor |
| `TranslateStage` | `translate` stage: translates `Message.texts()` per recipient before `format` |

## Tests

- `ChatTranslatorActivatorTest`: activation, provider discovery, configuration
  and tracked resource release.
- `TranslateStageTest`: per-recipient translation, preserved formats,
  `NOT_APPLICABLE` cases and non-mutation.
- `BackendConfigTest`: backend configuration and provider selection.
- `TranslationServiceCacheTest` (in `core-api`): cache contract.

## Related modules

- [core-api](../core-api/README.md) — `TranslatorProvider`, `TranslatorManager`,
  `TranslationService` and lifecycle contracts.
- [gtranslate](../gtranslate/README.md) — Google provider package.
- [ltranslate](../ltranslate/README.md) — LibreTranslate provider package.
- [suite-runtime](../suite-runtime) — package installation, activation and
  lifecycle events.
