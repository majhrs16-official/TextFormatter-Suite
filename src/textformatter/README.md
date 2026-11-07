# textformatter — Template Engine & Formatting Pipeline

> **Purpose**: Core text formatting engine. Renders MiniMessage templates with placeholders and channel-based formatting configuration. It does **not** evaluate inline scripting expressions: policy logic belongs to the iFlow VM.

---

## 1. Responsibilities

- **Template rendering** — parse and render MiniMessage templates with built-in and external placeholders
- **Channel registry** — manage named channels with per-channel format configuration
- **Formatting pipeline** — `TextFormatter` interface orchestrating template + channel lookup
- **Neutral plain-text port** — `MessageRenderer` lets platform adapters render lines without importing textformatter types
- **Translation spans** — translate `<tr>...</tr>` content through `TranslationService`
- **Local regex condition** — `<regex match="PATRÓN">CONTENIDO</regex>` renders its content only when the pre-compiled pattern finds a match (ADR-028)
- **Module registration** — `TextFormatterModule` registers the module descriptor for the kernel

---

## 2. Non-Responsibilities

- **No message routing or policy evaluation** — that's `iflow-vm` (VM bytecode `.flow`)
- **No inline scripts/SpEL** — templates are data, not executable expressions
- **No translation providers** — `gtranslate`/`ltranslate` implement `TranslationService`
- **No platform-specific code** — pure Java, uses Adventure/MinMessage
- **No configuration loading** — formats come from `host` config via `ChannelRegistry`

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Channel`, `Message`, `TranslationService`, SPI types |
| `net.kyori:adventure-text-minimessage` | Compile | MiniMessage parsing/rendering |
| `net.kyori:adventure-text-serializer-plain` | Compile | Plain text serialization |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Uses `TextFormatter` during dispatch/rendering |
| `iflow-vm` | Uses `TextFormatter` for VM transform operations |
| `tester` | Uses the live formatter through `TestProbe.format(...)` |
| `spigot-host` | Renders in-world lines through the neutral `MessageRenderer` port |
| `fabric-host` | Uses the formatting pipeline via `host` |

The package provides the `channel-api` and `formatter` capabilities.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TextFormatter` | Interface: `format(message, channel, context) → formatted message` |
| `DefaultTextFormatter` | Default implementation: template → channel format |
| `TextFormatters` | Factory/utilities for creating formatters |
| `TextFormatterMessageRenderer` | Implements the neutral `MessageRenderer` port with `TextFormatter` + plain-text serialization |
| `Template` | Parsed MiniMessage template (immutable) |
| `TemplateRenderer` | Renders `Template` with `TemplateContext` (placeholders + variables) |
| `TemplateContext` | Context for rendering: placeholders, variables, actor info |
| `Channel` | Channel definition (name, format template, color mode, etc.) |
| `ChannelRegistry` | Registry of channels; loads from config; `getChannel(name)` |
| `TextFormatterModule` | `Module` implementation; registers the module descriptor |
| `MiniEscape` | Utility for escaping MiniMessage tags |

---

## 6. Data Flow

```text
Message + Channel + Context
         ↓
DefaultTextFormatter.format()
         ↓
TemplateRenderer.render(Template, TemplateContext)
         ↓
MiniMessage template with %placeholders%, <regex> and <tr> spans
         ↓
PlaceholderResolver.resolve() for external tokens
         ↓
<regex> condition: emit CONTENIDO only on a match
         ↓
TranslationService.translate() for <tr> spans (when enabled)
         ↓
Formatted message (Adventure Component)
```

Translation is not a separate pipeline stage: `<tr>` spans are resolved inside
`TemplateRenderer` during the `format` stage. `chattranslator` supplies the
`TranslationService`; if it is absent, the span content is rendered unchanged.

Templates intentionally have no `<expr>` or `${...}` scripting step. Policy decisions are executed by the iFlow VM; the formatter only substitutes values.

### `<regex>` template condition (ADR-028)

```text
<regex match="PATRÓN">CONTENIDO</regex>
```

- `match` is a mandatory, non-empty Java regex. Double quotes and XML characters are
  written as `&quot;`, `&amp;`, `&lt;`, `&gt;` and `&apos;`; the pattern is not
  interpreted as MiniMessage. Unknown or malformed XML entities are configuration
  errors.
- `CONTENIDO` is both the candidate text and a MiniMessage template. Placeholders are
  resolved before the pattern is evaluated. If the pattern finds a match
  (`Pattern.matcher(content).find()`), the span emits `CONTENIDO`; otherwise it emits
  nothing. Anchor with `^`/`$` for a full-content match.
- Capture groups are not interpolated automatically and the pattern is never rendered.
- Patterns are compiled once in `Template.of(...)`. An invalid, empty, missing or
  oversized pattern fails when the template is created. Nesting `<regex>` spans is
  rejected.
- Limits: pattern ≤ 512 UTF-16 code units, evaluated content ≤ 8192 UTF-16 code
  units, at most 32 spans per template. The content limit is checked again after
  placeholder expansion.
- The tag only controls rendering of its own span. It does not execute code, evaluate
  SpEL, decide routing/permissions/delivery, mutate the message or replace the regex
  used by iFlow policies.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `TextFormatterModule` | `TextFormatterModule.java` | `ModuleLoader` (legacy descriptor); runtime activates `TextFormatterActivator` |
| `DefaultTextFormatter` | `DefaultTextFormatter.java` | Etapa `format` del pipeline (`FormatStage`) |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom `TextFormatter` | Implement `TextFormatter` interface, register via a custom `Module` |
| Custom channel formats | Configure via `ChannelRegistry` (YAML config in `host`) |
| Custom template variables | Add entries to `TemplateContext.variables()` |
| Custom placeholders | Implement `PlaceholderResolver` in the platform adapter |

---

## 9. Exploration Path

```
1. TextFormatter.java             → Main interface
2. DefaultTextFormatter.java      → Default implementation, formatting pipeline
3. TemplateRenderer.java          → Template rendering logic
4. Template.java / TemplateContext.java → Template model & rendering context
5. Channel.java / ChannelRegistry.java → Channel model & registry
6. TextFormatterModule.java       → Module registration
7. TextFormatters.java            → Factory utilities
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `Channel`, `Message`, translation and placeholder SPI
- [kernel](../kernel/README.md) — Loads `TextFormatterModule`
- [host](../host/README.md) — Wires `TextFormatter`, loads channel config
- [iflow](../iflow/README.md) — VM policies and transform operations
- [presets](../presets/README.md) — Uses `TextFormatter` and `ChannelRegistry` for presets

---

## 11. Security Notes (Audit 2026-09-28)

| Fix | Issue | Location |
|-----|-------|----------|
| **B-04** | İ (U+0130) corruption in `<tr>` span search | `TemplateRenderer.findSpans()` — uses `Pattern.CASE_INSENSITIVE` instead of `toLowerCase()` |
| **B-05** | Translation output not re-escaped | `TemplateRenderer.translateSpan()` — applies `MiniEscape.escape()` to translated text |
| **SEC** | `MiniEscape` complete (10 chars: `< > \ { } [ ] ( ) # @`) | `MiniEscape.escape()` — prevents MiniMessage injection |
| **SEC** | No inline expression evaluator | Removed legacy SpEL path; the only executable policy surface is the iFlow VM |
