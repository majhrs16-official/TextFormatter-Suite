package me.majhrs16.suite.syncsocket;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;

/**
 * Runtime activator of the {@code sync-socket} package.
 *
 * <p>Reads {@code tcp.yml} and {@code udp.yml} from the module config directory
 * and registers each usable sink with the running {@link SyncBus}. A sink that
 * is not configured is skipped with a log line; the package still activates so
 * the rest of the suite is not blocked by an optional edge.</p>
 */
public final class SyncSocketActivator implements ModuleActivator {

    private SyncBus bus;
    private SyncSink tcp;
    private SyncSink udp;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-socket")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(2, 1, 0)))
                .require(Requirement.atLeast("sync-bus", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        SyncBus resolved = context.services().find(SyncBus.class)
                .orElseThrow(() -> new IllegalStateException(
                        "sync-socket requires the sync-bus service"));

        SocketConfig tcpConfig = SocketConfig.loadTcpFromConfigDir(context.configDir());
        SocketConfig udpConfig = SocketConfig.loadUdpFromConfigDir(context.configDir());

        SyncSink registeredTcp = null;
        try {
            if (tcpConfig.usable()) {
                registeredTcp = new TcpSink(
                        tcpConfig.remoteHost(), tcpConfig.remotePort(), tcpConfig.localPort());
                resolved.register(registeredTcp);
                context.logger().info("TCP sync sink registered for "
                        + tcpConfig.remoteHost() + ":" + tcpConfig.remotePort());
            } else {
                context.logger().info("TCP sync sink is not configured");
            }

            if (udpConfig.usable()) {
                udp = new UdpSink(
                        udpConfig.remoteHost(), udpConfig.remotePort(), udpConfig.localPort());
                resolved.register(udp);
                context.logger().info("UDP sync sink registered for "
                        + udpConfig.remoteHost() + ":" + udpConfig.remotePort());
            } else {
                context.logger().info("UDP sync sink is not configured");
            }
        } catch (RuntimeException failure) {
            if (registeredTcp != null) {
                resolved.unregister(registeredTcp.name());
            }
            if (udp != null) {
                resolved.unregister(udp.name());
            }
            throw failure;
        }

        this.bus = resolved;
        this.tcp = registeredTcp;
    }

    @Override
    public void stop() {
        if (bus != null) {
            if (tcp != null) {
                bus.unregister(tcp.name());
            }
            if (udp != null) {
                bus.unregister(udp.name());
            }
        }
        bus = null;
        tcp = null;
        udp = null;
    }
}
