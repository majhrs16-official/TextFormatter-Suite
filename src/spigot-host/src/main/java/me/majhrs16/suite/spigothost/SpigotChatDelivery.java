package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.host.port.ChatDelivery;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spigot implementation of {@link ChatDelivery}: pushes rendered Adventure
 * components through BukkitAudiences, hopping back to the main thread when
 * the engine calls from an async context (chat events are async).
 */
public final class SpigotChatDelivery implements ChatDelivery {

    private final JavaPlugin plugin;
    private final BukkitAudiences audiences;

    public SpigotChatDelivery(JavaPlugin plugin, BukkitAudiences audiences) {
        this.plugin = plugin;
        this.audiences = audiences;
    }

    @Override
    public void deliver(Actor recipient, Component rendered, Message original) {
        Player player = recipient == null ? null : recipient.handle();
        if (player != null) {
            onMain(() -> audiences.player(player).sendMessage(rendered));
        } else if (recipient != null && recipient.isConsole()) {
            onMain(() -> audiences.console().sendMessage(rendered));
        }
    }

    @Override
    public void deliverConsole(Component rendered) {
        onMain(() -> audiences.console().sendMessage(rendered));
    }

    @Override
    public void playSound(Actor recipient, SoundSpec sound) {
        Player player = recipient == null ? null : recipient.handle();
        if (player == null) {
            return;
        }
        Sound nativeSound = resolve(sound.name());
        if (nativeSound == null) {
            return;
        }
        onMain(() -> player.playSound(player.getLocation(), nativeSound,
            sound.volume(), sound.pitch()));
    }

    @Override
    public boolean hasSound(String soundName) {
        return resolve(soundName) != null;
    }

    /**
     * Maps schema names ({@code entity.experience_orb.pickup},
     * {@code ping-message.mp3}) onto Bukkit enum constants by normalizing
     * separators; unknown names are skipped instead of throwing.
     */
    static Sound resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.toUpperCase(java.util.Locale.ROOT)
            .replace('.', '_')
            .replace('-', '_');
        String base = stripAudioExtension(normalized);
        try {
            return Sound.valueOf(base);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String stripAudioExtension(String normalized) {
        for (String ext : new String[] {"_MP3", "_OGG", "_WAV"}) {
            if (normalized.endsWith(ext)) {
                return normalized.substring(0, normalized.length() - ext.length());
            }
        }
        return normalized;
    }

    private void onMain(Runnable task) {
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
