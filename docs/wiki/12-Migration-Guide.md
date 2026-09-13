# Migration Guide

## From 2.0.x to 2.1.0

### Breaking Changes

#### Configuration

| Old | New | Notes |
|-----|-----|-------|
| `chat.claim-mode: cancel` | `chat.claim-mode: cancel-event` | Values renamed |
| `chat.claim-mode: clear` | `chat.claim-mode: clear-recipients` | Values renamed |

#### API Changes

| Old | New | Notes |
|-----|-----|-------|
| `ModuleDescriptor.dependencies` | `ModuleDescriptor.requires` | Renamed |
| `ModuleDescriptor.provides` | `ModuleDescriptor.provides` | Same |
| `SyncSink.send()` | `SyncSink.send()` | Same signature |
| `ChatDelivery.deliver()` | `ChatDelivery.deliver()` | Added `Message original` param |

#### Commands

| Old | New |
|-----|-----|
| `/suite module install <id>` | `/suite module install <id> [version]` |
| `/suite module update <id>` | `/suite module update <id> [version]` |
| N/A | `/suite module remove <id>` |
| N/A | `/suite module enable <id>` |
| N/A | `/suite module disable <id>` |
| N/A | `/suite module reload <id>` |
| N/A | `/suite module config <id>` |
| N/A | `/suite module info <id>` |
| N/A | `/suite suite update` |
| N/A | `/suite suite update force` |

### Migration Steps

1. **Update config.yml:**
```yaml
# Old
chat:
  claim-mode: cancel

# New
chat:
  claim-mode: cancel-event
```

2. **Update module dependencies:**
```gradle
// Old
implementation 'me.majhrs16:suite-core-api:2.0.x'

// New
implementation 'me.majhrs16:suite-core-api:2.1.0-SNAPSHOT'
```

3. **Update module descriptors:**
```java
// Old
return ModuleDescriptor.builder("my-module")
    .dependencies(List.of("other-module"))
    .build();

// New
return ModuleDescriptor.builder("my-module")
    .requires(List.of("other-module"))
    .provides(Set.of(Capability.of("my-cap", SemVer.of(1, 0, 0))))
    .build();
```

### Module Descriptor Changes

| Old Field | New Field | Required |
|-----------|-----------|----------|
| `id` | `id` | Yes |
| `name` | `name` | Yes |
| `version` | `version` | Yes |
| `contractVersion` | `contractVersion` | Yes |
| `jvmRange` | `jvmRangeMin` | Yes |
| `dependencies` | `requires` | No |
| `provides` | `provides` | No |
| `autoLoad` | N/A | No |

### SPI Changes

| Interface | Change |
|-----------|--------|
| `Translator` | Added `translateAsync` |
| `ExpressionEvaluator` | New interface |
| `PlaceholderResolver` | Added `available()` |
| `SyncSink` | Added `setListener` |
| `ChatDelivery` | Added `original` param to `deliver()` |

### Command Changes

| Old Command | New Command |
|-------------|-------------|
| `/suite module install <id>` | `/suite module install <id> [version]` |
| `/suite module update <id>` | `/suite module update <id> [version]` |
| N/A | `/suite module remove <id>` |
| N/A | `/suite module enable <id>` |
| N/A | `/suite module disable <id>` |
| N/A | `/suite module reload <id>` |
| N/A | `/suite module config <id>` |
| N/A | `/suite module info <id>` |
| N/A | `/suite suite update` |

### Configuration Migration

Run `/suite reload` after updating - the system will attempt to migrate configs automatically.

### Breaking Changes Checklist

- [ ] Update `chat.claim-mode` values in config.yml
- [ ] Update module `build.gradle` dependencies
- [ ] Update `ModuleDescriptor` in custom modules
- [ ] Update SPI implementations
- [ ] Test `/suite module` commands
- [ ] Test `/suite suite update`
- [ ] Verify config validation passes

### Compatibility

| Component | 2.0.x Config | 2.1.0 Config |
|-----------|--------------|--------------|
| config.yml | ✅ Auto-migrated | ✅ |
| channels/*.yml | ✅ Compatible | ✅ |
| rules.yml | ✅ Compatible | ✅ |
| translators/*.yml | ✅ Compatible | ✅ |
| sync/*.yml | ✅ Compatible | ✅ |
| messages.yml | ✅ Compatible | ✅ |

### Rollback Procedure

If issues arise:

1. Stop server
2. Restore backup of config files
3. Revert to 2.0.x jars
4. Run `/suite reload`

### Support

- GitHub Issues: https://github.com/majhrs16-official/TextFormatter-Suite/issues
- Discord: https://discord.gg/textformatter
- Wiki: https://github.com/majhrs16-official/TextFormatter-Suite/wiki

---

*Migration Guide v2.1 - Part of TextFormatter Suite Documentation*