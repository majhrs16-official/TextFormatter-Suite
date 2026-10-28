package me.majhrs16.suite.ltranslate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F6 gate: the LibreTranslate provider is published as a runtime service.
 */
class LTranslateActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void registersLibreProvider() {
        FakeRegistry registry = new FakeRegistry();

        new LTranslateActivator().start(new FakeContext(tempDir, registry));

        TranslatorProvider provider = registry.find(TranslatorProvider.class).orElseThrow();
        assertEquals("libre", provider.name());
        assertTrue(provider.validateConfig(Map.of("base-url", "https://libre.example")));
    }

    private static final class FakeRegistry implements ServiceRegistry {

        private final Map<Class<?>, Object> services = new HashMap<>();

        @Override
        public <T> Registration register(Class<T> type, T service) {
            services.put(type, service);
            return () -> services.remove(type);
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            return Optional.ofNullable(type.cast(services.get(type)));
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            T service = type.cast(services.get(type));
            return service == null ? List.of() : List.of(service);
        }
    }

    private record FakeContext(Path configDir, ServiceRegistry services) implements ModuleContext {

        @Override public String moduleId() { return "ltranslate"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() { throw new UnsupportedOperationException(); }
        @Override public Scheduler scheduler() { throw new UnsupportedOperationException(); }
        @Override public ResourceTracker resources() { throw new UnsupportedOperationException(); }
        @Override public PlatformInfo platform() { return PlatformInfo.current("test", "1.0"); }
        @Override public void close() { }

        private static PluginLogger noopLogger() {
            return new PluginLogger() {
                @Override public void info(String message, Object... args) { }
                @Override public void warn(String message, Object... args) { }
                @Override public void error(String message, Object... args) { }
                @Override public void error(String message, Throwable throwable) { }
                @Override public void debug(String message, Object... args) { }
            };
        }
    }
}
