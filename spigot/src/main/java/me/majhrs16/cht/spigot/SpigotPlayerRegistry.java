package me.majhrs16.cht.spigot;

import me.majhrs16.cht.core.platform.PlayerRegistry;
import me.majhrs16.cht.core.player.Subject;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spigot {@link PlayerRegistry} over the Bukkit online player list.
 */
final class SpigotPlayerRegistry implements PlayerRegistry {

    @Override
    public Collection<Subject> onlinePlayers() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<Subject> subjects = new ArrayList<>(online.size());
        for (Player player : online) {
            subjects.add(toSubject(player));
        }
        return subjects;
    }

    @Override
    public Optional<Subject> playerByName(String name) {
        Player player = Bukkit.getPlayerExact(name);
        return Optional.ofNullable(player).map(SpigotPlayerRegistry::toSubject);
    }

    @Override
    public Optional<Subject> playerByUuid(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return Optional.ofNullable(player).map(SpigotPlayerRegistry::toSubject);
    }

    static Subject toSubject(Player player) {
        return new Subject(
            player.getUniqueId(),
            player.getName(),
            Subject.SubjectKind.PLAYER,
            player);
    }
}