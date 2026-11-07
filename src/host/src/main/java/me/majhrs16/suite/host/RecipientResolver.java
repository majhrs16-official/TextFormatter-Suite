package me.majhrs16.suite.host;

import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Materializes a {@link Direction} into the concrete, de-duplicated recipient
 * list.
 *
 * <p>It only expands recipients and does not render or deliver, so the pipeline
 * engine can reuse it without depending on the delivery implementation.</p>
 *
 * <p>Fail-closed policy: a {@code PERMISSION} or {@code WORLD} direction
 * without a qualifier resolves to nobody and logs a warning. An omitted field
 * must never widen a restricted message into a broadcast.</p>
 */
public final class RecipientResolver {

    private final ActorDirectory actors;
    private final PermissionChecker permissions;
    private final PluginLogger logger;

    public RecipientResolver(ActorDirectory actors,
                             PermissionChecker permissions,
                             PluginLogger logger) {
        this.actors = Objects.requireNonNull(actors, "actors");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the recipients of {@code message}, in resolution order */
    public List<Actor> resolve(Message message) {
        Objects.requireNonNull(message, "message");
        Direction direction = message.direction();

        List<Actor> resolved = switch (direction.kind()) {
            case INITIATOR -> List.of(message.sender());
            case OTHERS -> withoutSelf(actors.onlinePlayers(), message.sender());
            case ALL -> actors.onlinePlayers();
            case CONSOLE -> List.of(actors.console());
            case SPECIFIC -> List.of(direction.recipients());
            case PERMISSION -> withPermission(actors.onlinePlayers(), direction.qualifier());
            case WORLD -> playersInWorld(direction.qualifier());
            case RADIUS -> playersNear(message.sender(), direction.qualifier());
        };
        return resolved.stream().distinct().toList();
    }

    private List<Actor> withoutSelf(List<Actor> candidates, Actor self) {
        List<Actor> others = new ArrayList<>(candidates.size());
        for (Actor candidate : candidates) {
            if (!candidate.equals(self)) {
                others.add(candidate);
            }
        }
        return others;
    }

    private List<Actor> withPermission(List<Actor> candidates, String permission) {
        if (permission == null || permission.isEmpty()) {
            logger.warn("PERMISSION direction without qualifier; delivering to nobody");
            return List.of();
        }
        List<Actor> allowed = new ArrayList<>(candidates.size());
        for (Actor candidate : candidates) {
            if (permissions.has(candidate, permission)) {
                allowed.add(candidate);
            }
        }
        return allowed;
    }

    private List<Actor> playersInWorld(String world) {
        if (world == null || world.isEmpty()) {
            logger.warn("WORLD direction without qualifier; delivering to nobody");
            return List.of();
        }
        List<Actor> located = actors.playersInWorld(world);
        if (located.isEmpty()) {
            logger.debug("no world resolution for '" + world + "'; delivering to nobody");
        }
        return located;
    }

    private List<Actor> playersNear(Actor center, String qualifier) {
        if (qualifier == null) {
            logger.warn("missing radius qualifier; delivering to nobody");
            return List.of();
        }
        double radius;
        try {
            radius = Double.parseDouble(qualifier);
        } catch (NumberFormatException exception) {
            logger.warn("invalid radius qualifier '" + qualifier + "'; delivering to nobody");
            return List.of();
        }
        return actors.playersNear(center, radius);
    }
}
