# textformatter — Template Engine & Formatting Pipeline

> **Purpose**: Core text formatting engine. Handles MiniMessage template rendering, Spring Expression Language (SpEL) evaluation, and channel-based formatting configuration.

---

## 1. Responsibilities

- **Template rendering** — parse and render MiniMessage templates with placeholders
- **Expression evaluation** — evaluate SpEL expressions in templates (via `ExpressionEvaluator` SPI)
- **Channel registry** — manage named channels with per-channel format configuration
- **Formatting pipeline** — `TextFormatter` interface orchestrating template + expression + channel
- **Module registration** — `TextFormatterModule` registers `ExpressionEvaluator` and `TextFormatter` implementations

---

## 2. Non-Responsibilities

- **No message routing** — that's `iflow`
- **No translation** — that's `gtranslate`/`ltranslate` implementing `TranslationService`
- **No platform-specific code** — pure Java, uses Adventure/MinMessage library
- **No configuration loading** — formats come from `host` config via `ChannelRegistry`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `ExpressionEvaluator` SPI, `Channel`, `Message` types |
| `net.kyori:adventure-text-minimessage` | Compile | MiniMessage parsing/rendering |
| `net.kyori:adventure-text-serializer-plain` | Compile | Plain text serialization |
| `org.springframework:spring-expression` | Compile | SpEL evaluation |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Uses `TextFormatter` via `TextFormatterModule` |
| `iflow` | Uses `TextFormatter` for transform operations |
| `presets` | Uses `TextFormatter` and `ChannelRegistry` for preset transforms |
| `spigot-host` | Uses formatting pipeline via `host` |
| `fabric-host` | Uses formatting pipeline via `host` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TextFormatter` | Interface: `format(message, channel, context) → formatted message` |
| `DefaultTextFormatter` | Default implementation: template → expressions → channel format |
| `TextFormatters` | Factory/utilities for creating formatters |
| `Template` | Parsed MiniMessage template (immutable) |
| `TemplateRenderer` | Renders `Template` with `TemplateContext` (placeholders + expressions) |
| `TemplateContext` | Context for rendering: placeholders, variables, actor info |
| `SpelExpressionEvaluator` | Implements `ExpressionEvaluator` SPI using Spring SpEL |
| `Channel` | Channel definition (name, format template, color mode, etc.) |
| `ChannelRegistry` | Registry of channels; loads from config; `getChannel(name)` |
| `TextFormatterModule` | `Module` implementation; registers `SpelExpressionEvaluator` and `DefaultTextFormatter` |
| `MiniEscape` | Utility for escaping MiniMessage tags |

---

## 6. Data Flow

```text
Message + Channel + Context
         ↓
DefaultTextFormatter.format()
         ↓
TemplateRenderer.render(Template, TemplateContext)
         ↓  (MiniMessage parsing)
MiniMessage template with {placeholders} and ${expressions}
         ↓
SpelExpressionEvaluator.evaluate() for each ${...}
         ↓
PlaceholderResolver.resolve() for each {...}  (from core-api SPI)
         ↓
Channel format applied (from ChannelRegistry)
         ↓
Formatted message (Adventure Component)
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `TextFormatterModule` | `TextFormatterModule.java` | `ModuleLoader` (kernel) → `SuiteBootstrap` (host) |
| `DefaultTextFormatter` | `DefaultTextFormatter.java` | `MessageDispatcher` (host) → `Router` (iflow) |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom `TextFormatter` | Implement `TextFormatter` interface, register via custom `Module` |
| Custom `ExpressionEvaluator` | Implement `ExpressionEvaluator` SPI (core-api), register via `Module` |
| Custom channel formats | Configure via `ChannelRegistry` (YAML config in `host`) |
| Custom template functions | Extend `TemplateContext` with additional variables/functions |

---

## 9. Exploration Path

```
1. TextFormatter.java             → Main interface
2. DefaultTextFormatter.java      → Default implementation, formatting pipeline
3. TemplateRenderer.java          → Template rendering logic
4. Template.java / TemplateContext.java → Template model & rendering context
5. SpelExpressionEvaluator.java   → SpEL expression evaluation (implements SPI)
6. Channel.java / ChannelRegistry.java → Channel model & registry
7. TextFormatterModule.java       → Module registration
8. TextFormatters.java            → Factory utilities
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `ExpressionEvaluator`, `Channel`, `Message` SPI
- [kernel](../kernel/README.md) — Loads `TextFormatterModule`
- [host](../host/README.md) — Wires `TextFormatter`, loads channel config
- [iflow](../iflow/README.md) — Uses `TextFormatter` for transform rules
- [presets](../presets/README.md) — Uses `TextFormatter` and `ChannelRegistry` for presets

---

## 11. Security Fixes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **B-04** | İ (U+0130) corruption in `<tr>` span search | `TemplateRenderer.findSpans()` — uses `Pattern.CASE_INSENSITIVE` instead of `toLowerCase()` |
| **B-05** | Translation output not re-escaped | `TemplateRenderer.translateSpan()` — applies `MiniEscape.escape()` to translated text |
| **SEC** | `MiniEscape` complete (10 chars: `< > \ { } [ ] ( ) # @`) | `MiniEscape.escape()` — prevents MiniMessage injection |
| **SEC** | `SpelExpressionEvaluator` sandboxed | Uses `SimpleEvaluationContext.forReadOnlyDataBinding()` + LRU cache (1024) |