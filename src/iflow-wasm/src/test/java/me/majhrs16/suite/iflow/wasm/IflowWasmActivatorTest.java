package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F5/W3 gate: the Wasm activator publishes the loader, contract, router and
 * backend, and fails closed when no policy artifact is installed.
 */
class IflowWasmActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void registersLoaderContractRouterAndBackend() {
        FakeRegistry registry = new FakeRegistry();
        FakeResources resources = new FakeResources();
        registry.register(ChannelRegistry.class, ChannelRegistry.builder()
                .register(Channel.builder("chat").build())
                .build());
        FakeContext context = new FakeContext(tempDir, registry, resources);

        try {
            new IflowWasmActivator().start(context);

            assertTrue(registry.find(WasmModuleLoader.class).isPresent());
            assertTrue(registry.find(WasmContract.class).isPresent());
            assertTrue(registry.find(WasmPolicyRouter.class).isPresent());
            assertTrue(registry.find(Router.class).isPresent());

            IflowBackend backend = registry.find(IflowBackend.class).orElseThrow();
            assertEquals("iflow-wasm", backend.name());
            assertFalse(backend.isAvailable(),
                    "without policy.wasm and its SHA-256 the backend must be unavailable");
            assertEquals(1, resources.tracked.size());
        } finally {
            context.close();
        }
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
            if (released) {
                return;
            }
            released = true;
            for (int i = tracked.size() - 1; i >= 0; i--) {
                try {
                    tracked.get(i).close();
                } catch (Exception ignored) {
                    // test double
                }
            }
        }

        @Override public boolean isReleased() { return released; }
    }

    private record FakeContext(Path configDir, ServiceRegistry services, FakeResources resources)
            implements ModuleContext {

        @Override public String moduleId() { return "iflow-wasm"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() { throw new UnsupportedOperationException(); }
        @Override public Scheduler scheduler() { throw new UnsupportedOperationException(); }
        @Override public PlatformInfo platform() { return PlatformInfo.current("test", "1.0"); }
        @Override public void close() { resources.releaseAll(); }

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
