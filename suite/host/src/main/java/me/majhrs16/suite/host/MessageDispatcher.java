package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.host.port.ChatDelivery;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bridges the engine and the platform: expands a {@link Direction} into the
 * concrete recipient list using an {@link ActorDirectory}, runs the
 * {@code SuiteHost} pipeline per recipient and pushes the outcome through a
 * {@link ChatDelivery}.
 *
 * <p>Deliberately synchronous and thread-agnostic: the platform adapter picks
 * the execution context (async chat event, off-main scheduler) and the
 * delivery implementation hops back to the main thread when required.</p>
 */
public final class MessageDispatcher {

    private final SuiteHost host;
    private final ActorDirectory actors;
    private final ChatDelivery delivery;
    private final PermissionChecker permissions;
    private final PluginLogger logger;

    public MessageDispatcher(SuiteHost host,
                             ActorDirectory actors,
                             ChatDelivery delivery,
                             PermissionChecker permissions,
                             PluginLogger logger) {
        this.host = Objects.requireNonNull(host, "host");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Routes and delivers one message end-to-end.
     *
     * @return per-target counters; never throws for routing outcomes.
     */
    public DispatchReport dispatch(Message message) {
        if (message.isCancelled()) {
            return DispatchReport.none("cancelled");
        }

        List<Actor> recipients = expand(message);
        int delivered = 0;
        int silenced = 0;
        int redirected = 0;

        for (Actor recipient : recipients) {
            RoutingResult result = host.deliver(message, recipient);
            RouteDecision decision = result.decision();

            if (result.redirect()) {
                delivery.deliverConsole(result.rendered());
                redirected++;
                continue;
            }
            if (!decision.delivered()) {
                logSilenced(recipient, decision);
                silenced++;
                continue;
            }
            delivery.deliver(recipient, result.rendered(), message);
            delivered++;
            playChannelSounds(message, recipient);
        }
        return new DispatchReport(recipients.size(), delivered, silenced, redirected, null);
    }

    /**
     * Materializes the direction into a de-duplicated recipient list.
     *
     * <p>Fail-open policy: an empty qualifier on {@code PERMISSION} or
     * {@code WORLD} resolves to every online player instead of nobody.</p>
     */
    private List<Actor> expand(Message message) {
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
            return candidates;
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
        if (world == null) {
            return actors.onlinePlayers();
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

    private void playChannelSounds(Message message, Actor recipient) {
        if (!host.config().soundEnabled()) {
            return;
        }
        Channel channel = host.channels().resolve(message.channel());
        for (SoundSpec sound : channel.sounds()) {
            try {
                if (delivery.hasSound(sound.name())) {
                    delivery.playSound(recipient, sound);
                } else {
                    logger.debug("sound '" + sound.name() + "' not in registry; skipped");
                }
            } catch (RuntimeException exception) {
                logger.error("sound '" + sound.name() + "' failed", exception);
            }
        }
    }

    private void logSilenced(Actor recipient, RouteDecision decision) {
        if (decision.target() == PolicyTarget.RATE_LIMIT && decision.backoffMillis() > 0) {
            logger.debug(recipient + " rate-limited for " + decision.backoffMillis() + "ms");
        } else if (decision.target() != PolicyTarget.DROP) {
            logger.debug(recipient + " silenced: " + decision.describe());
        }
    }
}
