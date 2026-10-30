package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TestProbe;
import me.majhrs16.suite.api.spi.TestRunnerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TesterActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesTheTestRunnerFactoryWithoutStartingPlatformWork() {
        FakeRegistry registry = new FakeRegistry();

        new TesterActivator().start(new FakeContext(tempDir, registry));

        assertThat(registry.find(TestRunnerFactory.class)).isPresent();
        assertThat(registry.find(TestRunnerFactory.class).orElseThrow().create(
            new FakeProbe(), new FakeActors(), new FakeLanguages(), noopLogger()))
            .isNotNull();
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

        @Override public String moduleId() { return "tester"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() { throw new UnsupportedOperationException(); }
        @Override public Scheduler scheduler() { throw new UnsupportedOperationException(); }
        @Override public ResourceTracker resources() { throw new UnsupportedOperationException(); }
        @Override public PlatformInfo platform() { return PlatformInfo.current("test", "1.0"); }
        @Override public void close() { }
    }

    private static PluginLogger noopLogger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { }
            @Override public void warn(String message, Object... args) { }
            @Override public void error(String message, Object... args) { }
            @Override public void error(String message, Throwable throwable) { }
            @Override public void debug(String message, Object... args) { }
        };
    }

    private static final class FakeProbe implements TestProbe {
        @Override public boolean ready() { return true; }
        @Override public me.majhrs16.suite.api.channel.ChannelRegistry channels() { return null; }
        @Override public me.majhrs16.suite.api.iflow.Router router() { return null; }
        @Override public int discoveredModules() { return 0; }
        @Override public DispatchOutcome dispatch(me.majhrs16.suite.api.message.Message message) {
            return DispatchOutcome.none();
        }
        @Override public void format(me.majhrs16.suite.api.message.Message message) { }
        @Override public String detectLanguage(String text) { return "en"; }
    }

    private static final class FakeActors implements me.majhrs16.suite.api.spi.ActorDirectory {
        @Override public List<me.majhrs16.suite.api.message.Actor> onlinePlayers() { return List.of(); }
        @Override public Optional<me.majhrs16.suite.api.message.Actor> byUuid(java.util.UUID uuid) {
            return Optional.empty();
        }
        @Override public Optional<me.majhrs16.suite.api.message.Actor> byName(String name) {
            return Optional.empty();
        }
        @Override public me.majhrs16.suite.api.message.Actor console() {
            return me.majhrs16.suite.api.message.Actor.console("console", null);
        }
    }

    private static final class FakeLanguages implements me.majhrs16.suite.api.spi.UserLanguageStore {
        @Override public Optional<String> languageOf(java.util.UUID uuid) { return Optional.empty(); }
        @Override public void save(java.util.UUID uuid, String value) { }
    }
}
