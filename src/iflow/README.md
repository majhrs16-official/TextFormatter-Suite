# iflow — Message Routing, Rules & Rate Limiting

> **Purpose**: Message routing engine. Evaluates rules to transform, redirect, block, or rate-limit messages before they reach the formatting pipeline.

---

## 1. Responsibilities

- **Rule evaluation** — scriptable rules (`Rule`) with conditions and transform operations
- **Message routing** — `Router` interface decides `RouteDecision` (allow, deny, redirect, transform)
- **Rate limiting** — per-actor/channel rate limiting via `RateLimiter`
- **Permission checks** — `PermissionChecker` for platform-agnostic permission verification
- **Module registration** — `IflowModule` registers `Router` implementation

---

## 2. Non-Responsibilities

- **No formatting** — delegates to `textformatter` (`TextFormatter`)
- **No translation** — delegates to `TranslationService` (via `host`)
- **No sync/transport** — delegates to `SyncSink` (via `host`)
- **No platform-specific logic** — `PermissionChecker` is SPI implemented by platform adapters

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message`, `Actor`, `Channel`, `PermissionChecker` SPI |
| `textformatter` | Compile | `TextFormatter` for transform operations |
| `spring-expression` | Compile | SpEL for rule conditions |
| `jackson-databind` | Compile | Rule serialization (YAML/JSON) |
| `kernel` | Test | Test fixtures |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Uses `Router` via `IflowModule` in `MessageDispatcher` |
| `spigot-host` | Uses routing via `host` |
| `fabric-host` | Uses routing via `host` |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Router` | Interface: `route(message, actor, channel) → RouteDecision` |
| `DefaultRouter` | Default implementation: evaluates rules in order, applies first match |
| `RouteDecision` | Outcome: `ALLOW`, `DENY`, `REDIRECT(channel)`, `TRANSFORM(newMessage)` |
| `RouteOutcome` | Result wrapper with decision + modified message + metadata |
| `Rule` | Single rule: `condition` (SpEL) + `operations` (TransformOp[]) |
| `TransformOp` | Transform operation: `SET_FORMAT`, `SET_CHANNEL`, `TRANSLATE`, `BLOCK`, `REDIRECT`, `SCRIPT` |
| `ScriptSurface` | Exposes variables to SpEL conditions (`message`, `actor`, `channel`, `config`, etc.) |
| `RateLimiter` | Token bucket rate limiter per actor/channel |
| `PermissionChecker` | SPI: `hasPermission(actor, permission) → boolean` |
| `PolicyTarget` | Target for rate limiting/permissions (actor, channel, global) |
| `IflowModule` | `Module` implementation; registers `DefaultRouter` |

---

## 6. Data Flow

```text
MessageDispatcher.dispatch(message)
         ↓
Router.route(message, actor, channel)
         ↓
DefaultRouter: for each Rule in order
         ↓
ScriptSurface builds SpEL context {message, actor, channel, ...}
         ↓
Rule.condition.evaluate(context) → boolean
         ↓ (if true)
Rule.operations[] applied sequentially:
  - SET_FORMAT → changes format template
  - SET_CHANNEL → redirects to different channel
  - TRANSLATE → marks for translation
  - BLOCK → RouteDecision.DENY
  - REDIRECT → RouteDecision.REDIRECT
  - SCRIPT → custom SpEL script
         ↓
RateLimiter.checkLimit(actor, channel) → allow/deny
         ↓
PermissionChecker.hasPermission(actor, perm) → allow/deny
         ↓
RouteDecision returned
         ↓
MessageDispatcher applies decision:
  - ALLOW → continue to formatting
  - DENY → drop message
  - REDIRECT → re-route to new channel
  - TRANSFORM → use modified message
```

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `IflowModule` | `IflowModule.java` | `ModuleLoader` → `SuiteBootstrap` |
| `DefaultRouter` | `DefaultRouter.java` | `MessageDispatcher.dispatch()` |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom `Router` | Implement `Router` interface, register via custom `Module` |
| Custom `TransformOp` | Add enum value to `TransformOp`, handle in `DefaultRouter` |
| Custom `PermissionChecker` | Implement `PermissionChecker` SPI (core-api), platform adapter provides |
| Custom rate limiting | Extend `RateLimiter` or replace with custom implementation |
| Rule scripts | Write SpEL expressions in rule config (YAML) |

---

## 9. Exploration Path

```
1. Router.java / DefaultRouter.java     → Routing interface & default impl
2. RouteDecision.java / RouteOutcome.java → Decision types
3. Rule.java / TransformOp.java         → Rule model & operations
4. ScriptSurface.java                   → SpEL context variables
5. RateLimiter.java                     → Rate limiting algorithm
6. PermissionChecker.java               → Permission SPI (core-api)
7. PolicyTarget.java                    → Rate limit/permission targets
8. IflowModule.java                     → Module registration
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — Defines `PermissionChecker`, `Message`, `Actor`, `Channel`
- [textformatter](../textformatter/README.md) — Used for `SET_FORMAT` transforms
- [host](../host/README.md) — Wires `Router`, provides `PermissionChecker` impl
- [kernel](../kernel/README.md) — Loads `IflowModule`