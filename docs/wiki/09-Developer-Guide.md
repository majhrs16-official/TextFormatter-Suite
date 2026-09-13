# Developer Guide

## Architecture Overview

TextFormatter Suite follows **Hexagonal Architecture** (Ports & Adapters) with a modular, SPI-driven design.

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                        Platform Adapters                        │
│  spigot-host  │  fabric-host  │  velocity-host  │  (future)    │
├─────────────────────────────────────────────────────────────────┤
│                        Suite Host                               │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────────┐  │
│  │ Dispatch │ Registry │ Translate │  Module  │  Config      │
│  └──────────┴──────────┴──────────┴──────────┴──────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│  textformatter │ iflow │ kernel │ transport │ gtranslate │ ... │
├─────────────────────────────────────────────────────────────────┤
│                        core-api (SPI)                           │
└─────────────────────────────────────────────────────────────────┘
```

## Module Development

### Creating a Module

1. **Create module directory:**
```bash
mkdir -p suite/my-module/src/main/java/me/majhrs16/suite/mymodule
```

2. **build.gradle:**
```groovy
plugins {
    id 'java'
    id 'maven-publish'
}

group = 'me.majhrs16'
version = '2.1.0-SNAPSHOT'

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'me.majhrs16:suite-core-api:2.1.0-SNAPSHOT'
    implementation 'me.majhrs16:suite-host:2.1.0-SNAPSHOT'

    testImplementation 'me.majhrs16:suite-kernel:2.1.0-SNAPSHOT'
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.java
            artifactId = 'suite-my-module'
        }
    }
    repositories {
        maven {
            name = 'local'
            url = rootProject.layout.buildDirectory.dir('repo').get().asFile.toURI()
        }
    }
}
```

3. **Module class:**
```java
package me.majhrs16.suite.mymodule;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

public final class MyModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("my-module")
            .version(SemVer.of(1, 0, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("my-capability", SemVer.of(1, 0, 0)))
            .build();
    }
}
```

4. **Register SPI:**
```
src/main/resources/META-INF/services/me.majhrs16.suite.api.Module
```
Content:
```
me.majhrs16.suite.mymodule.MyModule
```

### Module Dependencies

```gradle
dependencies {
    implementation 'me.majhrs16:suite-core-api:2.1.0-SNAPSHOT'
    implementation 'me.majhrs16:suite-textformatter:2.1.0-SNAPSHOT'
    implementation 'me.majhrs16:suite-iflow:2.1.0-SNAPSHOT'
    // Add other suite modules as needed
}
```

## SPI Development

### Creating a SPI Implementation

```java
// Implement the SPI interface
public class MyTranslator implements Translator {
    
    @Override
    public String translate(String text, String from, String to) {
        // Implementation
    }
    
    @Override
    public String detect(String text) {
        // Language detection
    }
    
    @Override
    public boolean isAvailable() {
        return true;
    }
}
```

### Registering SPI

```java
// In your module's onEnable or via ServiceLoader
// META-INF/services/me.majhrs16.suite.api.spi.Translator
// Content: com.example.MyTranslator
```

## Extension Development

### Creating an Extension

```java
public class MyExtension implements Extension {
    
    @Override
    public String id() { return "my-extension"; }
    
    @Override
    public String name() { return "My Extension"; }
    
    @Override
    public SemVer version() { return SemVer.of(1, 0, 0); }
    
    @Override
    public SemVer requiredCoreApi() { return SemVer.of(2, 1, 0); }
    
    @Override
    public List<String> dependencies() { return List.of(); }
    
    @Override
    public Set<Capability> providedCapabilities() {
        return Set.of(Capability.of("my-capability", SemVer.of(1, 0, 0)));
    }
    
    @Override
    public Set<Capability> requiredCapabilities() {
        return Set.of();
    }
    
    @Override
    public void onEnable(ExtensionContext context) {
        // Register commands, channels, event listeners
        context.registerChannel(myCustomChannel);
        context.dispatcher().ifPresent(d -> d.registerListener(...));
    }
    
