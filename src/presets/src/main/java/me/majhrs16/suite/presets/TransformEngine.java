package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.TransformOp;
import me.majhrs16.suite.iflow.rule.ScriptSurface;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.expression.AccessException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Transform Engine - Executes real SpEL-based transformations on messages.
 * <p>
 * This engine evaluates SpEL expressions to transform messages before delivery.
 * It provides a sandboxed evaluation context with access to message fields,
 * actor information, and helper functions.
 * </p>
 */
public final class TransformEngine {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    private final TranslationService translation;
    private final PlaceholderResolver placeholders;
    private final ChannelRegistry channels;
    private final BiFunction<Actor, String, Boolean> permissionChecker;

    public TransformEngine(TranslationService translation, PlaceholderResolver placeholders,
                           ChannelRegistry channels, BiFunction<Actor, String, Boolean> permissionChecker) {
        this.translation = translation;
        this.placeholders = placeholders;
        this.channels = channels;
        this.permissionChecker = permissionChecker;
    }

    /**
     * Applies a transform expression to a message.
     * The expression can modify the message text, language settings, etc.
     */
    public Message applyTransform(String expression, Message message, Actor sender,
                                  Actor recipient, Function<Actor, Boolean> permissionChecker) {
        try {
            Expression exp = getOrCompile(expression);
            Map<String, Object> bindings = createBindings(message, null, null);
            Object result = exp.getValue(createContext(message, null, null), bindings);

            // If result is a String, update message text (Message is immutable, return new instance)
            if (result instanceof String str) {
                message = message.withText(str);
            }
        } catch (Exception e) {
            // Log error but don't crash - transform failures shouldn't break delivery
        }
        return message;
    }

    /**
     * Applies a list of transform operations to a message.
     */
    public Message applyTransforms(List<TransformOp> transforms, Message message,
                                   Actor sender, Actor recipient) {
        ScriptSurface surface = new ScriptSurface(
            message, sender, recipient, channels, placeholders, translation, permissionChecker
        );

        for (TransformOp op : transforms) {
            op.apply(surface);
            message = surface.msg();
        }
        return message;
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
        return SimpleEvaluationContext.forPropertyAccessors(new SafePropertyAccessor()).build();
    }

    /**
     * Property accessor that only allows safe read access.
     */
    private static class SafePropertyAccessor implements PropertyAccessor {

        private static final Set<String> BLOCKED = Set.of(
            "class", "getClass", "T", "new", "constructor", "classLoader"
        );

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class[0];
        }

        @Override
        public boolean canRead(org.springframework.expression.EvaluationContext context,
                               Object target, String name) throws AccessException {
            return !BLOCKED.contains(name) && !name.startsWith("class") && !name.startsWith("getClass");
        }

        @Override
        public TypedValue read(org.springframework.expression.EvaluationContext context,
                               Object target, String name) throws AccessException {
            if (target instanceof Map) {
                Object value = ((Map<?, ?>) target).get(name);
                return new TypedValue(value);
            }
            if (target instanceof me.majhrs16.suite.api.message.Message) {
                var msg = (me.majhrs16.suite.api.message.Message) target;
                Object value = switch (name) {
                    case "text" -> msg.text();
                    case "channel" -> msg.channel();
                    case "type" -> msg.type().name();
                    case "langSource" -> msg.langSource() != null ? msg.langSource().code() : "";
                    case "langTarget" -> msg.langTarget() != null ? msg.langTarget().code() : "";
                    case "sender" -> msg.sender();
                    case "cancelled" -> msg.isCancelled();
                    default -> null;
                };
                return new TypedValue(value);
            }
            // Default bean property access
            try {
                var pd = org.springframework.beans.BeanUtils.getPropertyDescriptor(target.getClass(), name);
                if (pd != null && pd.getReadMethod() != null) {
                    Object value = pd.getReadMethod().invoke(target);
                    return new TypedValue(value);
                }
            } catch (Exception ignored) {
            }
            return TypedValue.NULL;
        }

        @Override
        public boolean canWrite(org.springframework.expression.EvaluationContext context,
                                Object target, String name) throws AccessException {
            return false;
        }

        @Override
        public void write(org.springframework.expression.EvaluationContext context,
                          Object target, String name, Object newValue) throws AccessException {
            throw new AccessException("Read-only evaluation context");
        }
    }
}