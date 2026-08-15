package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.platform.PlayerRegistry;
import me.majhrs16.cht.core.player.Subject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fabric {@link PlayerRegistry} over the server's player list.
 */
public final class FabricPlayerRegistry implements PlayerRegistry {

    private final MinecraftServer server;

    FabricPlayerRegistry(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public Collection<Subject> onlinePlayers() {
        List<ServerPlayerEntity> players = new ArrayList<>(
            server.getPlayerManager().getPlayerList());
        List<Subject> subjects = new ArrayList<>(players.size());
        for (ServerPlayerEntity player : players) {
            subjects.add(toSubject(player));
        }
        return subjects;
    }

    @Override
    public Optional<Subject> playerByName(String name) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        return Optional.ofNullable(player).map(FabricPlayerRegistry::toSubject);
    }

    @Override
    public Optional<Subject> playerByUuid(UUID uuid) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        return Optional.ofNullable(player).map(FabricPlayerRegistry::toSubject);
    }

    public static Subject toSubject(ServerPlayerEntity player) {
        return new Subject(
            player.getUuid(),
            player.getGameProfile().getName(),
            Subject.SubjectKind.PLAYER,
            player);
    }
}