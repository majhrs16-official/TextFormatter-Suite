package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InWorldActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void publishesTheNeutralHandler() {
        FakeRegistry registry = new FakeRegistry();
        registry.register(ChannelRegistry.class, ChannelRegistry.builder()
            .register(Channel.builder("sign.global")
                .signLocation("0,64,0,world")
                .build())
            .build());

        new InWorldActivator().start(new FakeContext(tempDir, registry));

        assertThat(registry.find(InWorldHandler.class)).isPresent();
    }

    @Test
    void failsExplicitlyWithoutChannelApi() {
        FakeRegistry registry = new FakeRegistry();

        assertThatThrownBy(() -> new InWorldActivator().start(
            new FakeContext(tempDir, registry)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("channel-api");
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

        @Override public String moduleId() { return "inworld"; }
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
}
