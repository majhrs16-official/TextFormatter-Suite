package me.majhrs16.cht.core.rules;

import me.majhrs16.cht.core.message.Message;
import me.majhrs16.cht.core.message.MessageType;
import me.majhrs16.cht.core.platform.PluginLogger;
import me.majhrs16.cht.core.scripting.ExpressionEvaluator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Executes the native rules pipeline over a batch of {@link Message messages}.
 *
 * <p>A rule guards an event (or a set of events) with a list of conditions;
 * when every condition holds, its actions run against a {@link ScriptSurface}
 * wrapping each message. This is the native replacement for ConditionalEvents:
 * no external plugin needed, the same SpEL engine is used everywhere, and the
 * API is exactly the atomic surface exposed to commands and placeholders.</p>
 *
 * <p>Rules are ordered and each one is applied to every message in the batch.
 * Actions may cancel, reformat (via {@code setFormat}), re-route (via {@code
 * setDirection}) or clone messages; the accepted messages flow to the next
 * stage. Messages are never shared: every rule works on a per-message surface,
 * so a cancellation or mutation can never leak to another recipient.</p>
 */
public final class RulesEngine {

    private final List<Rule> rules;
    private final ExpressionEvaluator expressions;
    private final PluginLogger logger;

    public RulesEngine(List<Rule> rules, ExpressionEvaluator expressions, PluginLogger logger) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Applies every matching rule to a message.
     *
     * @param message the incoming message (never modified in place).
     * @return the messages that survived: {@code 0} when cancelled, a single
     *         (possibly mutated) message, or several when actions cloned it.
     */
    public List<Message> apply(Message message) {
        List<Message> batch = new ArrayList<>();
        batch.add(message);

        for (Rule rule : rules) {
            if (!rule.matches(message.type())) {
                continue;
            }
            List<Message> next = new ArrayList<>();
            for (Message current : batch) {
                try {
                    next.addAll(applyRule(rule, current));
                } catch (RuntimeException e) {
                    logger.warn("Rule '%s' failed for message %s: %s",
                        rule.name(), current.id(), e.getMessage());
                    next.add(current);
                }
            }
            batch = next;
            if (batch.isEmpty()) {
                return batch;
            }
        }
        return batch;
    }

    private List<Message> applyRule(Rule rule, Message message) {
        if (rule.conditions().isEmpty()) {
            return executeActions(rule, message);
        }
        for (String condition : rule.conditions()) {
            Object result = evaluateCondition(condition, message);
            if (!truthy(result)) {
                return Collections.singletonList(message);
            }
        }
        return executeActions(rule, message);
    }

    private List<Message> executeActions(Rule rule, Message message) {
        ScriptSurface surface = new ScriptSurface(message);
        for (String action : rule.actions()) {
            Object result = evaluate(action, surface);
            if (result instanceof ScriptSurface) {
                surface = (ScriptSurface) result;
            }
        }
        Message mutated = surface.message();
        if (mutated.isCancelled()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(mutated);
    }

    private Object evaluateCondition(String expression, Message message) {
        return evaluate(expression, new ScriptSurface(message));
    }

    private Object evaluate(String expression, Object root) {
        java.util.Map<String, Object> bindings = new java.util.HashMap<>();
        bindings.put("msg", root);
        return expressions.evaluateObject(expression, bindings);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        return !text.isEmpty() && !text.equals("false") && !text.equals("0")
            && !text.equals("null");
    }

    public List<Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** A single ordered rule: matching events + conditions + actions. */
    public static final class Rule {

        private final String name;
        private final List<MessageType> types;
        private final List<String> conditions;
        private final List<String> actions;

        public Rule(String name, List<MessageType> types, List<String> conditions,
                List<String> actions) {
            this.name = name;
            this.types = Collections.unmodifiableList(new ArrayList<>(types));
            this.conditions = Collections.unmodifiableList(new ArrayList<>(conditions));
            this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        }

        public String name() {
            return name;
        }

        public boolean matches(MessageType type) {
            return types.isEmpty() || types.contains(type);
        }

        public List<MessageType> types() {
            return types;
        }

        public List<String> conditions() {
            return conditions;
        }

        public List<String> actions() {
            return actions;
        }
    }
}