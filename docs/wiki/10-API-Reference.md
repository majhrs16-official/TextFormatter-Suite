# API Reference

## Core API

### Package: `me.majhrs16.suite.api`

#### Message

```java
public final class Message {
    public static Builder builder();
    
    // Immutable message
    public UUID id();
    public MessageType type();
    Actor sender();
    Direction direction();
    String channel();
    List<String> texts();           // Formatted texts
    List<String> tooltips();        // Hover texts
    List<SoundSpec> sounds();       // Sounds
    Language langSource();          // Source language
    Language langTarget();          // Target language
    boolean translate();            // Should translate
    boolean formatPapi();           // Use PAPI placeholders
    boolean cancelled();            // Cancelled flag
    String colorMode();             // Color mode
    String formatPath();            // Last format path used
    
    // Builder
    public static class Builder {
        Builder type(MessageType);
        Builder sender(Actor);
        Builder direction(Direction);
        Builder channel(String);
        Builder text(String);
        Builder texts(List<String>);
        Builder tooltips(List<String>);
        Builder sounds(List<SoundSpec>);
        Builder langSource(Language);
        Builder langTarget(Language);
        Builder translate(boolean);
        Builder formatPapi(boolean);
        Builder cancelled(boolean);
        Builder colorMode(String);
        Builder formatPath(String);
        Message build();
    }
}
```

#### MessageType

```java
enum MessageType {
    CHAT, PRIVATE, MENTION, JOIN, LEAVE, DEATH,
    ADVANCEMENT, SIGN, INTERNAL, CUSTOM
}
```

#### Direction

```java
enum Direction {
    INITIATOR,    // Echo to sender
    OTHERS,       // Broadcast to others
    ALL,          // Everyone including sender
    CONSOLE,      // Server console
    WORLD,        // Players in world
    RADIUS,       // Players in radius
    PERMISSION,   // Players with permission
    SPECIFIC      // Specific recipients
}
```

#### Actor

```java
public final class Actor {
    public static Actor console(String name);
    public static Actor player(UUID uuid, String name, Language lang);
    
    UUID uuid();
    String name();
    ActorKind kind();          // PLAYER, CONSOLE, SYSTEM
    Language language();       // Player language
    Object handle();           // Platform-specific handle (Player, CommandSource, etc.)
    
    boolean hasPermission(String permission);
    boolean equals(Object);
    int hashCode();
    String toString();
}
```

#### Language

```java
enum Language {
    AUTO, EN, ES, FR, DE, PT, IT, RU, JA, KO, ZH, ZH_TW, AR, HI, NL, PL, TR, VI, TH, ID;
    
    static Optional<Language> of(String code);
    String code();
    String name();
    String nativeName();
}
```

#### SoundSpec

```java
record SoundSpec(String name, float volume, float pitch) {
    static SoundSpec of(String name);
    static SoundSpec of(String name, float volume, float pitch);
}
```

#### Formats

```java
record Formats(String[] templates, String[] tooltips) {
    static Formats of(String... templates);
    static Formats empty();
}
```

### SPI Interfaces

#### Translator

```java
public interface TranslationService {
    boolean isAvailable();
    String translate(String text, String from, String to);
    String detect(String text);
    CompletableFuture<String> translateAsync(String text, String from, String to);
}
```

#### PlaceholderResolver

```java
public interface PlaceholderResolver {
    String resolve(Actor actor, String input);
    boolean available();
}
```

#### SyncSink

```java
public interface SyncSink {
    String name();
    void start() throws IOException;
    void stop();
    void send(Message message) throws IOException, InterruptedException;
    void setListener(SyncListener listener);
}
```

#### SyncListener

```java
public interface SyncListener {
    void onMessage(SyncSink sink, Message message);
}
```

#### ActorDirectory

```java
public interface ActorDirectory {
    List<Actor> onlinePlayers();
    Optional<Actor> byUuid(UUID uuid);
    Optional<Actor> byName(String name);
    Actor console();
    List<Actor> playersInWorld(String world);
    List<Actor> playersNear(Actor center, double radius);
}
```

#### PluginLogger

```java
interface PluginLogger {
    void info(String message, Object... args);
    void warn(String message, Object... args);
    void error(String message, Object... args);
    void error(String message, Throwable throwable);
    void debug(String message, Object... args);
}
```

#### Module

```java
interface Module {
    ModuleDescriptor descriptor();
}
```

#### ModuleDescriptor

