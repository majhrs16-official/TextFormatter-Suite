# TextFormatter Suite — iFlow

## Purpose

The `iflow` module is the **rule engine / message router**. It processes messages through a configurable rule graph with:
- Priority-based rule matching
- SpEL conditions
- Transform operations (rewrite, sounds, sleep, redirect, setChannel, etc.)
- Rate limiting per channel+actor
- Asymmetric permissions (send vs receive)

## Key Components

### DefaultRouter
Implements `Router` interface:
```java
RouteOutcome route(Message message, Actor recipient);
```

Evaluation order per recipient:
1. Resolve channel for `message.channel()`
2. Reject if sender lacks channel `sendPermission`
3. Drop if recipient lacks channel `receivePermission`
4. First matching `Rule` wins (by priority, then ID)
5. Default → channel policy + rate limit

### Rule
```yaml
id: "my-rule"
priority: 100
matcher:
  channel: "chat.global"
  sender: ".*"
  receiver: ".*"
  direction: "OTHERS"
condition: "hasPermission && papi != ''"  # SpEL
action: "setChannel('staff')"             # SpEL
transforms:
  - op: "rewrite"
    template: "<red>⚠ %content%</red>"
  - op: "sounds"
    add: ["block.note_block.pling"]
target: "REDIRECT"  # DROP, REJECT, LOG, REDIRECT, CHANNEL_REDIRECT, RATE_LIMIT
redirectChannel: "staff.alert"
```

### Targets
- `DROP` — Silent drop
- `REJECT` — Reject with feedback to sender
- `LOG` — Allow (default)
- `REDIRECT` — Send to console instead of recipient
- `CHANNEL_REDIRECT` — Re-route to different channel
- `RATE_LIMIT` — Apply rate limit budget

### PermissionChecker
```java
boolean has(Actor actor, String permission);
```
- Injected at bootstrap (Spigot: PlaceholderAPI; Fabric: LuckPerms; Test: ALLOW_ALL)

### RateLimiter
- Sliding window per-second per `(channel, actor)`
- Budget = `channel.rateLimitPerSecond()`
- Thread-safe with `ReentrantReadWriteLock` (fixes purge race)

### ScriptSurface / TransformOp
Runtime execution surface for transform operations:
- `rewrite` — Replace message text via MiniMessage template
- `sounds` — Add/remove sounds
- `sleep` — Async delay (non-blocking)
- `setLangSource` / `setLangTarget` — Override translation languages
- `setColorMode` — Color handling
- `setFormatPapi` — PAPI format toggle
- `setChannel` — Change channel (enables CHANNEL_REDIRECT)

## Rule Graph

Rules form a DAG with:
- Input nodes (channel entry points)
- Condition nodes (filter)
- Transform nodes (mutate message)
- Redirect nodes (change target)
- Output nodes (delivery)
- Loop nodes (retry with max-steps guard)

## Configuration

`rules.yml` in config root:
```yaml
guard:
  max-steps: 512
filter:
  dedup-fanout: true
priority: batch-first
nodes:
  - id: n_chat.global
    kind: input
    label: chat.global
  - id: n_cond
    kind: cond
    matcher: { channel: "chat.global" }
  - id: n_transform
    kind: transform
    transforms:
      - op: rewrite
        template: "<green>%content%</green>"
edges:
  - from: n_chat.global
    to: n_cond
```

## Testing

Run: `./gradlew :src:iflow:test`

Key tests:
- `DefaultRouterTest` — Priority, permissions, rate limits, redirects, conditions
- `RuleTest` — Matcher patterns, wildcards, direction kinds
- `RateLimiterTest` — Token bucket, per-key isolation, refill, purge
- `IflowModuleTest` — Module descriptor, capabilities, ServiceLoader registration