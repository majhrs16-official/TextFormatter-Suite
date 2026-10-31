package me.majhrs16.suite.syncbukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformHandle;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SyncBukkitActivatorTest {

    @TempDir
    Path configDir;

    @Test
    void registersListenerAndStopsIt() throws Exception {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        FakeRegistry registry = new FakeRegistry();
        registry.register(PlatformHandle.class, (PlatformHandle) () -> plugin);
        registry.register(MessageDispatch.class, (MessageDispatch) message -> { });
        registry.register(ChannelRegistry.class, ChannelRegistry.builder()
                .register(Channel.builder("chat").build()).build());
        registry.register(UserLanguageStore.class, new UserLanguageStore() {
            @Override public Optional<String> languageOf(java.util.UUID uuid) { return Optional.empty(); }
            @Override public void save(java.util.UUID uuid, String value) { }
        });
        registry.register(PermissionChecker.class, PermissionChecker.ALLOW_ALL);
        registry.register(ActorDirectory.class, new ActorDirectory() {
            @Override public List<me.majhrs16.suite.api.message.Actor> onlinePlayers() { return List.of(); }
            @Override public Optional<me.majhrs16.suite.api.message.Actor> byUuid(java.util.UUID uuid) { return Optional.empty(); }
            @Override public Optional<me.majhrs16.suite.api.message.Actor> byName(String name) { return Optional.empty(); }
            @Override public me.majhrs16.suite.api.message.Actor console() {
                return me.majhrs16.suite.api.message.Actor.console("Console", null);
            }
        });

        SyncBukkitActivator activator = new SyncBukkitActivator();
        activator.start(new FakeContext(configDir, registry));

        ArgumentCaptor<BukkitChatListener> listener = ArgumentCaptor.forClass(BukkitChatListener.class);
        verify(pluginManager).registerEvents(listener.capture(), eq(plugin));
        assertTrue(listener.getValue().isActive());

        activator.stop();
        assertFalse(listener.getValue().isActive());
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

        @Override public String moduleId() { return "sync-bukkit"; }
        @Override public PluginLogger logger() { return noopLogger(); }
        @Override public EventBus events() { throw new UnsupportedOperationException(); }
        @Override public Scheduler scheduler() { return inlineScheduler(); }
        @Override public ResourceTracker resources() { return new ResourceTracker() {
            @Override public <T extends AutoCloseable> T track(T resource) { return resource; }
            @Override public Registration onRelease(Runnable cleanup) { return () -> { }; }
            @Override public void releaseAll() { }
            @Override public boolean isReleased() { return false; }
        }; }
        @Override public PlatformInfo platform() { return PlatformInfo.current("spigot", "1.21"); }
        @Override public void close() { }

        private static Scheduler inlineScheduler() {
            return new Scheduler() {
                @Override public Cancellable schedule(Runnable task, long delayMillis) {
                    task.run();
                    return cancelled();
                }
                @Override public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long periodMillis) {
                    task.run();
                    return cancelled();
                }
                @Override public Cancellable execute(Runnable task) {
                    task.run();
                    return cancelled();
                }
            };
        }

        private static Scheduler.Cancellable cancelled() {
            return new Scheduler.Cancellable() {
                @Override public void cancel() { }
                @Override public boolean isCancelled() { return true; }
            };
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
}
