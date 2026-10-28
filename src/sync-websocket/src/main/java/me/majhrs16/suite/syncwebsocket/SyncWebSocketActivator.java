package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

/**
 * Runtime activator of the {@code sync-websocket} package.
 *
 * <p>Reads {@code config.yml} from the module config directory. A configured
 * token starts the WebSocket server and registers the sink with the running
 * bus; an incomplete configuration leaves the package active without a sink.</p>
 */
public final class SyncWebSocketActivator implements ModuleActivator {

    private SyncBus bus;
    private SyncSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-websocket")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
                .provide(Capability.of("websocket-server", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("sync-bus", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        SyncBus resolved = context.services().find(SyncBus.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-websocket requires the sync-bus service"));

        WebSocketSyncConfig config = WebSocketSyncConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            context.logger().info("WebSocket sync sink is not configured");
            return;
        }

        WebSocketSyncSink created = new WebSocketSyncSink(
                config.port(), config.token(), context.logger(), config.bind());
        resolved.register(created);
        context.logger().info("WebSocket sync sink registered on " + config.bind() + ":" + config.port());

        this.bus = resolved;
        this.sink = created;
    }

    @Override
    public void stop() {
        if (bus != null && sink != null) {
            bus.unregister(sink.name());
        }
        bus = null;
        sink = null;
    }
}
