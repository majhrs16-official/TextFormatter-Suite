package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.UserLanguageStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fabric implementation of {@link ActorDirectory}.
 */
public final class FabricActorDirectory implements ActorDirectory {

    private final UserLanguageStore languageStore;
    private final MinecraftServer server;

    public FabricActorDirectory(MinecraftServer server) {
        this(server, null);
    }

    public FabricActorDirectory(MinecraftServer server, UserLanguageStore languageStore) {
        this.server = server;
        this.languageStore = languageStore;
    }

    @Override
    public List<Actor> onlinePlayers() {
        List<Actor> actors = new ArrayList<>();
        if (server == null || server.getPlayerManager() == null) {
            return actors;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            actors.add(actorOf(player));
        }
        return actors;
    }

    @Override
    public Optional<Actor> byUuid(UUID uuid) {
        if (server == null || server.getPlayerManager() == null) {
            return Optional.empty();
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        return player == null ? Optional.empty() : Optional.of(actorOf(player));
    }

    @Override
    public Optional<Actor> byName(String name) {
        if (server == null || server.getPlayerManager() == null) {
            return Optional.empty();
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        return player == null ? Optional.empty() : Optional.of(actorOf(player));
    }

    @Override
    public Actor console() {
        String serverName = server == null ? "Fabric Server" : "Fabric Server";
        return Actor.console(serverName, null);
    }

    @Override
    public List<Actor> playersInWorld(String world) {
        List<Actor> actors = new ArrayList<>();
        if (server == null || server.getPlayerManager() == null) {
            return actors;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getServerWorld().getRegistryKey().getValue().toString().equals(world)) {
                actors.add(actorOf(player));
            }
        }
        return actors;
    }

    @Override
    public List<Actor> playersNear(Actor center, double radiusBlocks) {
        ServerPlayerEntity origin = center == null ? null : center.handle();
        if (origin == null || !origin.isAlive()) {
            return List.of();
        }
        double radiusSquared = radiusBlocks * radiusBlocks;
        List<Actor> actors = new ArrayList<>();
        for (ServerPlayerEntity player : origin.getServerWorld().getPlayers()) {
            if (player.squaredDistanceTo(origin) <= radiusSquared) {
                actors.add(actorOf(player));
            }
        }
        return actors;
    }

    public Actor actorOf(ServerPlayerEntity player) {
        return new Actor(player.getUuid(), player.getName().getString(),
            Actor.ActorKind.PLAYER, languageOf(player).orElse(Language.AUTO), player);
    }

    private Optional<Language> languageOf(ServerPlayerEntity player) {
        String stored = languageStore == null ? null
            : languageStore.languageOf(player.getUuid()).orElse(null);
        return me.majhrs16.suite.fabrichost.logic.LangSetting.effective(
            stored, clientLocale(player));
    }

    private static Language clientLocale(ServerPlayerEntity player) {
        // In Fabric 1.21, locale is accessed differently
        // Try to get from player's profile or use null
        return null;
    }
}
