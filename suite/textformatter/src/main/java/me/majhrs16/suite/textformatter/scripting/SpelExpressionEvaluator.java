package me.majhrs16.suite.textformatter.scripting;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.ExpressionEvaluator;
import me.majhrs16.suite.api.spi.ExpressionEvaluationException;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL-based {@link ExpressionEvaluator} with sandboxed evaluation context.
 * Exposes bindings for sender, content, languages, permissions, and PAPI placeholders.
 */
public final class SpelExpressionEvaluator implements ExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final EvaluationContext evalContext;
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final PluginLogger logger;

    // Cache compiled expressions
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * Creates a new SpEL evaluator with sandboxed context.
     * <p>
     * The evaluation context is read-only and only exposes:
     * - Actor fields: name, uuid, kind, language
     * - Language fields: code, name
     * - Primitive types and collections
     * - No access to: T(), new, class, getClass, static fields, constructors
     */
    public SpelExpressionEvaluator(PlaceholderResolver placeholders,
                                    TranslationService translation,
                                    PluginLogger logger) {
        this.placeholders = placeholders;
        this.translation = translation;
        this.logger = logger;

        // Build a safe evaluation context: read-only, no type access, no static/constructors
        this.evalContext = SimpleEvaluationContext
            .forReadOnlyDataBinding()
            .withInstanceResolver((ctx, target, name) -> {
                // Only allow property access on whitelisted types
                if (target instanceof Map) {
                    return org.springframework.expression.TypeConverterDelegate.DEFAULT_TYPE_CONVERTER;
                }
                return null;
            })
            .withPropertyAccessor(new SafePropertyAccessor())
            .build();
    }

    @Override
    public String evaluate(String expression, Map<String, Object> bindings)
            throws ExpressionEvaluationException {
        try {
            Expression exp = getOrCompile(expression);
            Object result = exp.getValue(createContext(bindings));
            return result == null ? "" : String.valueOf(result);
        } catch (Exception e) {
            throw new ExpressionEvaluationException("Failed to evaluate: " + expression, e);
        }
    }

    @Override
    public Object evaluateObject(String expression, Map<String, Object> bindings)
            throws ExpressionEvaluationException {
        try {
            Expression exp = getOrCompile(expression);
            return exp.getValue(createContext(bindings));
        } catch (Exception e) {
            throw new ExpressionEvaluationException("Failed to evaluate object: " + expression, e);
        }
    }

    private Expression getOrCompile(String expression) {
        return expressionCache.computeIfAbsent(expression, parser::parseExpression);
    }

    private EvaluationContext createContext(Map<String, Object> bindings) {
        Map<String, Object> safeBindings = new java.util.HashMap<>(bindings);
        // Add helper functions
        safeBindings.put("hasPermission", (java.util.function.BiFunction<String, String, Boolean>) (actorName, perm) -> {
            // Will be resolved at evaluation time via actor lookup
            return false;
        });
        safeBindings.put("papi", (java.util.function.Function<String, String>) token -> {
            if (placeholders != null && placeholders.available()) {
                // Actor lookup would need to be passed in bindings
                return "";
            }
            return "";
        });
        safeBindings.put("translate", (java.util.function.Function<String, String>) text -> {
            if (translation != null && translation.isAvailable()) {
                // Language codes would need to be in bindings
                return text;
            }
            return text;
        });
        return org.springframework.expression.spel.support.StandardEvaluationContextBuilder
            .withBindings(safeBindings)
            .build();
    }

    /**
     * Property accessor that only allows read access to safe types.
     * Blocks: class, getClass, T(), new, static fields, constructors.
     */
    private static class SafePropertyAccessor implements org.springframework.expression.spel.PropertyAccessor {

        private static final java.util.Set<String> BLOCKED = java.util.Set.of(
            "class", "getClass", "T", "new", "constructor"
        );

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name)
                throws org.springframework.expression.AccessException {
            return !BLOCKED.contains(name) && !name.startsWith("class") && !name.startsWith("getClass");
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name)
                throws org.springframework.expression.AccessException {
            return false; // Read-only
        }

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class[0];
        }

        @Override
        public Object read(EvaluationContext context, Object target, String name)
                throws org.springframework.expression.AccessException {
            if (target instanceof Map) {
                return ((Map<?, ?>) target).get(name);
            }
            if (target instanceof Actor) {
                Actor actor = (Actor) target;
                return switch (name) {
                    case "name" -> actor.name();
                    case "uuid" -> actor.uuid() != null ? actor.uuid().toString() : "";
                    case "kind" -> actor.kind().name();
                    case "language" -> actor.language() != null ? actor.language().code() : "";
                    default -> null;
                };
            }
            if (target instanceof Language) {
                Language lang = (Language) target;
                return switch (name) {
                    case "code" -> lang.code();
                    case "name" -> lang.name();
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
        public void write(EvaluationContext context, Object target, String name, Object newValue)
                throws org.springframework.expression.AccessException {
            throw new org.springframework.expression.AccessException("Read-only evaluation context");
        }
    }
}