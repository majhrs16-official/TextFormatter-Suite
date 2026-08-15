package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.config.DefaultFiles;
import me.majhrs16.cht.core.platform.ConfigFolder;
import me.majhrs16.cht.core.platform.PluginLogger;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.server.MinecraftServer;

/**
 * Fabric entry point. Builds the {@link ChatTranslatorApp} once the server is
 * available and tears it down on stop.
 *
 * <p>{@link #app()} is the global hook the mixins use to inject messages
 * produced by the vanilla server (join/leave/death/advancement broadcasts).</p>
 */
public final class ChatTranslatorMod implements ModInitializer {

    private static ChatTranslatorApp INSTANCE;

    private ChatTranslatorApp app;
    private FabricScheduler scheduler;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarted(MinecraftServer server) {
        ConfigFolder folder = new FabricConfigFolder();
        PluginLogger logger = new FabricLogger();
        DefaultFiles.copyMissing(folder, logger);

        this.scheduler = new FabricScheduler(server);
        this.app = ChatTranslatorApp.builder()
            .configFolder(folder)
            .display(new FabricChatDisplay(server))
            .scheduler(scheduler)
            .players(new FabricPlayerRegistry(server))
            .permissions(new FabricPermissionChecker(server))
            .placeholders(new FabricPlaceholderResolver())
            .logger(logger)
            .build();
        INSTANCE = app;

        ChatEventWiring.register(app);
    }

    private void onServerStopping(MinecraftServer server) {
        INSTANCE = null;
        if (app != null) {
            app.shutdown();
            app = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    public static ChatTranslatorApp app() {
        return INSTANCE;
    }
}