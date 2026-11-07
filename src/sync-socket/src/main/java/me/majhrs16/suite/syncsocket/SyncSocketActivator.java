package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime activator of the {@code sync-socket} package.
 *
 * <p>Reads {@code tcp.yml} and {@code udp.yml} from the module config directory
 * and publishes each usable sink as a {@link SyncSink}. A sink that is not
 * configured is skipped with a log line; the package still activates so the
 * rest of the suite is not blocked by an optional edge. The {@code sync-bus}
 * discovers the published sinks, so this package does not require the bus.</p>
 */
public final class SyncSocketActivator implements ModuleActivator {

    private SyncBus bus;
    private final List<SyncSink> sinks = new ArrayList<>();

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-socket")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        this.bus = context.services().find(SyncBus.class).orElse(null);
        SocketConfig tcpConfig = SocketConfig.loadTcpFromConfigDir(context.configDir());
        SocketConfig udpConfig = SocketConfig.loadUdpFromConfigDir(context.configDir());

        if (tcpConfig.usable()) {
            TcpSink tcp = new TcpSink(
                    tcpConfig.remoteHost(), tcpConfig.remotePort(), tcpConfig.localPort());
            context.services().register(SyncSink.class, tcp);
            sinks.add(tcp);
            context.logger().info("TCP sync sink published for "
                    + tcpConfig.remoteHost() + ":" + tcpConfig.remotePort());
        } else {
            context.logger().info("TCP sync sink is not configured");
        }

        if (udpConfig.usable()) {
            UdpSink udp = new UdpSink(
                    udpConfig.remoteHost(), udpConfig.remotePort(), udpConfig.localPort());
            context.services().register(SyncSink.class, udp);
            sinks.add(udp);
            context.logger().info("UDP sync sink published for "
                    + udpConfig.remoteHost() + ":" + udpConfig.remotePort());
        } else {
            context.logger().info("UDP sync sink is not configured");
        }
    }

    @Override
    public void stop() {
        // The module owns its inbound sockets and threads; release them before
        // ModuleHost checks the module thread group. The bus also stops the
        // sinks on ModuleDeactivatedEvent; both stops are idempotent.
        if (bus != null) {
            for (SyncSink sink : sinks) {
                bus.unregister(sink.name());
            }
        }
        for (SyncSink sink : sinks) {
            sink.stop();
        }
        sinks.clear();
        bus = null;
    }
}
