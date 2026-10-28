package me.majhrs16.suite.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the neutral runtime API introduced by F1.
 *
 * <p>They do not test a runtime implementation; they prove the contracts are
 * coherent (lifecycle, ownership and release semantics) and that a module can
 * be written against them without knowing the platform or any feature.</p>
 */
class RuntimeContractTest {

    @Test
    void moduleStateCoversEveryLifecycleStep() {
        assertEquals(
                EnumSet.of(
                        ModuleState.INSTALLED,
                        ModuleState.ACTIVE,
                        ModuleState.FAILED,
                        ModuleState.RESTART_REQUIRED,
                        ModuleState.REMOVED),
                EnumSet.allOf(ModuleState.class));
    }

    @Test
    void lifecycleHandlesAreAutoCloseable() {
        assertTrue(AutoCloseable.class.isAssignableFrom(ModuleContext.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(ServiceRegistry.Registration.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(EventBus.Subscription.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(Scheduler.Cancellable.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(ResourceTracker.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(PlatformPorts.Registration.class));
    }

    @Test
    void platformInfoRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformInfo("", "1.0", "x86_64", "linux", 21));
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformInfo("spigot", "1.0", "x86_64", "linux", 0));
    }

    @Test
    void platformInfoCurrentDescribesRunningJvm() {
        PlatformInfo info = PlatformInfo.current("test", "1.0");
        assertEquals("test", info.name());
        assertFalse(info.arch().isBlank());
        assertFalse(info.os().isBlank());
        assertTrue(info.javaMajor() >= 17);
    }

    @Test
    void moduleCanStartAndStopUsingOnlyRuntimeContracts() throws Exception {
        DemoActivator activator = new DemoActivator();
        FakeModuleContext context = new FakeModuleContext("demo");

        activator.start(context);
        assertTrue(activator.started.get());
        assertTrue(context.services().find(String.class).isPresent());

        activator.stop();
        context.close();

        assertTrue(activator.stopped.get());
        assertTrue(context.services().find(String.class).isEmpty());
        assertTrue(context.resources().isReleased());
        assertTrue(context.events.isClosed());
    }

    @Test
    void resourceTrackerReleasesInReverseOrderAndKeepsGoingOnFailure() {
        FakeModuleContext context = new FakeModuleContext("demo");
        ResourceTracker tracker = context.resources();
        List<String> order = new ArrayList<>();

        tracker.track(closeable("first", order));
        tracker.onRelease(() -> order.add("second"));
        tracker.onRelease(() -> {
            order.add("third");
            throw new IllegalStateException("boom");
        });

        tracker.releaseAll();

        assertEquals(List.of("third", "second", "first"), order);
        assertTrue(tracker.isReleased());
        tracker.releaseAll();
        assertEquals(3, order.size(), "releaseAll must be idempotent");
    }

    @Test
    void serviceRegistrationIsScopedAndIdempotent() {
        FakeModuleContext context = new FakeModuleContext("demo");
        ServiceRegistry registry = context.services();

        ServiceRegistry.Registration registration = registry.register(String.class, "one");
        registry.register(String.class, "two");

        assertEquals(Optional.of("one"), registry.find(String.class));
        assertEquals(List.of("one", "two"), registry.findAll(String.class));

        registration.close();
        registration.close();

        assertEquals(List.of("two"), registry.findAll(String.class));
    }

    @Test
    void eventSubscriptionStopsDeliveringAfterCloseAndIsolatesFailures() {
        FakeModuleContext context = new FakeModuleContext("demo");
        EventBus bus = context.events();
        List<String> received = new ArrayList<>();

        bus.subscribe(String.class, value -> {
            throw new IllegalStateException("listener failure");
        });
        EventBus.Subscription healthy = bus.subscribe(String.class, received::add);

        bus.publish("hello");
        assertEquals(List.of("hello"), received);

        healthy.close();
        healthy.close();
        bus.publish("ignored");
        assertEquals(List.of("hello"), received);
        assertFalse(healthy.isActive());
    }

    private static Closeable closeable(String name, List<String> order) {
        return () -> order.add(name);
    }

    private static final class DemoActivator implements ModuleActivator {

        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();

        @Override
        public ModuleDescriptor descriptor() {
            return ModuleDescriptor.builder("demo")
                    .version(SemVer.of(1, 0, 0))
                    .contractVersion(SemVer.of(2, 0, 0))
                    .jvmRange(17, 0)
                    .provide(new Capability("demo", SemVer.of(1, 0, 0)))
                    .build();
        }

        @Override
        public void start(ModuleContext context) {
            context.services().register(String.class, "demo-service");
            started.set(true);
        }

        @Override
        public void stop() {
            stopped.set(true);
        }
    }

    private static final class FakeModuleContext implements ModuleContext {

        private final String moduleId;
        private final FakeServiceRegistry services = new FakeServiceRegistry();
        private final FakeEventBus events = new FakeEventBus();
        private final FakeScheduler scheduler = new FakeScheduler();
        private final FakeResourceTracker resources = new FakeResourceTracker();

        private FakeModuleContext(String moduleId) {
            this.moduleId = moduleId;
        }

        @Override public String moduleId() { return moduleId; }
        @Override public Path configDir() { return Path.of("."); }
        @Override public PluginLogger logger() { return new NoopLogger(); }
        @Override public ServiceRegistry services() { return services; }
        @Override public EventBus events() { return events; }
        @Override public Scheduler scheduler() { return scheduler; }
        @Override public ResourceTracker resources() { return resources; }
        @Override public PlatformInfo platform() { return PlatformInfo.current("test", "1.0"); }

        @Override
        public void close() {
            scheduler.cancelAll();
            events.clear();
            services.clear();
            resources.releaseAll();
        }
    }

    private static final class FakeServiceRegistry implements ServiceRegistry {

        private final Map<Class<?>, List<Object>> services = new LinkedHashMap<>();

        @Override
        public <T> Registration register(Class<T> type, T service) {
            services.computeIfAbsent(type, ignored -> new ArrayList<>()).add(service);
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    List<Object> list = services.get(type);
                    if (list != null) {
                        list.remove(service);
                        if (list.isEmpty()) {
                            services.remove(type);
                        }
                    }
                }
            };
        }

        @Override
        public <T> Optional<T> find(Class<T> type) {
            List<Object> list = services.get(type);
            return list == null || list.isEmpty() ? Optional.empty() : Optional.of(type.cast(list.get(0)));
        }

        @Override
        public <T> List<T> findAll(Class<T> type) {
            List<Object> list = services.get(type);
            if (list == null) {
                return List.of();
            }
            List<T> result = new ArrayList<>(list.size());
            for (Object service : list) {
                result.add(type.cast(service));
            }
            return result;
        }

        void clear() {
            services.clear();
        }
    }