    @Override
    public void onDisable() {
        // Cleanup
    }
    
    @Override
    public void onConfigReload(ExtensionConfig config) {
        // Handle config changes
    }
    
    @Override
    public ExtensionMetadata metadata() {
        return ExtensionMetadata.builder()
            .id("my-extension")
            .name("My Extension")
            .description("Does amazing things")
            .author("Your Name")
            .build();
    }
}
```

### Extension Context API

```java
public interface ExtensionContext {
    // Core access
    SuiteHost host();
    MessageDispatcher dispatcher();
    PluginLogger logger();
    TranslationService translation();
    UserLanguageStore languages();
    ChannelRegistry channels();
    Path dataDirectory();
    String extensionId();
    
    // Channel management
    void registerChannel(Channel channel);
    void unregisterChannel(String channelName);
    
    // Message dispatch
    void dispatchMessage(Message message);
    
    // Shared state
    void putState(String key, Object value);
    <T> T getState(String key);
    void removeState(String key);
    
    // Event bus
    void subscribe(String eventType, Consumer<Object> listener);
    void unsubscribe(String eventType, Consumer<?> listener);
    void publish(String eventType, Object event);
    
    // Permissions
    boolean hasPermission(Actor actor, String permission);
    
    // Translation
    String translate(String text, String from, String to);
    
    // Config
    ExtensionConfig loadConfig();
    void saveConfig(ExtensionConfig config);
    
    // Player lookup
    Optional<Actor> findPlayer(String nameOrUuid);
}
```

## Module Lifecycle

```
Module JAR loaded
       │
       ▼
ServiceLoader discovers Module class
       │
       ▼
Module.descriptor() called
       │
       ▼
Module loaded into kernel
       │
       ▼
Kernel resolves dependencies
       ▼
Module loaded into registry
       │
       ▼
Module.onEnable() called (if Extension)
       │
       ▼
Module ready for use
```

## Testing

### Unit Tests

```java
@Test
void testMessageFormatting() {
    var host = createTestHost();
    var sender = new Actor(UUID.randomUUID(), "Test", ActorKind.PLAYER, Language.EN, null);
    
    var message = Message.builder()
        .type(MessageType.CHAT)
        .sender(sender)
        .direction(Direction.OTHERS)
        .channel("chat.global")
        .text("Hello world")
        .build();
    
    var report = host.dispatch(message);
    
    assertTrue(report.delivered() > 0);
}
```

### Integration Tests

```java
@SpringBootTest
class IntegrationTest {
    
    @Autowired
    SuiteHost host;
    
    @Test
    void testFullPipeline() {
        var message = Message.builder()
            .type(MessageType.CHAT)
            .sender(testActor)
            .direction(Direction.OTHERS)
            .channel("chat.global")
            .text("Hello world")
            .build();
        
        var report = host.dispatch(message);
        assertTrue(report.delivered() > 0);
    }
}
```

### Performance Tests

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public void benchmarkTemplateRendering() {
    var template = Template.of("<green>%content%</green>");
    var context = TemplateContext.builder()
        .sender(sender)
        .recipient(recipient)
        .content("Hello world")
        .build();
    
    renderer.render(template, context);
}
```

## Building & Publishing

### Local Development

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Publish to mavenLocal
./gradlew publishToMavenLocal

# Run specific module tests
./gradlew :suite:textformatter:test
./gradlew :suite:iflow:test
```

### Publishing

```bash
# Publish to mavenLocal
./gradlew publishToMavenLocal

# Publish to Maven Central (requires credentials)
./gradlew publish
```

### Version Management

```gradle
// In gradle.properties
version=2.1.0-SNAPSHOT

# For release
version=2.1.0
```

## Code Style

### Formatting

```bash
# Format code
./gradlew spotlessApply

# Check formatting
./gradlew spotlessCheck
```

### Linting

```bash
# Checkstyle
./gradlew checkstyleMain

