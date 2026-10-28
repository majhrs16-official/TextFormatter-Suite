package me.majhrs16.suite.synctelegram;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
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
 * token and chat id register a {@link TelegramSink} with the running bus; an
 * incomplete configuration leaves the package active but without a sink, so an
 * optional edge never blocks the suite.</p>
 */
public final class SyncTelegramActivator implements ModuleActivator {

    private SyncBus bus;
    private SyncSink sink;

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("sync-telegram")
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
                        "sync-telegram requires the sync-bus service"));

        TelegramConfig config = TelegramConfig.loadFromConfigDir(context.configDir());
        if (!config.usable()) {
            context.logger().info("Telegram sync sink is not configured");
            return;
        }

        TelegramSink created = new TelegramSink(config.token(), config.chatId(), new HttpTransport());
        resolved.register(created);
        context.logger().info("Telegram sync sink registered for chat " + config.chatId());

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