```java
record ModuleDescriptor(
    String id,
    String name,
    SemVer version,
    SemVer contractVersion,
    int jvmRangeMin,
    Set<Capability> provides,
    Set<Capability> requires,
    String mainClass
) {
    static Builder builder(String id);
}
```

#### Capability

```java
record Capability(String name, SemVer version) {
    static Capability of(String name, SemVer version);
}
```

#### SemVer

```java
final class SemVer implements Comparable<SemVer> {
    static SemVer parse(String);
    static SemVer of(int major, int minor, int patch);
    
    int major();
    int minor();
    int patch();
    boolean satisfies(SemVer range);
    int compareTo(SemVer other);
    String toString();
}
```

#### ExpressionEvaluator

```java
interface ExpressionEvaluator {
    String evaluate(String expression, Map<String, Object> bindings)
        throws ExpressionEvaluationException;
    
    Object evaluateObject(String expression, Map<String, Object> bindings)
        throws ExpressionEvaluationException;
}

class ExpressionEvaluationException extends Exception {}
```

#### Channel

```java
interface Channel {
    String name();
    String permission();
    String sendPermission();
    String receivePermission();
    Type type();              // CHAT | EVENT
    boolean showSender();
    int rateLimitPerSecond();
    Language langSource();
    Language langTarget();
    Formats messages();
    Formats tooltips();
    List<SoundSpec> sounds();
}
```

#### ChannelRegistry

```java
interface ChannelRegistry {
    Optional<Channel> get(String name);
    Optional<Channel> resolve(String path);
    List<Channel> all();
    List<String> paths();
    void register(Channel channel);
    void unregister(String name);
}
```

#### HostConfig

```java
interface HostConfig {
    boolean quickLook();
    String language();
    boolean engineParallel();
    boolean soundEnabled();
    ClaimMode claimMode();
    
    enum ClaimMode { CANCEL_EVENT, CLEAR_RECIPIENTS }
}
```

#### SuiteHost

```java
interface SuiteHost {
    HostConfig config();
    ChannelRegistry channels();
    TranslationService translation();
    ModuleManager modules();
    Path dataDirectory();
    void close();
}
```

#### ModuleManager

```java
interface ModuleManager {
    Collection<Module> loadedModules();
    Optional<Module> getModule(String id);
    ModuleDescriptor getDescriptor(String id);
}
```

## Host SPI

### ChatDelivery

```java
interface ChatDelivery {
    void deliver(Actor recipient, Component rendered, Message original);
    void deliverConsole(Component rendered);
    void playSound(Actor recipient, SoundSpec sound);
    boolean hasSound(String soundName);
}
```

### ActorDirectory

```java
interface ActorDirectory {
    List<Actor> onlinePlayers();
    Optional<Actor> byUuid(UUID uuid);
    Optional<Actor> byName(String name);
    Actor console();
    List<Actor> playersInWorld(String world);
    List<Actor> playersNear(Actor center, double radius);
}
```

## Host Config

### ConfigLoader

```java
final class ConfigLoader {
    static HostConfig loadConfig(Path dir);
    static ChannelRegistry loadChannels(Path dir);
}
```

### HostConfig

```java
record HostConfig(
    boolean quickLook,
    Language defaultLanguage,
    boolean engineParallel,
    boolean soundEnabled,
    ClaimMode claimMode
) {
    static HostConfig defaults();
}
```

### ConfigPath

```java
enum ConfigPath {
    QUICK_LOOK("quick-look"),
    IFLOW("iflow"),
    IFLOW_ENGINE("engine"),
    IFLOW_ENGINE_PARALLEL("parallel"),
    SONIDO("sonido"),
    SONIDO_ENABLED("enabled"),
    GENERAL("general"),
    GENERAL_LANGUAGE("language"),
    CHAT("chat"),
    CHAT_CLAIM_MODE("claim-mode"),
    CHANNEL_NAME("name"),
    CHANNEL_PERMISSION("permission"),
    CHANNEL_SEND_PERMISSION("send-permission"),
    CHANNEL_RECEIVE_PERMISSION("receive-permission"),
    CHANNEL_MESSAGES("messages"),
    CHANNEL_TOOLTIPS("tooltips"),
    CHANNEL_SHOW_SENDER("show-sender"),
    CHANNEL_RATE_LIMIT("rate-limit-per-second"),
    CHANNEL_LANG_SOURCE("lang-source"),
    CHANNEL_LANG_TARGET("lang-target"),
    CHANNEL_TYPE("type"),
    CHANNEL_SOUNDS("sounds"),
    SOUND_NAME("name"),
    SOUND_VOLUME("volume"),
    SOUND_PITCH("pitch");
    
    String key();
}
```

