package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

/**
 * Runtime activator of the {@code sync-websocket} package.
 *
 * <p>Reads {@code config.yml} from the module config directory. A configured
 * token publishes the WebSocket sink; an incomplete configuration leaves the
 * package active without a sink. The {@code sync-bus} discovers the published
 * {@link SyncSink}, so this package does not require the bus to activate.</p>
 */
public final class SyncWebSocketActivator implements ModuleActivator {

    private SyncBus bus;
    private WebSocketSyncSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-websocket")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(1, 0, 0)))
                .provide(Capability.of("websocket-server", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        WebSocketSyncConfig config = WebSocketSyncConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            context.logger().info("WebSocket sync sink is not configured");
            return;
        }

        WebSocketSyncSink created = new WebSocketSyncSink(
                config.port(), config.token(), context.logger(), config.bind());
        context.services().register(SyncSink.class, created);
        this.bus = context.services().find(SyncBus.class).orElse(null);
        this.sink = created;
        context.logger().info("WebSocket sync sink published on " + config.bind() + ":" + config.port());
    }

    @Override
    public void stop() {
        // The module owns the WebSocket worker threads it started; they must be
        // gone before ModuleHost checks the module thread group. The bus also
        // stops the sink on ModuleDeactivatedEvent; both stops are idempotent.
        if (sink == null) {
            return;
        }
        if (bus != null) {
            bus.unregister(sink.name());
        }
        sink.stop();
        sink = null;
        bus = null;
    }
}
