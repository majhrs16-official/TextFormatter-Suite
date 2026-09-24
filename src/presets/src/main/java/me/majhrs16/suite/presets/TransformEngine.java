package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.ScriptSurface;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Transform Engine - Executes real SpEL-based transformations on messages.
 * <p>
 * This engine evaluates SpEL expressions to transform messages before delivery.
 * It provides a sandboxed evaluation context with access to message fields,
 * actor information, and helper functions.
 */
public final class TransformEngine {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    private final TranslationService translation;
    private final PlaceholderResolver placeholders;

    public TransformEngine(TranslationService translation, PlaceholderResolver placeholders) {
        this.translation = translation;
        this.placeholders = placeholders;
    }

    /**
     * Applies a transform expression to a message.
     * The expression can modify the message text, language settings, etc.
     */
    public void applyTransform(String expression, Message message, Actor sender,
                               Actor recipient, Function<Actor, Boolean> permissionChecker) {
        try {
            Expression exp = getOrCompile(expression);
            Map<String, Object> bindings = createBindings(message, null, null);
            Object result = exp.getValue(createContext(message, null, null), bindings);

            // If result is a String, update message text
            if (result instanceof String str) {
                message.setText(str);
            }
        } catch (Exception e) {
            // Log error but don't crash - transform failures shouldn't break delivery
        }
    }

    /**
     * Applies a list of transform operations to a message.
     */
    public void applyTransforms(List<TransformOp> transforms, Message message,
                                 Actor sender, Actor recipient) {
        ScriptSurface surface = new ScriptSurface(
            message, null, null, null, null, null, null
        );

        for (TransformOp op : transforms) {
            op.apply(new ScriptSurface(message, null, null, null, null, null, null));
        }
    }

    /**
     * Evaluates a condition expression against a message.
     */
    public boolean evaluateCondition(String expression, Message message,
                                      Actor sender, Actor recipient,
                                      Function<Actor, Boolean> permissionChecker) {
        try {
            Expression exp = getOrCompile(expression);
            Map<String, Object> bindings = createBindings(message, null, null);
            Object result = exp.getValue(createContext(message, null, null), bindings);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private Expression getOrCompile(String expression) {
        return expressionCache.computeIfAbsent(expression, parser::parseExpression);
    }

    private Map<String, Object> createBindings(Message message, Actor sender, Actor recipient) {
        Map<String, Object> bindings = new ConcurrentHashMap<>();
        bindings.put("msg", message);
        bindings.put("sender", sender);
        bindings.put("recipient", recipient);
        bindings.put("content", message.text());
        bindings.put("channel", message.channel());
        bindings.put("type", message.type());
        bindings.put("langSource", message.langSource() != null ? message.langSource().code() : "");
        bindings.put("langTarget", message.langTarget() != null ? message.langTarget().code() : "");
        return bindings;
    }

    private SimpleEvaluationContext createContext(Message message, Actor sender, Actor recipient) {
        return SimpleEvaluationContext
            .forReadOnlyDataBinding()
            .withPropertyAccessor(new SafePropertyAccessor())
            .build();
    }

    /**
     * Property accessor that only allows safe read access.
     */
    private static class SafePropertyAccessor extends org.springframework.expression.spel.PropertyAccessor {

        private static final Set<String> BLOCKED = Set.of(
            "class", "getClass", "T", "new", "constructor", "classLoader"
        );

        @Override
        public boolean canRead(org.springframework.expression.EvaluationContext context,
                               Object target, String name) throws org.springframework.expression.AccessException {
            return !BLOCKED.contains(name) && !name.startsWith("class") && !name.startsWith("getClass");
        }

        @Override
        public boolean canWrite(org.springframework.expression.EvaluationContext context,
                                Object target, String name) throws org.springframework.expression.AccessException {
            return false;
        }

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class[0];
        }

        @Override
        public Object read(org.springframework.expression.EvaluationContext context,
                           Object target, String name) throws org.springframework.expression.AccessException {
            if (target instanceof Map) {
                return ((Map<?, ?>) target).get(name);
            }
            if (target instanceof me.majhrs16.suite.api.message.Message) {
                var msg = (me.majhrs16.suite.api.message.Message) target;
                return switch (name) {
                    case "text" -> msg.text();
                    case "channel" -> msg.channel();
                    case "type" -> msg.type().name();
                    case "langSource" -> msg.langSource() != null ? msg.langSource().code() : "";
                    case "langTarget" -> msg.langTarget() != null ? msg.langTarget().code() : "";
                    case "sender" -> msg.sender();
                    case "cancelled" -> msg.isCancelled();
                    default -> null;
                };
            }
            // Default bean property access
            try {
                return org.springframework.beans.BeanUtils.getPropertyDescriptor(target.getClass(), name)
                    .getReadMethod().invoke(target);
            } catch (Exception ignored) {
                return null;
            }
        }

        @Override
        public void write(org.springframework.expression.EvaluationContext context,
                          Object target, String name, Object newValue)
                throws org.springframework.expression.AccessException {
            throw new org.springframework.expression.AccessException("Read-only evaluation context");
        }
    }
}