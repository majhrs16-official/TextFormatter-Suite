# tester — Runtime Pipeline Test Service

> **Purpose**: Runs production runtime scenarios (routing, translation, formatting, delivery, events and stress) through a neutral `TestProbe` port, without compiling against Paper, Bukkit, Fabric or the host.

---

## 1. Responsibilities

- **Runtime scenarios** — module discovery, channel registry, actor directory, chat, world/radius/specific routing, permissions, join/quit/death/advancement events, translation and formatting hot paths
- **Benchmarking** — concurrency, stress, burst and performance profiling
- **Neutral probe** — `TestProbe` exposes only `ChannelRegistry`, `Router`, dispatch counters, formatting and language detection
- **Service factory** — `TestServiceFactory` builds `TestService` from the host adapter's probe, actor directory, language store and logger
- **Runtime activation** — `TesterActivator` publishes the factory as a scoped service without starting threads, sockets or server work
- **Module descriptor** — `TesterModule` declares the `test-service` capability

---

## 2. Non-Responsibilities

- **No platform API** — no Paper, Bukkit, Fabric or Adventure imports in `src/main`
- **No host internals** — no `SuiteHost`, `MessageDispatcher`, `ChatDelivery` or `DispatchReport`
- **No harness-only tooling** — C7: `tester` is a production package, not a test-only artifact
- **No command tree** — the host adapter owns `/suite test ...` and supplies the probe

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message`, `Actor`, `ChannelRegistry`, `Router`, SPI types |
| `junit-jupiter` | Test | Unit tests |
| `assertj` | Test | Fluent assertions |

The distribution is a `PACKAGE` with no package dependencies. Its descriptor requires `channel-api`, `router` and `formatter`; `textformatter` provides `channel-api` + `formatter`, and the installed iFlow engine provides `router`.

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `spigot-host` | Builds `TestService` with `SpigotTestProbe` and the live host |
| `suite-runtime` | Installs/activates the `tester` package and publishes `TestServiceFactory` |
| Future `fabric-host` | Must implement its own `TestProbe`; no platform types enter this package |

The host still constructs the service directly while F4b is open. The package already exposes the neutral factory for the final host wiring; `SpigotTestProbe` now binds only `ChannelRegistry`, `Router`, `MessageRenderer`, `MessageDispatch` and `TranslationService`.

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `TestService` | Scenario runner, benchmarks and report generation |
| `TestProbe` | Neutral port to routing, channels, formatting, translation and dispatch |
| `DispatchOutcome` (`core-api`) | Delivered/silenced/redirected counters shared with the dispatch port |
| `TestServiceFactory` | Creates `TestService` for a host adapter |
| `TesterActivator` | `ModuleActivator`; registers the factory and declares capabilities |
| `TesterModule` | `Module` descriptor for SPI discovery |
| `PerformanceProfiler` | Timing/memory helper used by the scenarios |

---

## 6. Data Flow

```text
Host adapter ──→ SpigotTestProbe/FabricTestProbe ──→ TestProbe
                                                   │
TestServiceFactory ──→ TestService ────────────────┤
        │                                          │
        ├── channels()/router()/ready()            │
        ├── dispatch(Message) ──→ live dispatcher  │
        ├── format(Message) ──→ live formatter     │
        └── detectLanguage(text) ──→ live translator
```

`TestService` never receives a host object. The adapter translates the neutral calls into the live platform pipeline and returns `DispatchOutcome` counters.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `TestServiceFactory.create(...)` | `TestServiceFactory.java` | Host adapter |
| `TestService.runFullTestSuite(...)` | `TestService.java` | `/suite test full` (host command) |
| `TestService.runStressTest(...)` | `TestService.java` | `/suite test stress` |
| `TesterActivator.start(...)` | `TesterActivator.java` | `ModuleHost` when the package activates |
| `TesterModule.descriptor()` | `TesterModule.java` | Kernel SPI discovery |

---

## 8. Limitations

- `fabric-host` does not wire the tester yet; its adapter must implement `TestProbe`.
- The host adapter still constructs `TestService` directly; moving it to the runtime service registry is F4b.
- Scenarios depend on the live host having `chat.global`, `join`, `quit`, `death` and `advancement` channels.
- No E2E validation runs against a real Spigot/Paper/Fabric server in CI.

---

## 9. Tests

`./gradlew :src:tester:test` covers:

- `TestServiceFactory` construction and neutral probe wiring
- `TesterActivator` publishing the factory without platform work
- Full scenario suite through a fake probe with real `ChannelRegistry`/`Router` contracts
- Dispatch counters, event channels and stress/concurrency paths
- Distribution packaging through `modulePackage`

---

## 10. Exploration Path

```
1. TestProbe.java          → Neutral host port
2. TestService.java        → Scenarios, benchmarks and reports
3. TestServiceFactory.java → Host-facing construction
4. TesterActivator.java    → Runtime activation and service registration
5. TesterModule.java       → Module descriptor/capability
6. SpigotTestProbe.java    → Live Spigot adapter (in spigot-host)
```

---

## 11. Related Modules

- [core-api](../core-api/README.md) — `Message`, `Actor`, `ChannelRegistry`, `Router`, SPI
- [textformatter](../textformatter/README.md) — Live formatter used by the probe; provides `formatter`
- [iflow-vm](../iflow-vm/README.md) — Live router; provides `router`
- [spigot-host](../spigot-host/README.md) — `SpigotTestProbe` and `/suite test` command
- [fabric-host](../fabric-host/README.md) — Future `TestProbe` adapter
