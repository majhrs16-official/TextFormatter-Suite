package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spigot implementation of {@link ActorDirectory}: every query maps live
 * Bukkit players onto suite {@link Actor}s carrying the native
 * {@link Player} handle.
 *
 * <p>Language resolution order per player: stored preference
 * ({@link UserLanguageStore}; {@code off} maps to {@code AUTO} = receive
 * source text) → client locale ({@code Player#getLocale()}, 1.12.2+) →
 * null (host default applies).</p>
 */
public final class SpigotActorDirectory implements ActorDirectory {

    private final UserLanguageStore languageStore;

    public SpigotActorDirectory() {
        this(null);
    }

    public SpigotActorDirectory(UserLanguageStore languageStore) {
        this.languageStore = languageStore;
    }

    @Override
    public List<Actor> onlinePlayers() {
        // Snapshot defensivo: onChat corre async y la vista viva puede mutar
        // (join/quit en el main thread) mientras iteramos.
        Object[] snapshot = Bukkit.getOnlinePlayers().toArray();
        List<Actor> actors = new ArrayList<>(snapshot.length);
        for (Object element : snapshot) {
            if (element instanceof Player player) {
                actors.add(actorOf(player));
            }
        }
        return actors;
    }

    @Override
    public Optional<Actor> byUuid(UUID uuid) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.getUniqueId().equals(uuid))
            .findFirst()
            .map(this::actorOf);
    }

    @Override
    public Optional<Actor> byName(String name) {
        return Optional.ofNullable(Bukkit.getPlayerExact(name)).map(this::actorOf);
    }

    @Override
    public Actor console() {
        return Actor.console(Bukkit.getName(), null);
    }

    @Override
    public List<Actor> playersInWorld(String world) {
        List<Actor> actors = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getName().equals(world)) {
                actors.add(actorOf(player));
            }
        }
        return actors;
    }

    @Override
    public List<Actor> playersNear(Actor center, double radiusBlocks) {
        Player origin = center == null ? null : center.handle();
        if (origin == null || !origin.isOnline()) {
            return List.of();
        }
        double radiusSquared = radiusBlocks * radiusBlocks;
        List<Actor> actors = new ArrayList<>();
        for (Player player : origin.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(origin.getLocation()) <= radiusSquared) {
                actors.add(actorOf(player));
            }
        }
        return actors;
    }

    /** Maps one Bukkit player onto its immutable suite representation. */
    public Actor actorOf(Player player) {
        return new Actor(player.getUniqueId(), player.getName(),
            Actor.ActorKind.PLAYER, languageOf(player), player);
    }

    private Language languageOf(Player player) {
        String stored = languageStore == null ? null
            : languageStore.languageOf(player.getUniqueId()).orElse(null);
        return me.majhrs16.suite.spigothost.logic.LangSetting.effective(
            stored, clientLocale(player));
    }

    private static Language clientLocale(Player player) {
        String raw = player.getLocale();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lowered = raw.toLowerCase(java.util.Locale.ROOT);
        // Código completo primero ("zh_cn" → "zh-cn" existe como código);
        // si no, la base ("pt_br" → "pt").
        Language full = Language.of(lowered).orElse(null);
        if (full != null) {
            return full;
        }
        return Language.of(lowered.split("_")[0]).orElse(null);
    }
}
