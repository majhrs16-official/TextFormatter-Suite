# Contributing Guide

## Welcome

Thank you for your interest in contributing to TextFormatter Suite! This guide will help you get started.

## Code of Conduct

- Be respectful and inclusive
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md)
- Help others learn
- No harassment, discrimination, or toxic behavior

## Getting Started

### Prerequisites

- Java 21 (or 17)
- Gradle 8.5+
- Git
- IDE with Gradle support (IntelliJ IDEA recommended)

### Setup

```bash
# Clone repository
git clone https://github.com/majhrs16-official/TextFormatter-Suite.git
cd TextFormatter-Suite

# Build
./gradlew build

# Run tests
./gradlew test

# Run benchmarks
./gradlew :suite:loadtest:jmh
```

### IDE Setup

**IntelliJ IDEA:**
1. File → Open → Select `build.gradle`
2. Enable "Auto-import" for Gradle
3. Run `./gradlew idea` to generate project files

**VS Code:**
1. Install "Extension Pack for Java"
3. Open folder
3. Run `./gradlew build` to generate classpath

## Development Workflow

### Branching Strategy

```
main                    # Stable releases
├── develop             # Integration branch
│   ├── feature/xyz     # Feature branches
│   ├── fix/xyz         # Bug fixes
│   └── docs/xyz        # Documentation
```

### Commit Convention

```
type(scope): description

[optional body]

[optional footer]
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `refactor`: Code restructuring
- `test`: Adding tests
- `chore`: Maintenance
- `perf`: Performance
- `security`: Security fix

**Examples:**
```
feat(iflow): add CHANNEL_REDIRECT target
fix(iflow): fix rate limiter memory leak
docs(wiki): add translation guide
refactor(core-api): simplify ModuleDescriptor
test(loadtest): add message pipeline benchmarks
```

### Pull Request Process

1. Fork repository
2. Create feature branch from `develop`
3. Write code + tests
4. Run `./gradlew check` locally
5. Update documentation
5. Submit PR to `develop`

### PR Checklist

- [ ] Tests pass (`./gradlew check`)
- [ ] Code formatted (`./gradlew spotlessCheck`)
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] CHANGELOG.md updated
- [ ] No breaking changes (or marked)
- [ ] CI passes

## Code Standards

### Java Version

- **Target**: Java 17/21
- **Language Level**: 17/21
- **Preview Features**: Enabled where stable

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
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public void benchmarkTemplateRendering() {
    // Benchmark code
}
```

### Running Tests

```bash
# All tests
./gradlew test

# Specific module
./gradlew :suite:textformatter:test

# Specific test
./gradlew :suite:textformatter:test --tests "TemplateRendererTest"

# With coverage
./gradlew jacocoTestReport
```

## Code Review

### Review Checklist

- [ ] Code compiles and tests pass
- [ ] Follows code style
- [ ] Tests added/updated
- [ ] Documentation updated
- [ ] No breaking changes (or documented)
- [ ] Performance considered
- [ ] Security considered
- [ ] Logging appropriate

### Review Guidelines

- Be constructive and respectful
- Focus on code, not person
- Explain reasoning
- Suggest improvements, don't demand
- Approve when ready, request changes when needed

## Issue Reporting

### Bug Reports

Include:
- Version/commit hash
- Platform (Spigot/Fabric/Velocity)
- Java version
- Server version
- Steps to reproduce
- Expected vs actual behavior
- Logs/config snippets
- Steps to reproduce

### Feature Requests

1. Check existing issues
2. Describe use case
3. Explain expected behavior
4. Consider alternatives
5. Implementation ideas welcome

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
- [ ] CHANGELOG.md updated
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

## Support Channels

- **GitHub Issues**: Bug reports, feature requests
- **GitHub Discussions**: Questions, ideas
- **Discord**: Community chat
- **Wiki**: Documentation

---

*Contributing Guide v2.1 - Part of TextFormatter Suite Documentation*