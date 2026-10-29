package me.majhrs16.suite.syncvelocity.plugin;

import com.velocitypowered.api.proxy.ProxyServer;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.velocity.VelocityConnection;
import me.majhrs16.suite.api.velocity.VelocityConnectionFactory;
import me.majhrs16.suite.api.velocity.VelocitySettings;

import java.util.Objects;

/**
 * Factory for hosts that want to expose the Velocity adapter to the suite
 * runtime. A Velocity plugin can register it as a service while it is active.
 */
public final class VelocityProxyConnectionFactory implements VelocityConnectionFactory {

    private final ProxyServer proxy;
    private final Object plugin;

    public VelocityProxyConnectionFactory(ProxyServer proxy, Object plugin) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public VelocityConnection create(VelocitySettings settings, PluginLogger logger) {
        return new VelocityProxyConnection(proxy, plugin, settings, logger);
    }
}
