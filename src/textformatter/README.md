# TextFormatter Suite — TextFormatter

## Purpose

The `textformatter` module is the **message formatting engine**. It handles:
- MiniMessage parsing and rendering
- `<tr>` translation tag processing
- Placeholder resolution
- Channel-specific format templates
- Sound specification playback

## Key Components

### TextFormatter (Interface)
```java
Component format(Message message, TemplateContext context);
```

### TextFormatters (Factory)
```java
TextFormatter create(ChannelRegistry channels, TranslationService translation,
                     PlaceholderResolver placeholders, PluginLogger logger);
```

### TemplateRenderer
- Parses MiniMessage templates
- Resolves placeholders (`%player_name%`, `%content%`, `%lang_source%`, etc.)
- Handles `<tr>` tags → delegates to `TranslationService`
- Applies channel format templates (sender, message, tooltips)

### MiniMessage Integration
- Uses `net.kyori:adventure-text-minimessage`
- Custom tags: `<tr>`, `<gradient>`, `<hover>`, `<click>`
- Legacy `&` color codes supported via `MiniEscape`

### MiniEscape
**Security-critical**: Escapes MiniMessage special characters to prevent injection.
Escapes: `< > \ { } [ ] ( ) # @`

### Channel Format Templates
Per-channel message arrays:
- `messages[]` — Main chat format (sender + content)
- `tooltips[]` — Hover text
- `showSender` — Whether to show sender name

## Pipeline Integration

```
SuiteHost.deliver()
    → Resolve recipient language
    → Router.route() (iFlow)
    → TextFormatter.format(message, context)
       → TemplateRenderer.renderPlain()
          → Parse MiniMessage
          → Resolve placeholders
          → Process <tr> tags (translation)
          → Apply channel format
    → ChatDelivery.deliver()
```

## Configuration

Channel format in `channels/<name>.yml`:
```yaml
messages:
  - '<gray>%player_name%: <tr>%content%</tr></gray>'
tooltips:
  - 'Hover: %lang_source% → %lang_target%'
show-sender: true
```

## Testing

Run: `./gradlew :src:textformatter:test`

Key tests:
- `TemplateRendererTest` — MiniMessage parsing, placeholder resolution, `<tr>` tags
- `MiniEscapeTest` — Injection prevention, all special chars escaped
- `ChannelRegistryTest` — Channel loading, type filtering