## iFlow API

### Router

```java
interface Router {
    RouteDecision route(Message message, Actor recipient);
    void setRules(Collection<Rule> rules);
    Collection<Rule> rules();
}
```

### RouteDecision

```java
record RouteDecision(
    PolicyTarget target,
    String reason,
    long backoffMillis,
    Actor recipient,
    Actor emitter,
    String redirectChannel
) {
    boolean delivered();
    boolean rejected();
    String describe();
}

enum PolicyTarget {
    LOG, DROP, REJECT, REDIRECT, CHANNEL_REDIRECT, RATE_LIMIT
}
```

### Rule

```java
record Rule(
    String id,
    int priority,
    PolicyTarget target,
    String reason,
    String channelPath,
    String emitterPattern,
    String receiverPattern,
    Direction.Kind kind,
    List<TransformOp> transforms,
    String redirectChannel,
    String condition,
    String action
) {
    boolean matches(String channel, String emitter, String receiver, Direction dir);
    static Builder builder(PolicyTarget target);
}
```

### TransformOp

```java
abstract class TransformOp {
    abstract void apply(ScriptSurface surface);
    
    static class Rewrite extends TransformOp { String template; }
    static class Sounds extends TransformOp { List<String> add; List<String> remove; }
    static class Sleep extends TransformOp { long millis; }
    static class SetLangSource extends TransformOp { String lang; }
    static class SetLangTarget extends TransformOp { String lang; }
    static class SetColorMode extends TransformOp { String mode; }
    static class SetFormatPapi extends TransformOp { boolean enabled; }
    static class SetChannel extends TransformOp { String channel; }
}
```

### ScriptSurface

```java
interface ScriptSurface {
    // Query
    Message msg();
    Actor sender();
    Actor recipient();
    ChannelRegistry channels();
    boolean hasPermission(Actor, String);
    String papi(String token);
    String papiRecipient(String token);
    String papi(Actor, String);
    boolean canTranslate();
    Channel channel();
    
    // Mutation
    void setLangTarget(Language);
    void setLangSource(Language);
    void skipTranslate();
    void enableTranslate();
    void setFormat(String path);
    void setColorMode(String);
    void setFormatPapi(boolean);
    void cancel();
    void setProcessed();
    void redirect(String channel);
    Message cloneMessage();
    String toJson();
    
    // Transform helpers
    void setText(String);
    void setSoundsAdd(List<String>);
    void setSoundsRemove(List<String>);
    void setSleepMillis(long);
}
```

## Module SPI

### Module

```java
interface Module {
    ModuleDescriptor descriptor();
}
```

### ModuleDescriptor

```java
interface ModuleDescriptor {
    String id();
    String name();
    SemVer version();
    SemVer contractVersion();
    int jvmRangeMin();
    Set<Capability> provides();
    Set<Capability> requires();
    String mainClass();
}
```

### Extension

```java
interface Extension {
    String id();
    String name();
    SemVer version();
    SemVer requiredCoreApi();
    List<String> dependencies();
    Set<Capability> providedCapabilities();
    Set<Capability> requiredCapabilities();
    void onEnable(ExtensionContext context);
    void onDisable();
    void onConfigReload(ExtensionConfig config);
    ExtensionMetadata metadata();
}
```

### ExtensionContext

```java
interface ExtensionContext {
    SuiteHost host();
    MessageDispatcher dispatcher();
    PluginLogger logger();
    TranslationService translation();
    UserLanguageStore languages();
    ChannelRegistry channels();
    Path dataDirectory();
    String extensionId();
    
    void registerChannel(Channel channel);
    void unregisterChannel(String channelName);
    void dispatchMessage(Message message);
    void putState(String key, Object value);
    <T> T getState(String key);
    void removeState(String key);
    void subscribe(String eventType, Consumer<Object> listener);
    void unsubscribe(String eventType, Consumer<?> listener);
    void publish(String eventType, Object event);
    boolean hasPermission(Actor actor, String permission);
    String translate(String text, String from, String to);
    ExtensionConfig loadConfig();
    void saveConfig(ExtensionConfig config);
    Optional<Actor> findPlayer(String nameOrUuid);
}
```

### ExtensionConfig