    private static final class FakeEventBus implements EventBus {

        private final List<FakeSubscription> subscriptions = new CopyOnWriteArrayList<>();
        private final List<RuntimeException> failures = new ArrayList<>();
        private volatile boolean closed;

        @Override
        public <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
            FakeSubscription subscription = new FakeSubscription(eventType, listener);
            subscriptions.add(subscription);
            return subscription;
        }

        @Override
        public void publish(Object event) {
            for (FakeSubscription subscription : subscriptions) {
                if (!subscription.isActive() || !subscription.eventType.isInstance(event)) {
                    continue;
                }
                try {
                    subscription.deliver(event);
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }
        }

        boolean isClosed() {
            return closed;
        }

        void clear() {
            closed = true;
            subscriptions.clear();
        }

        private final class FakeSubscription implements Subscription {

            private final Class<?> eventType;
            private final Consumer<Object> listener;
            private final AtomicBoolean active = new AtomicBoolean(true);

            @SuppressWarnings("unchecked")
            private FakeSubscription(Class<?> eventType, Consumer<?> listener) {
                this.eventType = eventType;
                this.listener = (Consumer<Object>) listener;
            }

            void deliver(Object event) {
                listener.accept(event);
            }

            @Override public void unsubscribe() { active.set(false); }
            @Override public boolean isActive() { return active.get(); }
        }
    }

    private static final class FakeScheduler implements Scheduler {

        private final List<FakeCancellable> tasks = new CopyOnWriteArrayList<>();

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            return track(task);
        }

        @Override
        public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
            return track(task);
        }

        @Override
        public Cancellable execute(Runnable task) {
            return track(task);
        }

        private Cancellable track(Runnable task) {
            FakeCancellable cancellable = new FakeCancellable(task);
            tasks.add(cancellable);
            return cancellable;
        }

        void cancelAll() {
            tasks.forEach(FakeCancellable::cancel);
            tasks.clear();
        }

        private static final class FakeCancellable implements Cancellable {

            private final Runnable task;
            private final AtomicBoolean cancelled = new AtomicBoolean();

            private FakeCancellable(Runnable task) {
                this.task = task;
            }

            @Override public void cancel() { cancelled.set(true); }
            @Override public boolean isCancelled() { return cancelled.get(); }
        }
    }

    private static final class FakeResourceTracker implements ResourceTracker {

        private final List<Runnable> cleanups = new ArrayList<>();
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            cleanups.add(() -> {
                try {
                    resource.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            return resource;
        }

        @Override
        public Registration onRelease(Runnable cleanup) {
            cleanups.add(cleanup);
            AtomicBoolean removed = new AtomicBoolean();
            return () -> {
                if (removed.compareAndSet(false, true)) {
                    cleanups.remove(cleanup);
                }
            };
        }

        @Override
        public void releaseAll() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            List<Runnable> pending = new ArrayList<>(cleanups);
            cleanups.clear();
            for (int i = pending.size() - 1; i >= 0; i--) {
                try {
                    pending.get(i).run();
                } catch (RuntimeException ignored) {
                    // Report and continue: one broken cleanup must not leak the rest.
                }
            }
        }

        @Override public boolean isReleased() { return released.get(); }
    }

    private static final class NoopLogger implements PluginLogger {

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
