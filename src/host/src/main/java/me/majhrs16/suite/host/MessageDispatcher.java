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

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Bridges the engine and the platform: expands a {@link Direction} into the
 * concrete recipient list using an {@link ActorDirectory}, runs the
 * {@code SuiteHost} pipeline per recipient and pushes the outcome through a
 * {@link ChatDelivery}.
 *
 * <p>Processes recipients in parallel using a bounded executor to prevent
 * thread exhaustion (DOS-2). The platform adapter picks the execution context
 * (async chat event, off-main scheduler) and the delivery implementation
 * hops back to the main thread when required.</p>
 */
public final class MessageDispatcher {

    private final SuiteHost host;
    private final ActorDirectory actors;
    private final ChatDelivery delivery;
    private final PermissionChecker permissions;
    private final PluginLogger logger;
    private final ExecutorService executor;
    private final ScheduledExecutorService sleepScheduler;

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
        // Bounded executor for parallel recipient processing (DOS-2)
        this.executor = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "msg-dispatcher-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        // Dedicated scheduler for sleep delays (TF-CONC-01) - doesn't block workers
        this.sleepScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "msg-dispatcher-sleep-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns the actor directory for accessing online players.
     */
    public ActorDirectory getActors() {
        return actors;
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

        // Resolve source language once for the entire message (not per recipient)
        Message messageWithResolvedSource = host.resolveSourceLanguage(message);

        // Check rate limit at emission level (once per message, before fan-out)
        if (!host.router().checkEmissionRateLimit(messageWithResolvedSource)) {
            long waitMillis = host.router().nanosUntilNextRateLimitWindow(messageWithResolvedSource) / 1_000_000L;
            return new DispatchReport(0, 0, 0, 0, 0, "rate limit exceeded: " + waitMillis + "ms");
        }

        List<Actor> recipients = expand(messageWithResolvedSource);
        if (recipients.isEmpty()) {
            return new DispatchReport(0, 0, 0, 0, 0, null);
        }

        // Process recipients in parallel using bounded executor
        List<CompletableFuture<RecipientResult>> futures = recipients.stream()
            .map(recipient -> CompletableFuture.supplyAsync(() -> {
                RoutingResult result = host.deliver(messageWithResolvedSource, recipient);
                return new RecipientResult(recipient, result);
            }, executor))
            .collect(Collectors.toList());

        // Collect results
        int delivered = 0;
        int silenced = 0;
        int redirected = 0;
        int channelRedirected = 0;

        for (CompletableFuture<RecipientResult> future : futures) {
            RecipientResult rr = null;
            try {
                rr = future.get(10, TimeUnit.SECONDS);
                Actor recipient = rr.recipient();
                RoutingResult result = rr.result();
                RouteDecision decision = result.decision();

                if (result.redirect()) {
                    delivery.deliverConsole(result.rendered());
                    redirected++;
                    continue;
                }
                if (decision.target() == PolicyTarget.CHANNEL_REDIRECT) {
                    String targetChannel = decision.redirectChannel();
                    if (targetChannel != null) {
                        Message redirectedMsg = Message.builder()
                            .from(messageWithResolvedSource)
                            .channel(targetChannel)
                            .build();
                        // Re-route to target channel
                        var reResult = host.deliver(redirectedMsg, recipient);
                        if (reResult.decision().delivered()) {
                            delivery.deliver(recipient, reResult.rendered(), redirectedMsg);
                            channelRedirected++;
                            playChannelSounds(redirectedMsg, recipient);
                            continue;
                        }
                    }
                    // Fallback: deliver to console if channel redirect fails
                    delivery.deliverConsole(result.rendered());
                    redirected++;
                    continue;
                }
                if (!decision.delivered()) {
                    logSilenced(recipient, decision);
                    silenced++;
                    continue;
                }
                Message messageForDelivery = result.message() != null ? result.message() : messageWithResolvedSource;
                
                // Apply sleep delay if set via transform - use scheduler to avoid blocking caller thread (TF-CONC-01)
                long sleepMillis = messageForDelivery.sleepMillis();
                if (sleepMillis > 0) {
                    // Schedule delivery after sleep - non-blocking continuation
                    final Message finalMsg = messageForDelivery;
                    final Actor finalRecipient = recipient;
                    final Component finalRendered = result.rendered();
                    sleepScheduler.schedule(() -> {
                        try {
                            delivery.deliver(finalRecipient, finalRendered, finalMsg);
                            playChannelSounds(finalMsg, finalRecipient);
                        } catch (Exception e) {
                            logger.error("Error delivering message after sleep to " + finalRecipient.name(), e);
                        }
                    }, sleepMillis, TimeUnit.MILLISECONDS);
                    delivered++;
                    continue;
                }
                
                delivery.deliver(recipient, result.rendered(), messageForDelivery);
                delivered++;
                playChannelSounds(messageForDelivery, recipient);
            } catch (TimeoutException e) {
                // Cancel the underlying task to free up the worker (TF-CONC-02)
                future.cancel(true);
                Actor recipient = rr != null ? rr.recipient() : null;
                logger.warn("Timeout processing recipient " + (recipient != null ? recipient.name() : "unknown") + " after 10s; task cancelled");
                silenced++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Actor recipient = rr != null ? rr.recipient() : null;
                logger.warn("Interrupted processing recipient " + (recipient != null ? recipient.name() : "unknown"));
                silenced++;
            } catch (Exception e) {
                logger.error("Error processing recipient", e);
                silenced++;
            }
        }
        return new DispatchReport(recipients.size(), delivered, silenced, redirected, channelRedirected, null);
    }

    private record RecipientResult(Actor recipient, RoutingResult result) {}

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
        
        // Play sounds from channel configuration
        for (SoundSpec sound : channel.sounds()) {
            playSoundIfAvailable(sound, recipient);
        }
        
        // Play additional sounds added via transforms (message-level sounds)
        for (String soundName : message.sounds()) {
            playSoundIfAvailable(new SoundSpec(soundName, 1.0f, 1.0f), recipient);
        }
    }
    
    private void playSoundIfAvailable(SoundSpec sound, Actor recipient) {
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

    private void logSilenced(Actor recipient, RouteDecision decision) {
        if (decision.target() == PolicyTarget.RATE_LIMIT && decision.backoffMillis() > 0) {
            logger.debug(recipient + " rate-limited for " + decision.backoffMillis() + "ms");
        } else if (decision.target() != PolicyTarget.DROP) {
            logger.debug(recipient + " silenced: " + decision.describe());
        }
    }

    /**
     * Shuts down the internal executor. Should be called when the dispatcher
     * is no longer needed (e.g., plugin reload, server shutdown) to avoid
     * thread leaks.
     */
    public void close() {
        executor.shutdown();
        sleepScheduler.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!sleepScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                sleepScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            sleepScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