```java
record ExtensionConfig(Map<String, Object> values) {
    static ExtensionConfig empty();
    static ExtensionConfig of(Map<String, Object> values);
    <T> T get(String key);
    <T> T getOrDefault(String key, T defaultValue);
    String getString(String key);
    String getStringOrDefault(String key, String defaultValue);
    int getInt(String key);
    int getIntOrDefault(String key, int defaultValue);
    long getLong(String key);
    boolean getBoolean(String key);
    boolean getBooleanOrDefault(String key, boolean defaultValue);
    double getDouble(String key);
    List<Object> getList(String key);
    Map<String, Object> getMap(String key);
    Set<String> keys();
}
```

## Event API

### MessageEvent

```java
final class MessageEvent {
    private final Message message;
    private final Actor sender;
    private boolean cancelled;
    private final UUID id;
    private Message modifiedMessage;
    
    public Message message();
    public Actor sender();
    boolean isCancelled();
    UUID id();
    Message getMessage();          // Returns modified or original
    void setMessage(Message modified);
    void setCancelled(boolean cancelled);
    UUID eventId();
}
```

### MessageEventBus (Future)

```java
interface MessageEventBus {
    void register(String id, Consumer<MessageEvent> listener);
    void unregister(String id);
    void publish(MessageEvent event);
}
```

## Transport

### MessageCodec

```java
interface MessageCodec {
    String toJson(Message message);
    Message fromJson(String json);
    byte[] toBytes(Message message);
    Message fromBytes(byte[] bytes);
}
```

### HttpTransport

```java
interface HttpTransport {
    CompletableFuture<String> post(String url, String json, Map<String, String> headers);
    CompletableFuture<String> get(String url, Map<String, String> headers);
    void close();
}
```

### SyncSink

```java
interface SyncSink {
    String name();
    void start() throws IOException;
    void stop();
    void send(Message message) throws IOException, InterruptedException;
    void setListener(SyncListener listener);
}
```

### SyncListener

```java
interface SyncListener {
    void onMessage(SyncSink sink, Message message);
}
```

## Web Editor API

### StateStore

```javascript
// JavaScript API for web editor extensions

// State management
Suite.state.get(path)           // Get value at path
Suite.state.set(path, value)    // Set value at path
Suite.state.mutate(path, fn)    // Mutate value at path
Suite.state.subscribe(path, fn) // Subscribe to changes

// History
Suite.history.undo()
Suite.history.redo()
Suite.history.canUndo()
Suite.history.canRedo()

// Validation
Suite.validate()                // Returns issues array
Suite.validate.path(path)       // Validate specific path

// Import/Export
Suite.export()                  // Returns files object
Suite.import(files)             // Import from files object

// UI
Suite.views.switchView(name)
Suite.views.renderTxf()
Suite.views.renderProps()
Suite.views.renderSidebar()
```

### Custom Node Types

```javascript
Suite.editor.registerNodeType({
  kind: 'custom-action',
  label: 'Custom Action',
  color: '#FF6B6B',
  ports: { inputs: 1, outputs: 1 },
  properties: [
    { key: 'action', type: 'select', options: ['a', 'b', 'c'] }
  ],
  render: (node, ctx) => { /* custom SVG/HTML */ }
});
```

### Events

```javascript
Suite.events.on('state-change', (path, value) => {})
Suite.events.on('selection-change', (selection) => {})
Suite.events.on('validation-complete', (issues) => {})
Suite.events.on('export-complete', (files) => {})
```

## Configuration Schema (JSON Schema)

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "TextFormatter Suite Config",
  "type": "object",
  "properties": {
    "quick-look": { "type": "boolean", "default": true },
    "general": {
      "type": "object",
      "properties": {
        "language": { "type": "string", "enum": ["en", "es", "fr", "de", "auto"] }
      }
    },
    "iflow": {
      "type": "object",
      "properties": {
        "engine": {
          "type": "object",
          "properties": {
            "parallel": { "type": "boolean" }
          }
        }
      },
      "sonido": {
        "type": "object",
        "properties": {
          "enabled": { "type": "boolean" }
        }
      },
      "chat": {
        "type": "object",
        "properties": {
          "claim-mode": { "type": "string", "enum": ["cancel-event", "clear-recipients"] }
        }
      }
    }
  },
  "required": ["quick-look", "general", "iflow", "sonido", "chat"]
}
```

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 2.1.0 | 2024-01-15 | FASE 1-15 complete |
| 2.0.0 | 2023-12-01 | Major rewrite |
| 1.5.0 | 2023-06-15 | iFlow engine |
| 1.0.0 | 2023-01-01 | Initial release |

---

*API Reference v2.1 - Part of TextFormatter Suite Documentation*