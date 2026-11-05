package me.majhrs16.suite.iflow.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.IflowHub;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IflowHubTest {

    @TempDir
    Path dir;

    @Test
    void activatorRegistersHubAndRefreshesWhenBackendAppears() {
        FakeContext context = new FakeContext(dir);
        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        assertEquals("none", hub.activeBackend());
        assertTrue(context.services.find(Router.class).isPresent());

        FakeRouter router = new FakeRouter();
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-firewall", router, true));
        context.events.publish(new ModuleActivatedEvent(
                "iflow-firewall", new IflowModule().descriptor()));

        assertEquals("iflow-firewall", hub.activeBackend());
        assertSame(router, hub.router());
        assertTrue(hub.reload());
    }

    @Test
    void fallsBackWhenConfiguredBackendIsUnavailable() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: iflow-wasm
                fallback-on-unavailable: true
                config-version: 1.0
                """);
        FakeContext context = new FakeContext(dir);
        FakeRouter firewall = new FakeRouter();
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-wasm", new FakeRouter(), false));
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-firewall", firewall, true));

        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        assertEquals("iflow-firewall", hub.activeBackend());
        assertSame(firewall, hub.router());
    }

    @Test
    void fallbackUsesExplicitPriorityRegardlessOfRegistrationOrder() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: iflow-wasm
                fallback-on-unavailable: true
                config-version: 1.0
                """);
        FakeContext context = new FakeContext(dir);
        FakeRouter vm = new FakeRouter();
        FakeRouter firewall = new FakeRouter();
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-vm", vm, true));
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-firewall", firewall, true));
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-wasm", new FakeRouter(), false));

        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        assertEquals("iflow-firewall", hub.activeBackend());
        assertSame(firewall, hub.router());
    }

    @Test
    void noFallbackFailsClosed() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: iflow-wasm
                fallback-on-unavailable: false
                config-version: 1.0
                """);
        FakeContext context = new FakeContext(dir);
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-wasm", new FakeRouter(), false));
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-firewall", new FakeRouter(), true));

        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        assertEquals("none", hub.activeBackend());
        assertFalse(hub.hasPermission(Actor.unknown("player"), "test"));
        assertFalse(hub.checkEmissionRateLimit(Message.builder().build()));
        assertEquals(0, hub.nanosUntilNextRateLimitWindow(Message.builder().build()));

        RouteOutcome outcome = hub.route(Message.builder().build(), Actor.unknown("recipient"));
        assertEquals(PolicyTarget.REJECT, outcome.decision().target());
        assertFalse(outcome.delivered());
    }

    @Test
    void deactivationRefreshesSelection() {
        FakeContext context = new FakeContext(dir);
        FakeRouter router = new FakeRouter();
        ServiceRegistry.Registration registration = context.services.register(
                IflowBackend.class, new FakeBackend("iflow-firewall", router, true));
        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        assertEquals("iflow-firewall", hub.activeBackend());

        registration.close();
        context.events.publish(new ModuleDeactivatedEvent("iflow-firewall"));

        assertEquals("none", hub.activeBackend());
    }

    @Test
    void delegatesRoutingToActiveBackend() {
        FakeContext context = new FakeContext(dir);
        FakeRouter router = new FakeRouter();
        context.services.register(IflowBackend.class,
                new FakeBackend("iflow-firewall", router, true));
        new IflowActivator().start(context);

        IflowHub hub = context.services.find(IflowHub.class).orElseThrow();
        Message message = Message.builder().sender(Actor.unknown("emitter")).build();
        RouteOutcome outcome = hub.route(message, Actor.unknown("recipient"));

        assertEquals(PolicyTarget.LOG, outcome.decision().target());
        assertTrue(hub.hasPermission(Actor.unknown("player"), "test"));
        assertTrue(hub.checkEmissionRateLimit(message));
        assertEquals(42, hub.nanosUntilNextRateLimitWindow(message));
    }

    private static final class FakeBackend implements IflowBackend {
        private final String name;
        private final Router router;
        private final boolean available;

        private FakeBackend(String name, Router router, boolean available) {
            this.name = name;
            this.router = router;
            this.available = available;
        }

        @Override public String name() { return name; }
        @Override public Router router() { return router; }
        @Override public boolean isAvailable() { return available; }
    }

    private static final class FakeRouter implements Router {
        @Override
        public RouteOutcome route(Message message, Actor recipient) {
            RouteDecision decision = new RouteDecision(
                    PolicyTarget.LOG, "ok", 0, recipient, message.sender());
            return RouteOutcome.unchanged(decision, message);
        }

        @Override public boolean hasPermission(Actor actor, String permission) { return true; }
        @Override public boolean checkEmissionRateLimit(Message message) { return true; }
        @Override public long nanosUntilNextRateLimitWindow(Message message) { return 42; }
    }

    private static final class FakeContext implements ModuleContext {
        private final Path configDir;
        private final List<String> logs = new ArrayList<>();
        private final SimpleRegistry services = new SimpleRegistry();
        private final SimpleEventBus events = new SimpleEventBus();
        private final SimpleResources resources = new SimpleResources();

        private FakeContext(Path configDir) {
            this.configDir = configDir;
        }

        @Override public String moduleId() { return "iflow"; }
        @Override public Path configDir() { return configDir; }
        @Override public PluginLogger logger() {
            return new PluginLogger() {
                @Override public void info(String message, Object... args) { logs.add(message); }
                @Override public void warn(String message, Object... args) { logs.add(message); }
                @Override public void error(String message, Object... args) { logs.add(message); }
                @Override public void error(String message, Throwable throwable) { logs.add(message); }
                @Override public void debug(String message, Object... args) { logs.add(message); }
            };
        }
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
            List<Object> values = services.getOrDefault(type, List.of());
            List<T> result = new ArrayList<>();
            for (Object value : values) {
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