# SpotBugs
./gradlew spotbugsMain
```

### Imports

```java
// Order:
// 1. java.* / javax.*
// 2. Third-party (alphabetical)
// 3. me.majhrs16.suite.* (project packages)
// 4. Static imports
```

### Documentation

```java
/**
 * Brief description.
 * 
 * <p>Detailed explanation if needed.</p>
 * 
 * @param param description
 * @return description
 * @throws ExceptionType when condition
 */
public ReturnType methodName(ParamType param) {
    // Implementation
}
```

## Debugging

### Remote Debugging

```bash
# JVM args for remote debugging
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

### Logging

```java
// In your code
logger.debug("Processing message: {}", message.id());
logger.info("Processed {} messages", count);
logger.warn("Rate limit exceeded for {}", player);
logger.error("Failed to send", exception);
```

### JVM Flags for Development

```bash
# Debug flags
-XX:+UnlockDiagnosticVMOptions
-XX:+DebugNonSafepoints
-XX:+PrintCompilation
-XX:+PrintInlining
-XX:+PrintGCDetails
-XX:+PrintGCDateStamps
-Xlog:gc*:file=gc.log:time,uptime,level,tags

# JFR profiling
-XX:StartFlightRecording=duration=60s,filename=profile.jfr,settings=profile
```

## Profiling

### JMH Benchmarks

```java
@Benchmark
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public void benchmarkTemplateRendering() {
    // Benchmark code
}
```

### Async Profiler

```bash
# CPU profiling
./profiler.sh -d 60 -e cpu -f profile.html <pid>

# Allocation profiling
./profiler.sh -d 60 -e alloc -f alloc.html <pid>

# Wall clock
./profiler.sh -d 60 -e wall -f wall.html <pid>
```

## CI/CD Pipeline

### GitHub Actions

```yaml
# .github/workflows/ci.yml
name: CI

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - uses: gradle/gradle-build-action@v2
      - run: ./gradlew check --no-daemon
      
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - run: ./gradlew test --no-daemon
      
  benchmark:
    runs-on: ubuntu-latest
    if: github.event_name == 'schedule'
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - run: ./gradlew :suite:loadtest:jmh
```

## Release Process

### Versioning

```
Major.Minor.Patch[-SNAPSHOT]
2.1.0-SNAPSHOT  → Development
2.1.0           → Release
2.1.1           → Patch release
```

### Release Checklist

- [ ] All tests pass
- [ ] Version bumped in `gradle.properties`
- [ ] Changelog updated (`CHANGELOG.md`)
- [ ] Git tag created (`v2.1.0`)
- [ ] GitHub Release created
- [ ] Artifacts published to Maven Central
- [ ] Docker images built
- [ ] Documentation updated
- [ ] Announcement posted

### Release Script

```bash
#!/bin/bash
# release.sh

VERSION=$1

# Update version
sed -i "s/version=.*/version=$VERSION/" gradle.properties

# Build
./gradlew clean build publish -x test

# Tag
git tag -a "v$VERSION" -m "Release v$VERSION"
git push origin "v$VERSION"

# Create GitHub release
gh release create "v$VERSION" --generate-notes
```

## Contributing

### Code of Conduct

- Be respectful and inclusive
- Follow the code of conduct
- Help others learn

### Pull Request Process

1. Fork repository
2. Create feature branch
3. Write tests
4. Ensure CI passes
3. Update documentation
4. Submit PR

### Commit Convention

```
feat: add new feature
fix: fix bug
docs: update documentation
refactor: refactor code
test: add tests
chore: maintenance
perf: performance improvement
```

### Commit Message Format

```
type(scope): description

[optional body]

[optional footer]
```

Example:
```
feat(iflow): add CHANNEL_REDIRECT target

- Add CHANNEL_REDIRECT PolicyTarget
- Update DefaultRouter to handle redirect
- Add redirectChannel to RouteDecision

Closes #123
```

## Support Channels

- **GitHub Issues** - Bug reports, feature requests
- **GitHub Discussions** - Questions, ideas
- **Discord** - Community chat
- **Wiki** - Documentation

---

*Developer Guide v2.1 - Part of TextFormatter Suite*