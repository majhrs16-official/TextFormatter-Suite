package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ExpressionEvaluator;
import me.majhrs16.suite.api.spi.ExpressionEvaluationException;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.ScriptSurface;
import me.majhrs16.suite.iflow.rule.TransformOp;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final ExpressionEvaluator expressionEvaluator;

    public DefaultRouter(ChannelRegistry channels) {
        this(channels, PermissionChecker.ALLOW_ALL, null);
    }

    public DefaultRouter(ChannelRegistry channels, PermissionChecker permissions) {
        this(channels, permissions, null);
    }

    public DefaultRouter(ChannelRegistry channels, PermissionChecker permissions,
                         ExpressionEvaluator expressionEvaluator) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.rateLimit = new RateLimiter();
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public RouteOutcome route(Message message, Actor recipient) {
        Objects.requireNonNull(recipient, "recipient");
        String path = message.channel() == null ? "chat" : message.channel();
        Channel channel = channels.resolve(path);
        Actor emitter = message.sender();
        boolean selfEcho = recipient.equals(emitter);

        if (!selfEcho && !allowed(emitter, channel.sendPolicy())) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT,
                "emitter " + emitter.name() + " lacks " + channel.sendPolicy() + " on " + path,
                0, recipient, emitter), message);
        }
        if (!allowed(recipient, channel.receivePolicy())) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.DROP,
                "receiver " + recipient.name() + " lacks " + channel.receivePolicy() + " on " + path,
                0, recipient, emitter), message);
        }

        Rule rule = matchingRule(path, emitter, recipient, message);
        if (rule != null) {
            return apply(rule, channel, message, recipient, emitter);
        }

        if (channel.rateLimitPerSecond() > 0) {
            String key = path + "\u0000" + emitter.uuid();
            if (!rateLimit.tryAcquire(key, channel.rateLimitPerSecond())) {
                return RouteOutcome.of(new RouteDecision(PolicyTarget.RATE_LIMIT,
                    "budget exhausted on " + path + " (" + channel.rateLimitPerSecond() + "/s)",
                    rateLimit.nanosUntilNextWindow() / 1_000_000L, recipient, emitter), message);
            }
        }
        return RouteOutcome.of(new RouteDecision(PolicyTarget.LOG, "default-accept on " + path,
            0, recipient, emitter), message);
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

    @Override
    public boolean hasPermission(Actor actor, String permission) {
        return permission == null || permissions.has(actor, permission);
    }

    private Rule matchingRule(String path, Actor emitter, Actor recipient, Message message) {
        for (Rule rule : rules.get()) {
            if (rule.matches(path, emitter.name(), recipient.name(), message.direction())) {
                // Evaluate SpEL condition if present
                if (rule.condition() != null && expressionEvaluator != null) {
                    if (!evaluateCondition(rule, message, emitter, recipient)) {
                        continue;
                    }
                }
                return rule;
            }
        }
        return null;
    }

    private boolean evaluateCondition(Rule rule, Message message, Actor emitter, Actor recipient) {
        try {
            Map<String, Object> bindings = createBindings(message, emitter, recipient);
            Object result = expressionEvaluator.evaluateObject(rule.condition(), bindings);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            // Condition evaluation error -> treat as false (skip rule)
            return false;
        }
    }

    private Map<String, Object> createBindings(Message message, Actor emitter, Actor recipient) {
        Map<String, Object> bindings = new HashMap<>();
        bindings.put("msg", message);
        bindings.put("sender", emitter);
        bindings.put("recipient", recipient);
        bindings.put("emitter", emitter);
        bindings.put("receiver", recipient);
        bindings.put("channel", message.channel());
        bindings.put("direction", message.direction());
        bindings.put("type", message.type());
        bindings.put("content", message.text());
        bindings.put("langSource", message.langSource() != null ? message.langSource().code() : "");
        bindings.put("langTarget", message.langTarget() != null ? message.langTarget().code() : "");
        return bindings;
    }

    private RouteOutcome apply(Rule rule, Channel channel, Message message,
                                Actor recipient, Actor emitter) {
        // Use a working message that gets updated through ScriptSurface transforms
        Message working = message;

        // Execute SpEL action if present
        if (rule.action() != null && expressionEvaluator != null) {
            executeAction(rule, working, emitter, recipient);
        }

        // Check if action cancelled the message
        if (working.isCancelled()) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.DROP, "cancelled by action", 0, recipient, emitter), working);
        }

        // Apply transform operations (F7+)
        if (!rule.transforms().isEmpty()) {
            ScriptSurface surface = new ScriptSurface(working, emitter, recipient,
                channels, null, null, permissions::has);
            for (TransformOp transform : rule.transforms()) {
                transform.apply(surface);
            }
            // Retrieve the transformed message from ScriptSurface
            working = surface.msg();
        }

        // Check if transforms cancelled the message
        if (working.isCancelled()) {
            return RouteOutcome.of(new RouteDecision(PolicyTarget.DROP, "cancelled by transform", 0, recipient, emitter), working);
        }

        // Handle CHANNEL_REDIRECT from transform (setChannel)
        String redirectTarget = working.channel();
        if (rule.redirectChannel() != null) {
            redirectTarget = rule.redirectChannel();
        }

        switch (rule.target()) {
            case DROP:
                return RouteOutcome.of(new RouteDecision(PolicyTarget.DROP, rule.reason(), 0, recipient, emitter), working);
            case REJECT:
                return RouteOutcome.of(new RouteDecision(PolicyTarget.REJECT, rule.reason(), 0, recipient, emitter), working);
            case REDIRECT:
                return RouteOutcome.of(new RouteDecision(PolicyTarget.REDIRECT, rule.reason(), 0, recipient, emitter), working);
            case CHANNEL_REDIRECT:
                String targetChannel = rule.redirectChannel();
                return RouteOutcome.of(new RouteDecision(PolicyTarget.CHANNEL_REDIRECT,
                    rule.reason() + (targetChannel != null ? " → " + targetChannel : ""),
                    0, recipient, emitter, targetChannel), working);
            case RATE_LIMIT:
                if (channel.rateLimitPerSecond() > 0) {
                    String key = working.channel() + "\u0000" + emitter.uuid();
                    if (!rateLimit.tryAcquire(key, channel.rateLimitPerSecond())) {
                        return RouteOutcome.of(new RouteDecision(PolicyTarget.RATE_LIMIT,
                            rule.reason() + " — budget exhausted",
                            rateLimit.nanosUntilNextWindow() / 1_000_000L, recipient, emitter), working);
                    }
                }
                return RouteOutcome.of(new RouteDecision(PolicyTarget.LOG, rule.reason(), 0, recipient, emitter), working);
            case LOG:
            default:
                return RouteOutcome.of(new RouteDecision(PolicyTarget.LOG, rule.reason(), 0, recipient, emitter), working);
        }
    }

    private void executeAction(Rule rule, Message message, Actor emitter, Actor recipient) {
        try {
            Map<String, Object> bindings = createBindings(message, emitter, recipient);
            expressionEvaluator.evaluateObject(rule.action(), bindings);
        } catch (Exception e) {
            // Action evaluation error -> ignore
        }
    }
}