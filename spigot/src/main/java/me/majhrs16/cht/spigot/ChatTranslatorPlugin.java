package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.ChatTranslatorApp;
import me.majhrs16.cht.core.config.DefaultFiles;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.platform.ConfigFolder;
import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.platform.PlaceholderResolver;
import me.majhrs16.cht.core.platform.Scheduler;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spigot entry point. Wires the platform ports into the {@link ChatTranslatorApp}
 * and registers the event/command handlers.
 */
public final class ChatTranslatorPlugin extends JavaPlugin {

    private BukkitAudiences audiences;
    private ChatTranslatorApp app;

    @Override
    public void onEnable() {
        ConfigFolder folder = new SpigotConfigFolder(getDataFolder());
        SpigotLogger logger = new SpigotLogger(this);
        DefaultFiles.copyMissing(folder, logger);
        this.audiences = BukkitAudiences.create(this);

        ChatDisplay display = new SpigotChatDisplay(this, audiences);
        Scheduler scheduler = new SpigotScheduler(this);
        PermissionChecker permissions = new SpigotPermissionChecker();
        PlaceholderResolver placeholders = new SpigotPlaceholderResolver();

        this.app = ChatTranslatorApp.builder()
            .configFolder(folder)
            .display(display)
            .scheduler(scheduler)
            .players(new SpigotPlayerRegistry())
            .permissions(permissions)
            .placeholders(placeholders)
            .logger(logger)
            .build();

        getServer().getPluginManager().registerEvents(new ChatListener(app), this);
        getCommand("cht").setExecutor(new ChtCommand(app, getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        if (audiences != null) {
            audiences.close();
        }
        if (app != null) {
            app.shutdown();
        }
    }

    public ChatTranslatorApp app() {
        return app;
    }
}