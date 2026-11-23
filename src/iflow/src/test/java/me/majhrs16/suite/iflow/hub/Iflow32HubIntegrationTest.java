package me.majhrs16.suite.iflow.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.IflowHub;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.Iflow32Activator;
import me.majhrs16.suite.iflow.vm.engine.v3.Iflow32Router;

/**
 * Real hub → {@code iflow-32} VM → {@code RouteOutcome} integration.
 *
 * <p>Activates the actual {@link Iflow32Router} through
 * {@link Iflow32Activator}, publishes its {@link IflowBackend} into the hub
 * context and routes messages through {@link IflowHub}. No fake router is
 * involved: the decision comes from the guest policy reading
 * {@code message.content}, so a constant-policy regression would flip one of
 * the two expectations.</p>
 */
class Iflow32HubIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void hubSelects32AndRoutesThroughTheRealVm() throws IOException {
        Path vmDir = Files.createDirectories(temp.resolve("iflow-32"));
        copyPolicy(vmDir);
        FakeContext vmContext = new FakeContext(vmDir, "iflow-32");
        IflowBackend backend;
        try {
            new Iflow32Activator().start(vmContext);
            backend = vmContext.services.find(IflowBackend.class).orElseThrow();
            assertEquals("iflow-32", backend.name());
            assertTrue(backend.isAvailable(), "policy.flow must load");

            Path hubDir = Files.createDirectories(temp.resolve("iflow"));
            Files.writeString(hubDir.resolve("config.yml"), """
                    engine: iflow-32
                    fallback-on-unavailable: false
                    config-version: 1.0
                    """);
            FakeContext hubContext = new FakeContext(hubDir, "iflow");
            try {
                hubContext.services.register(IflowBackend.class, backend);
                new IflowActivator().start(hubContext);

                IflowHub hub = hubContext.services.find(IflowHub.class).orElseThrow();
                assertEquals("iflow-32", hub.activeBackend());
                assertNotNull(hub.router());
                assertEquals(Iflow32Router.class, hub.router().getClass());

                Actor emitter = Actor.unknown("emitter");
                Actor recipient = Actor.unknown("recipient");

                RouteOutcome blocked = hub.route(message(emitter, "block me"), recipient);
                assertEquals(PolicyTarget.DROP, blocked.decision().target());
                assertEquals("blocked", blocked.decision().reason());

                RouteOutcome allowed = hub.route(message(emitter, "allow me"), recipient);
                assertEquals(PolicyTarget.LOG, allowed.decision().target());
                assertEquals("policy", allowed.decision().reason());
            } finally {
                hubContext.close();
            }
        } finally {
            vmContext.close();
        }
    }

    private static Message message(Actor emitter, String text) {
        return Message.builder()
                .sender(emitter)
                .channel("chat")
                .text(text)
                .build();
    }

    private static void copyPolicy(Path dir) throws IOException {
        try (InputStream rom = Iflow32HubIntegrationTest.class.getResourceAsStream(
                "/rom/v3/policy-route.rom")) {
            if (rom == null) {
                throw new IOException("missing test resource /rom/v3/policy-route.rom");
            }
            Files.copy(rom, dir.resolve(Iflow32Router.POLICY_FILE));
        }
    }

    private static final class FakeContext implements ModuleContext {
        private final Path configDir;
        private final String moduleId;
        private final SimpleRegistry services = new SimpleRegistry();
        private final SimpleEventBus events = new SimpleEventBus();
        private final SimpleResources resources = new SimpleResources();

        private FakeContext(Path configDir, String moduleId) {
            this.configDir = configDir;
            this.moduleId = moduleId;
        }

        @Override public String moduleId() { return moduleId; }
        @Override public Path configDir() { return configDir; }
        @Override public PluginLogger logger() { return NoopLogger.INSTANCE; }
        @Override public ServiceRegistry services() { return services; }
        @Override public EventBus events() { return events; }
        @Override public Scheduler scheduler() {
            throw new UnsupportedOperationException("scheduler not used");
        }
        @Override public ResourceTracker resources() { return resources; }
        @Override public PlatformInfo platform() {
            return new PlatformInfo("test", "1.0", "x86_64", "linux", 17);
        }
        @Override public void close() { resources.releaseAll(); }
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }

    private static final class SimpleRegistry implements ServiceRegistry {
        private final Map<Class<?>, List<Object>> services = new LinkedHashMap<>();

        @Override
        public <T> Registration register(Class<T> type, T service) {
            services.computeIfAbsent(type, key -> new ArrayList<>()).add(service);
            return () -> services.getOrDefault(type, List.of()).remove(service);
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            List<Object> values = services.getOrDefault(type, List.of());
            return values.isEmpty() ? Optional.empty() : Optional.of(type.cast(values.get(0)));
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            List<T> result = new ArrayList<>();
            for (Object value : services.getOrDefault(type, List.of())) {
                result.add(type.cast(value));
            }
            return result;
        }
    }

    private static final class SimpleEventBus implements EventBus {
        private final Map<Class<?>, List<Consumer<Object>>> listeners = new LinkedHashMap<>();

        @Override
        public <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
            Consumer<Object> wrapper = event -> {
                if (eventType.isInstance(event)) {
                    listener.accept(eventType.cast(event));
                }
            };
            listeners.computeIfAbsent(eventType, key -> new ArrayList<>()).add(wrapper);
            return new Subscription() {
                private boolean active = true;

                @Override public void unsubscribe() {
                    if (active) {
                        active = false;
                        listeners.getOrDefault(eventType, List.of()).remove(wrapper);
                    }
                }

                @Override public boolean isActive() { return active; }
            };
        }

        @Override
        public void publish(Object event) {
            for (Map.Entry<Class<?>, List<Consumer<Object>>> entry : listeners.entrySet()) {
                if (entry.getKey().isInstance(event)) {
                    for (Consumer<Object> listener : new ArrayList<>(entry.getValue())) {
                        listener.accept(event);
                    }
                }
            }
        }
    }

    private static final class SimpleResources implements ResourceTracker {
        private final List<AutoCloseable> resources = new ArrayList<>();
        private boolean released;

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            resources.add(resource);
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
            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    resources.get(i).close();
                } catch (Exception ignored) {
                    // test double
                }
            }
        }

        @Override public boolean isReleased() { return released; }
    }
}
