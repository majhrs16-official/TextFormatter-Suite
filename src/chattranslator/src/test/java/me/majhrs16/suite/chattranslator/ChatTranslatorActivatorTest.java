package me.majhrs16.suite.chattranslator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslatorProvider;
import me.majhrs16.suite.api.spi.TranslationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F5/F6 gate: the chattranslator activator builds its facade, discovers
 * providers and tracks the service plus lifecycle subscriptions so
 * deactivation closes the executor and removes listeners.
 */
class ChatTranslatorActivatorTest {

    @TempDir
    Path tempDir;

    @Test
    void registersFacadeAndTracksItsService() {
        FakeRegistry registry = new FakeRegistry();
        FakeResources resources = new FakeResources();
        FakeContext context = new FakeContext(tempDir, registry, resources);

        new ChatTranslatorActivator().start(context);

        assertTrue(registry.find(ChatTranslator.class).isPresent());
        assertTrue(registry.find(TranslationService.class).isPresent());
        assertEquals(3, resources.tracked.size());

        resources.releaseAll();
        assertTrue(resources.isReleased());
        long activeSubscriptions = resources.tracked.stream()
                .filter(EventBus.Subscription.class::isInstance)
                .map(EventBus.Subscription.class::cast)
                .filter(EventBus.Subscription::isActive)
                .count();
        assertEquals(0, activeSubscriptions);
    }

    @Test
    void usesConfiguredProviderFromServiceRegistry() throws Exception {
        Path translators = tempDir.resolve("translators");
        Files.createDirectories(translators);
        Files.writeString(translators.resolve("google.yml"), """
            provider: google
            active: true
            """);

        FakeRegistry registry = new FakeRegistry();
        registry.register(TranslatorProvider.class, new FakeProvider());
        FakeResources resources = new FakeResources();

        new ChatTranslatorActivator().start(new FakeContext(tempDir, registry, resources));

        TranslatorManager manager = registry.find(TranslatorManager.class).orElseThrow();
        assertEquals(1, manager.providers().size());
        assertEquals("google", manager.providers().get(0).name());

        resources.releaseAll();
    }

    private static final class FakeProvider implements TranslatorProvider {

        @Override
        public String name() {
            return "google";
        }

        @Override
        public Translator create(Map<String, Object> config) {
            return new Translator() {
                @Override public String name() { return "google"; }
                @Override public String translate(String text, String from, String to) { return text; }
                @Override public String detect(String text) { return "en"; }
                @Override public boolean isAvailable() { return true; }
            };
        }

        @Override
        public boolean validateConfig(Map<String, Object> config) {
            return true;
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

        @Override public String moduleId() { return "chattranslator"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() {
            return new EventBus() {
                @Override
                public <E> Subscription subscribe(Class<E> eventType, Consumer<E> listener) {
                    return new Subscription() {
                        private boolean active = true;

                        @Override public void unsubscribe() { active = false; }
                        @Override public boolean isActive() { return active; }
                    };
                }

                @Override
                public void publish(Object event) {
                }
            };
        }
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
