package me.majhrs16.suite.syncvelocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.spi.SyncListener;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.MessageCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;

/**
 * Velocity plugin entry point for TextFormatter Suite.
 * Bootstraps the VelocitySink and registers it with the suite's host.
 */
@Plugin(
    id = "textformatter-suite-velocity",
    name = "TextFormatter Suite (Velocity)",
    version = "@version@",
    description = "Velocity proxy sync module for TextFormatter Suite",
    authors = {"majhrs16"}
)
public final class VelocityPlugin implements Module {

    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityPlugin.class);

    private final ProxyServer proxy;
    private final Path dataDirectory;
    private VelocitySink sink;
    private SyncListener listener;

    @Inject
    public VelocityPlugin(ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-velocity")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }

    /**
     * Called by Velocity after plugin loading.
     */
    public void onLoad() {
        LOGGER.info("Loading TextFormatter Suite Velocity sync module...");

        // Load config
        Path configFile = dataDirectory.resolve("sync/velocity.yml");
        String secret = "";
        List<String> servers = List.of();
        String mapping = "* -> chat.hub";

        if (Files.exists(configFile)) {
            try {
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                String content = Files.readString(configFile);
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) yaml.load(content);
                if (map != null) {
                    Object s = map.get("secret");
                    if (s instanceof String) secret = (String) s;
                    Object sv = map.get("servers");
                    if (sv instanceof List) servers = (List<String>) sv;
                    Object m = map.get("mapping");
                    if (m instanceof String) mapping = (String) m;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load velocity.yml config: {}", e.getMessage());
            }
        }

        // Create and start sink
        sink = new VelocitySink(proxy, secret, servers, mapping);
        try {
            sink.start();
            LOGGER.info("VelocitySink started successfully");
        } catch (IOException e) {
            LOGGER.error("Failed to start VelocitySink", e);
        }
    }

    @Override
    public void onDisable() {
        if (sink != null) {
            sink.stop();
            LOGGER.info("VelocitySink stopped");
        }
    }

    // Module SPI methods
    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-velocity")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .build();
    }
}