# transport — Transport Abstraction & HTTP Implementation

> **Purpose**: Defines the `Transport` abstraction and provides `HttpTransport` implementation. Used by sync modules and translation providers for outbound HTTP communication. Uses `HttpURLConnection` (not `java.net.http.HttpClient`) to avoid module accessibility issues in plugin classloaders (Paper/Spigot).

---

## 1. Responsibilities

- **Transport abstraction** — `Transport` interface: `send(request) → response`
- **HTTP transport** — `HttpTransport` implementation using `HttpURLConnection` (avoids `java.net.http.HttpClient` module accessibility issues in plugin classloaders)
- **SSRF Protection** — Validates URLs against private/internal address ranges (RFC 1918, RFC 3927, RFC 6598, loopback, multicast) using `InetAddress.getAllByName()` to prevent DNS rebinding attacks
- **Message codec** — `MessageCodec` interface for encoding/decoding messages
- **Configuration** — timeouts, headers, SSRF deny/allow lists via `HttpTransport` config

---

## 2. Non-Responsibilities

- **No business logic** — pure transport layer
- **No retry logic** — callers handle retries
- **No authentication** — callers add auth headers
- **No platform-specific code** — pure Java 11+ (`HttpURLConnection`)

---

## 3. Dependencies

| Dependency | Type | Reason |
|------------|------|--------|
| `core-api` | Compile | `Message` type for codec |
| `org.json` | Compile | JSON handling in `MessageCodec` |

---

## 4. Consumers

| Consumer | Usage |
|----------|-------|
| `sync-http` | `HttpTransport` for webhook delivery |
| `sync-tcpudp` | `MessageCodec` for TCP/UDP framing |
| `sync-discord` | `MessageCodec` for Discord payload encoding |
| `gtranslate` | `HttpTransport` for Google API calls |
| `ltranslate` | `HttpTransport` for LibreTranslate API calls |

---

## 5. Main Components

| Component | Role |
|-----------|------|
| `Transport` | Interface: `send(TransportRequest) → TransportResponse` |
| `HttpTransport` | Implementation: `java.net.http.HttpClient` wrapper |
| `TransportRequest` | Request model: URL, method, headers, body |
| `TransportResponse` | Response model: status, headers, body |
| `MessageCodec` | Interface: `encode(Message) → bytes`, `decode(bytes) → Message` |
| `MessageCodec` (impl) | JSON-based codec for `Message` serialization |

---

## 6. Data Flow

```text
Caller (sync module, translation provider)
         ↓
HttpTransport.send(TransportRequest)
         ↓
HttpURLConnection.openConnection() → configure timeouts, headers, SSRF validation
         ↓
If POST: write request body to output stream
         ↓
Follow redirects manually (max 5) with SSRF validation on each redirect
         ↓
Read response → TransportResponse
         ↓
MessageCodec.decode() if needed
         ↓
Return to caller
```

> **SSRF Protection**: All URLs (initial + redirects) validated against deny patterns (private ranges, loopback, link-local, multicast). Uses `InetAddress.getAllByName()` to resolve all IPs and prevent DNS rebinding.

---

## 7. Entry Points

| Entry Point | Location | Called By |
|-------------|----------|-----------|
| `HttpTransport` constructor | `HttpTransport.java` | Sync modules, translation providers |
| `MessageCodec` implementations | `MessageCodec.java` | Same |

---

## 8. Extension Points

| Extension Point | How to Extend |
|-----------------|---------------|
| Custom `Transport` | Implement `Transport` interface (e.g., WebSocket, gRPC) |
| Custom `MessageCodec` | Implement `MessageCodec` for different wire formats (Protobuf, etc.) |
| HTTP client config | Configure `HttpClient` builder in `HttpTransport` |

---

## 9. Exploration Path

```
1. Transport.java                 → Transport interface
2. HttpTransport.java             → HTTP implementation
3. TransportRequest/Response.java → Request/response models
4. MessageCodec.java              → Codec interface
5. sync-http/HttpSink.java        → Usage example
6. gtranslate/GTranslate.java     → Usage example
```

---

## 10. Related Modules

- [core-api](../core-api/README.md) — `Message` type for codec
- [sync-http](../sync-http/README.md) — Webhook sync sink
- [sync-tcpudp](../sync-tcpudp/README.md) — TCP/UDP sync sinks
- [sync-discord](../sync-discord/README.md) — Discord sync sink
- [gtranslate](../gtranslate/README.md) — Google Translate
- [ltranslate](../ltranslate/README.md) — LibreTranslate