package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.player.Channel;
import me.majhrs16.cht.core.player.Subject;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
/**
 * Spigot {@link ChatDisplay}: renders Adventure components through the Bukkit
 * audiences and maps sounds onto the registry.
 */
final class SpigotChatDisplay implements ChatDisplay {

    private final JavaPlugin plugin;
    private final BukkitAudiences audiences;

    SpigotChatDisplay(JavaPlugin plugin, BukkitAudiences audiences) {
        this.plugin = plugin;
        this.audiences = audiences;
    }

    @Override
    public void send(Subject recipient, Component message, Channel channel) {
        Object handle = recipient.handle();
        if (handle instanceof CommandSender) {
            audiences.sender((CommandSender) handle).sendMessage(message);
        } else {
            audiences.console().sendMessage(message);
        }
    }

    @Override
    public void sendToConsole(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void dispatchServerCommand(String command) {
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    @Override
    public void dispatchCommand(Subject actor, String command, Channel channel) {
        Object handle = actor.handle();
        if (handle instanceof CommandSender) {
            Bukkit.getScheduler().runTask(plugin,
                () -> Bukkit.dispatchCommand((CommandSender) handle, command));
        } else {
            dispatchServerCommand(command);
        }
    }

    @Override
    public void playSound(Subject recipient, SoundSpec sound) {
        Object handle = recipient.handle();
        if (!(handle instanceof Player)) {
            return;
        }
        Player player = (Player) handle;
        try {
            player.playSound(player.getLocation(), soundOf(sound.name()), sound.volume(), sound.pitch());
        } catch (IllegalArgumentException ignored) {
            // unknown sound name, skip silently
        }
    }

    private static org.bukkit.Sound soundOf(String name) {
        return org.bukkit.Sound.valueOf(name.replace('-', '_')
            .toUpperCase(Locale.ROOT));
    }

    @Override
    public boolean hasSound(String key) {
        try {
            soundOf(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}