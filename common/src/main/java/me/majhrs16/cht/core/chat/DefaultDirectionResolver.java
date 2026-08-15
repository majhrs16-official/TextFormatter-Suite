package me.majhrs16.cht.core.chat;

import me.majhrs16.cht.core.config.ChatSettings;
import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.Direction;
import me.majhrs16.cht.core.platform.DirectionResolver;
import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.platform.PlayerRegistry;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.core.storage.UserStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default {@link DirectionResolver} built over the platform ports.
 *
 * <p>Resolves the audience kinds that only need the player registry and the
 * language/permission stores. Kinds needing world geometry (RADIUS, WORLD) or
 * explicit Discord targets are only approximated here -- platforms that want
 * them must supply their own resolver.</p>
 */
public final class DefaultDirectionResolver implements DirectionResolver {

    private final PlayerRegistry players;
    private final UserStore users;
    private final PermissionChecker permissions;
    private final ChatSettings settings;

    public DefaultDirectionResolver(
            PlayerRegistry players,
            UserStore users,
            PermissionChecker permissions,
            ChatSettings settings) {
        this.players = Objects.requireNonNull(players, "players");
        this.users = Objects.requireNonNull(users, "users");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public List<Actor> resolve(Actor initiator, Direction direction) {
        List<Actor> result = new ArrayList<>();
        if (direction == null) {
            return result;
        }
        switch (direction.kind()) {
            case INITIATOR:
                if (initiator != null) {
                    result.add(initiator);
                }
                break;
            case OTHERS:
                for (Subject player : players.onlinePlayers()) {
                    if (!same(initiator, player)) {
                        result.add(toActor(player));
                    }
                }
                break;
            case ALL:
                for (Subject player : players.onlinePlayers()) {
                    result.add(toActor(player));
                }
                result.add(console());
                break;
            case CONSOLE:
                result.add(console());
                break;
            case PERMISSION:
                for (Subject player : players.onlinePlayers()) {
                    if (permissions.has(player, direction.qualifier())) {
                        result.add(toActor(player));
                    }
                }
                break;
            case SPECIFIC:
                for (Actor actor : direction.recipients()) {
                    if (actor != null) {
                        result.add(actor);
                    }
                }
                break;
            default:
                // WORLD/RADIUS are approximations in the survival-base model:
                // everything online, the platform resolver should refine them.
                for (Subject player : players.onlinePlayers()) {
                    result.add(toActor(player));
                }
                break;
        }
        return result;
    }

    private Actor toActor(Subject player) {
        Actor.ActorKind kind = player.isPlayer() ? Actor.ActorKind.PLAYER
            : Actor.ActorKind.CONSOLE;
        return new Actor(player.uuid(), player.name(), kind,
            users.language(player.uuid()).orElse(settings.defaultLanguage()),
            player.handle());
    }

    private Actor console() {
        return new Actor(null, "CONSOLE", Actor.ActorKind.CONSOLE,
            settings.defaultLanguage(), null);
    }

    private static boolean same(Actor initiator, Subject player) {
        if (initiator == null) {
            return false;
        }
        if (initiator.uuid() != null && player.uuid() != null) {
            return initiator.uuid().equals(player.uuid());
        }
        return initiator.name().equalsIgnoreCase(player.name());
    }
}