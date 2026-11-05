package me.majhrs16.suite.coretranslator;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.runtime.ModuleContext;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI adapter for {@link CoreTranslatorPlaceholders}.
 *
 * <p>This is the restored {@code CoreTranslator} feature from the previous
 * ChatTranslator project: identifier {@code cot}, placeholders such as
 * {@code %cot_translate;en;es;hello%}. It is loaded only when PlaceholderAPI is
 * present.</p>
 */
@Deprecated
public final class CoreTranslatorPapiExpansion extends PlaceholderExpansion {

    private final CoreTranslatorPlaceholders placeholders;

    public CoreTranslatorPapiExpansion(CoreTranslatorPlaceholders placeholders) {
        this.placeholders = placeholders;
    }

    /** Registers the expansion and tracks its removal with the module scope. */
    public static void register(JavaPlugin plugin,
                                CoreTranslatorPlaceholders placeholders,
                                ModuleContext context) {
        CoreTranslatorPapiExpansion expansion = new CoreTranslatorPapiExpansion(placeholders);
        if (!expansion.register()) {
            context.logger().warn("CoreTranslator: PlaceholderAPI rejected the cot expansion");
            return;
        }
        context.resources().onRelease(expansion::unregister);
        context.logger().info("CoreTranslator registered %cot_* placeholders for "
            + plugin.getName());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "cot";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Majhrs16";
    }

    @Override
    public @NotNull String getVersion() {
        return CoreTranslatorModule.VERSION.toString();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
        return placeholders.resolve(actor(player), identifier);
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String identifier) {
        return placeholders.resolve(actor(player), identifier);
    }

    private Actor actor(OfflinePlayer player) {
        if (player == null) {
            return placeholders.console();
        }
        String name = player.getName();
        if (name == null || name.isBlank()) {
            name = player.getUniqueId().toString();
        }
        return new Actor(player.getUniqueId(), name, Actor.ActorKind.PLAYER, null, player);
    }
}
