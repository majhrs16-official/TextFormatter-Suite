package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.SyncSink;
import me.majhrs16.suite.transport.HttpTransport;

/**
 * Runtime activator of the {@code sync-telegram} package.
 *
 * <p>Reads {@code config.yml} from the module config directory. A configured
 * token and chat id publish a {@link TelegramSink} as a {@link SyncSink}; an
 * incomplete configuration leaves the package active but without a sink, so an
 * optional edge never blocks the suite. The {@code sync-bus} discovers the
 * published sink, which keeps this package independent from the bus.</p>
 */
public final class SyncTelegramActivator implements ModuleActivator {

    private SyncBus bus;
    private TelegramSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-telegram")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("sync-sink", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        TelegramConfig config = TelegramConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            context.logger().info("Telegram sync sink is not configured");
            return;
        }

        TelegramSink created = new TelegramSink(config.token(), config.chatId(), new HttpTransport());
        context.services().register(SyncSink.class, created);
        this.bus = context.services().find(SyncBus.class).orElse(null);
        this.sink = created;
        context.logger().info("Telegram sync sink published for chat " + config.chatId());
    }

    @Override
    public void stop() {
        // The module stops its own sink so the token buffer is cleared even
        // when the bus is not installed. The bus also stops it on
        // ModuleDeactivatedEvent; both stops are idempotent.
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
