package me.majhrs16.suite.syncvelocity.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.syncvelocity.VelocityConfig;
import me.majhrs16.suite.syncvelocity.VelocitySink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Velocity plugin entry point.
 *
 * <p>The plugin is a thin adapter: it owns the Velocity lifecycle and hands a
 * {@link VelocityProxyConnection} to the neutral sink. All queueing, mapping,
 * retry and payload logic lives in {@code sync-velocity}.</p>
 */
@Plugin(
    id = "textformatter-suite-velocity",
    name = "TextFormatter Suite (Velocity)",
    version = "2.1.0",
    description = "Velocity proxy sync module for TextFormatter Suite",
    authors = {"majhrs16"}
)
public final class VelocityPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityPlugin.class);

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final PluginLogger logger;
    private VelocitySink sink;

    @Inject
    public VelocityPlugin(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.logger = new Slf4jPluginLogger(LOGGER);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        VelocityConfig config = VelocityConfig.load(dataDirectory);
        if (!config.enabled) {
            logger.info("Velocity sync sink is disabled by config");
            return;
        }
        try {
            VelocityProxyConnection connection = new VelocityProxyConnection(
                proxy, this, config.settings(), logger);
            sink = new VelocitySink(connection, config, config.settings(), logger);
            sink.start();
            logger.info("Velocity sync sink started on channel " + config.channel);
        } catch (Exception failure) {
            logger.error("Velocity sync sink could not start", failure);
            sink = null;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sink != null) {
            sink.stop();
            sink = null;
            logger.info("Velocity sync sink stopped");
        }
    }
}
