package me.majhrs16.suite.iflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F5 gate: the VM activator publishes a Router and tracks the engine for
 * deactivation.
 */
class IflowVmActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void registersRouterAndTracksTheEngine() {
        FakeRegistry registry = new FakeRegistry();
        registry.register(ChannelRegistry.class, ChannelRegistry.builder()
                .register(Channel.builder("chat").build())
                .build());
        FakeResources resources = new FakeResources();

        new IflowVmActivator().start(new FakeContext(tempDir, registry, resources));

        assertTrue(registry.find(Router.class).isPresent());
        assertTrue(registry.find(VmPolicyEngine.class).isPresent());
        assertEquals(1, resources.tracked.size());

        resources.releaseAll();
        assertTrue(resources.isReleased());
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

    private static final class FakeResources implements ResourceTracker {

        private final List<AutoCloseable> tracked = new ArrayList<>();
        private boolean released;

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            tracked.add(resource);
            return resource;
        }

        @Override
        public Registration onRelease(Runnable cleanup) {
            return () -> { };
        }

        @Override
        public void releaseAll() {
            released = true;
            for (AutoCloseable resource : tracked) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // The test only checks that the tracker ran.
                }
            }
        }

        @Override
        public boolean isReleased() {
            return released;
        }
    }

    private record FakeContext(Path configDir, ServiceRegistry services,
                               ResourceTracker resources) implements ModuleContext {

        @Override public String moduleId() { return "iflow-vm"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() { throw new UnsupportedOperationException(); }
        @Override public Scheduler scheduler() { throw new UnsupportedOperationException(); }
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
