# sync-velocity-velocity — Velocity plugin adapter

Thin Velocity plugin around the neutral
[`sync-velocity`](../sync-velocity/README.md) package. This is the only
project in the Velocity path that imports `com.velocitypowered`.

---

## 1. Responsibilities

- Implement the Velocity plugin lifecycle (`ProxyInitializeEvent` /
  `ProxyShutdownEvent`).
- Register and unregister the plugin-message channel.
- Translate `PluginMessageEvent` payloads into `VelocityListener` callbacks.
- Resolve target server names and call `sendPluginMessage`.
- Package the neutral classes into the plugin JAR, because Velocity plugins are
  loaded by the proxy rather than by the suite module runtime.

## 2. Non-responsibilities

- No queue, retry, mapping, metrics or health logic.
- No suite routing.
- No direct `SyncBus` registration.

---

## 3. Components

| Component | Role |
|-----------|------|
| `VelocityPlugin` | `@Plugin` entry point; wires config, connection and sink. |
| `VelocityProxyConnection` | `VelocityConnection` implementation over `ProxyServer`. |
| `VelocityProxyConnectionFactory` | Factory for hosts that expose the adapter as a service. |
| `Slf4jPluginLogger` | `PluginLogger` backed by slf4j. |

---

## 4. Configuration

The plugin uses the same neutral config:
`config/sync-velocity/config.yml`, with legacy `sync/velocity.yml` fallback.
The defaults resource is `defaults/sync/velocity.yml`.

`enabled: false` keeps the plugin loaded but does not start the sink.

---

## 5. Build

The Gradle `jar` task builds a self-contained plugin JAR:

- neutral `sync-velocity`, `core-api`, `sync-common`, `transport` and `org.json`
  are shaded into the JAR;
- `velocity-api` is `compileOnly` and provided by the proxy;
- `velocity-plugin.json` points to
  `me.majhrs16.suite.syncvelocity.velocity.VelocityPlugin`.

---

## 6. Tests

`VelocityProxyConnectionTest` uses Mockito to verify:

- channel and listener registration/unregistration;
- only the configured channel is forwarded;
- server discovery and `sendPluginMessage`;
- idempotent close.

No real Velocity proxy or network is required.
