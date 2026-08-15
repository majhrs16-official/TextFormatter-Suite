package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.platform.PlaceholderResolver;
import me.majhrs16.cht.core.player.Subject;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Spigot {@link PlaceholderResolver}. Uses PlaceholderAPI when present and
 * returns the input untouched otherwise.
 */
final class SpigotPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String resolve(Subject subject, String input) {
        if (!available()) {
            return input;
        }
        Object handle = subject.handle();
        if (handle instanceof Player) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                (Player) handle, input);
        }
        return input;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }
}