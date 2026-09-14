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
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL-based {@link ExpressionEvaluator} with sandboxed evaluation context.
 * <p>
 * The evaluation context uses {@link SimpleEvaluationContext#forReadOnlyDataBinding()}
 * which provides:
 * <ul>
 *   <li>Read-only access to properties (no writes)</li>
 *   <li>No type references (no T(), no new, no static field access)</li>
 *   <li>No method invocation except property getters</li>
 * </ul>
 * </p>
 * <p>
 * Custom property accessor restrictions are applied via {@link SafePropertyAccessor}
 * when using the standard evaluation context, but for read-only data binding
 * the Spring framework already provides strong sandboxing.
 * </p>
 */
public final class SpelExpressionEvaluator implements ExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final PluginLogger logger;

    // Cache compiled expressions
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    public SpelExpressionEvaluator(PlaceholderResolver placeholders,
                                    TranslationService translation,
                                    PluginLogger logger) {
        this.placeholders = placeholders;
        this.translation = translation;
        this.logger = logger;
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

    /**
     * Creates a new evaluation context with the provided bindings.
     * Each evaluation gets a fresh context for isolation.
     * Uses Spring's built-in read-only data binding context for sandboxing.
     */
    private EvaluationContext createContext(Map<String, Object> bindings) {
        Map<String, Object> safeBindings = new HashMap<>(bindings);

        // Add helper functions as read-only values
        safeBindings.putIfAbsent("hasPermission", false);
        safeBindings.putIfAbsent("papi", "");
        safeBindings.putIfAbsent("translate", "");

        return SimpleEvaluationContext
            .forReadOnlyDataBinding()
            .withRootObject(safeBindings)
            .build();
    }
}