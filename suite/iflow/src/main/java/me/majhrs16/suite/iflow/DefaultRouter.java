package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default iFlow implementation: per-recipient firewall driven by channel
 * permission policies (both idioms), channel default-policy and the active
 * rule set.
 *
 * <p>Evaluation order per recipient:</p>
 * <ol>
 *   <li>resolves the route tail (channel) for {@code message.channel()};</li>
 *   <li>rejects emitters without the channel {@code sendPolicy()} — nothing is
 *       delivered to anyone, the sender is the one marked (they keep seeing
 *       their own tail, per ADR);</li>
 *   <li>receivers without {@code receivePolicy()} are silently skipped;</li>
 *   <li>the first matching {@link Rule} decides the target;</li>
 *   <li>no rule matched → channel default policy with the channel
 *       {@code rateLimitPerSecond()} budget enforced;</li>
 *   <li>rate-limited deliveries report a backoff instead of dropping.</li>
 * </ol>
 */
public final class DefaultRouter implements Router {

    private final ChannelRegistry channels;
    private final PermissionChecker permissions;
    private final RateLimiter rateLimit;
    private final AtomicReference<List<Rule>> rules = new AtomicReference<>(List.of());

    public DefaultRouter(ChannelRegistry channels) {
        this(channels, PermissionChecker.ALLOW_ALL);
    }

    public DefaultRouter(ChannelRegistry channels, PermissionChecker permissions) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.rateLimit = new RateLimiter(1);
    }

    @Override
    public RouteDecision route(Message message, Actor recipient) {
        Objects.requireNonNull(recipient, "recipient");
        String path = message.channel() == null ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        Actor emitter = message.sender();
        boolean selfEcho = recipient.equals(emitter);

        if (!selfEcho && !allowed(emitter, channel.sendPolicy())) {
            return new RouteDecision(PolicyTarget.REJECT,
                "emitter " + emitter.name() + " lacks " + channel.sendPolicy() + " on " + path,
                0, recipient, emitter);
        }
        if (!allowed(recipient, channel.receivePolicy())) {
            return new RouteDecision(PolicyTarget.DROP,
                "receiver " + recipient.name() + " lacks " + channel.receivePolicy() + " on " + path,
                0, recipient, emitter);
        }

        Rule rule = matchingRule(path, emitter.name(), recipient.name(), message.direction());
        if (rule != null) {
            return apply(rule, channel, message, recipient, emitter);
        }

        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            if (!rateLimit.tryAcquire(key)) {
                return new RouteDecision(PolicyTarget.RATE_LIMIT,
                    "budget exhausted on " + path + " (" + channel.rateLimitPerSecond() + "/s)",
                    rateLimit.nanosUntilNextWindow() / 1_000_000L, recipient, emitter);
            }
        }
        return new RouteDecision(PolicyTarget.LOG, "default-accept on " + path,
            0, recipient, emitter);
    }

    @Override
    public void setRules(Collection<Rule> newRules) {
        List<Rule> ordered = new ArrayList<>(newRules);
        ordered.sort(Comparator.comparingInt(Rule::priority).thenComparing(Rule::id));
        rules.set(List.copyOf(ordered));
    }

    @Override
    public Collection<Rule> rules() {
        return rules.get();
    }

    private boolean allowed(Actor actor, String permission) {
        return permission == null || permissions.has(actor, permission);
    }

    private Rule matchingRule(String path, String emitter, String receiver,
                              me.majhrs16.suite.api.message.Direction direction) {
        for (Rule rule : rules.get()) {
            if (rule.matches(path, emitter, receiver, direction)) {
                return rule;
            }
        }
        return null;
    }

    private RouteDecision apply(Rule rule, Channel channel, Message message,
                                Actor recipient, Actor emitter) {
        switch (rule.target()) {
            case DROP:
                return new RouteDecision(PolicyTarget.DROP, rule.reason(), 0, recipient, emitter);
            case REJECT:
                return new RouteDecision(PolicyTarget.REJECT, rule.reason(), 0, recipient, emitter);
            case REDIRECT:
                return new RouteDecision(PolicyTarget.REDIRECT, rule.reason(), 0, recipient, emitter);
            case RATE_LIMIT:
                if (channel.rateLimitPerSecond() > 0) {
                    String key = message.channel() + "\u0000" + emitter.uuid();
                    if (!rateLimit.tryAcquire(key)) {
                        return new RouteDecision(PolicyTarget.RATE_LIMIT,
                            rule.reason() + " — budget exhausted",
                            rateLimit.nanosUntilNextWindow() / 1_000_000L, recipient, emitter);
                    }
                }
                return new RouteDecision(PolicyTarget.LOG, rule.reason(), 0, recipient, emitter);
            case LOG:
            default:
                return new RouteDecision(PolicyTarget.LOG, rule.reason(), 0, recipient, emitter);
        }
    }
}