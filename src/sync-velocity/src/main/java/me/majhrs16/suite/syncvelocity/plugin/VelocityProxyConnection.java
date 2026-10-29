package me.majhrs16.suite.syncvelocity.plugin;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.velocity.VelocityConnection;
import me.majhrs16.suite.api.velocity.VelocityListener;
import me.majhrs16.suite.api.velocity.VelocitySettings;

import java.util.List;
import java.util.Objects;

/**
 * Velocity API adapter for the neutral {@link VelocityConnection} port.
 *
 * <p>This is the only class in the Velocity distribution that registers proxy
 * channels and listeners. It never formats or routes messages; those decisions
 * belong to {@code VelocitySink} in the neutral package.</p>
 */
public final class VelocityProxyConnection implements VelocityConnection {

    private final ProxyServer proxy;
    private final Object plugin;
    private final VelocitySettings settings;
    private final PluginLogger logger;

    private ChannelIdentifier channel;
    private EventHandler<PluginMessageEvent> handler;
    private boolean started;

    public VelocityProxyConnection(ProxyServer proxy, Object plugin,
                                   VelocitySettings settings, PluginLogger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public synchronized void start(VelocityListener listener) {
        if (started) {
            return;
        }
        ChannelIdentifier registered = MinecraftChannelIdentifier.from(settings.channel());
        EventHandler<PluginMessageEvent> registeredHandler = event -> {
            if (!registered.equals(event.getIdentifier())) {
                return;
            }
            byte[] payload = event.getData();
            if (payload != null) {
                listener.onMessage(payload.clone());
            }
        };
        proxy.getChannelRegistrar().register(registered);
        try {
            proxy.getEventManager().register(
                plugin, PluginMessageEvent.class, registeredHandler);
        } catch (RuntimeException failure) {
            proxy.getChannelRegistrar().unregister(registered);
            throw failure;
        }
        channel = registered;
        handler = registeredHandler;
        started = true;
        logger.info("Velocity channel registered: " + settings.channel());
    }

    @Override
    public synchronized void stop() {
        if (!started) {
            return;
        }
        if (handler != null) {
            proxy.getEventManager().unregisterListener(plugin, handler);
            handler = null;
        }
        if (channel != null) {
            proxy.getChannelRegistrar().unregister(channel);
            channel = null;
        }
        started = false;
        logger.info("Velocity channel unregistered: " + settings.channel());
    }

    @Override
    public List<String> servers() {
        return proxy.getAllServers().stream()
            .map(server -> server.getServerInfo().getName())
            .toList();
    }

    @Override
    public boolean send(String server, byte[] payload) {
        if (!started || channel == null) {
            return false;
        }
        return proxy.getServer(server)
            .map(target -> sendTo(target, payload))
            .orElse(false);
    }

    private boolean sendTo(RegisteredServer target, byte[] payload) {
        return target.sendPluginMessage(channel, payload);
    }
}
