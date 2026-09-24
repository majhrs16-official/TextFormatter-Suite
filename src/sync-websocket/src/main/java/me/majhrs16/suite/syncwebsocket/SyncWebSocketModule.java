package me.majhrs16.suite.syncwebsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the WebSocket sync module: provides the {@code sync-sink}
 * capability with WebSocket support for real-time synchronization.
 */
public final class SyncWebSocketModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-websocket")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
            .provide(Capability.of("websocket-server", SemVer.of(2, 1, 0)))
            .build();
    }
}