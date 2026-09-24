package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.spi.PlaceholderResolver;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI bridge implementing the suite {@link PlaceholderResolver}
 * port. All {@code %token%} inputs are delegated to PAPI for the actor's
 * player; when PAPI is absent the resolver reports unavailable and the
 * renderer keeps tokens untouched.
 *
 * <p>PAPI classes are only touched from the nested hook, which is reached
 * exclusively after the plugin-presence check — safe under lazy resolution
 * even though the dependency is compileOnly.</p>
 */
public final class SpigotPlaceholderResolver implements PlaceholderResolver {

    private final boolean available;

    public SpigotPlaceholderResolver() {
        this.available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @Override
    public String resolve(Actor actor, String input) {
        if (!available || input == null || !input.contains("%")) {
            return input == null ? "" : input;
        }
        Player player = actor == null ? null : actor.handle();
        if (player == null) {
            return input;
        }
        return PapiHook.apply(player, input);
    }

    @Override
    public boolean available() {
        return available;
    }

    /** Isolated so org.bukkit.craftbukkit-free environments never load PAPI types eagerly. */
    private static final class PapiHook {
        static String apply(Player player, String input) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, input);
        }
    }
}
