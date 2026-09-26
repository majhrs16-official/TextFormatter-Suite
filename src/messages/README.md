# messages — Message Catalog & Serialization

> **Purpose**: Defines serialized message formats and catalog for cross-platform message exchange.

---

## 1. Responsibilities

- **Message serialization formats** — defines how `Message` objects are encoded/decoded
- **Catalog of message types** — centralized message definitions
- **No dependencies** — pure data definitions

---

## 2. Non-Responsibilities

- **No runtime logic** — only data structures
- **No platform code** — pure Java
- **No configuration** — static definitions

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| *(none)* | — | Zero dependencies |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `host` | Message catalog for config |
| `spigot-host` | Message serialization for sync |
| `fabric-host` | Message serialization for sync |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `MessagesCatalog` | Catalog of message formats, serialization helpers |

---

## 6. Data Flow

Static definitions only — used by `MessageCodec` in `transport` and sync modules for encoding/decoding.

---

## 7. Entry Points

None — static library.

---

## 8. Exploration Path

```
1. MessagesCatalog.java → Message format definitions
```

---

## 9. Related Modules

- [transport](../transport/README.md) — Uses catalog for `MessageCodec`
- [sync-http](../sync-http/README.md) / [sync-tcpudp](../sync-tcpudp/README.md) / etc. — Serialize messages
- [host](../host/README.md) — References catalog in config