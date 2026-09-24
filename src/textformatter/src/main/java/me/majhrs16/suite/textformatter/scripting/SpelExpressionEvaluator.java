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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private static final int MAX_CACHE_SIZE = 1024;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final PluginLogger logger;

    // Bounded LRU cache for compiled expressions
    private final ConcurrentMap<String, Expression> expressionCache = new LruExpressionCache(MAX_CACHE_SIZE);

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

    /**
     * Thread-safe bounded LRU cache for compiled SpEL expressions.
     * Uses a synchronized LinkedHashMap with access-order for LRU eviction.
     */
    private static final class LruExpressionCache implements ConcurrentMap<String, Expression> {

        private final int maxSize;
        private final LinkedHashMap<String, Expression> map;

        LruExpressionCache(int maxSize) {
            this.maxSize = maxSize;
            this.map = new LinkedHashMap<>(16, 0.75f, true);
        }

        @Override
        public int size() {
            synchronized (map) {
                return map.size();
            }
        }

        @Override
        public boolean isEmpty() {
            synchronized (map) {
                return map.isEmpty();
            }
        }

        @Override
        public boolean containsKey(Object key) {
            synchronized (map) {
                return map.containsKey(key);
            }
        }

        @Override
        public boolean containsValue(Object value) {
            synchronized (map) {
                return map.containsValue(value);
            }
        }

        @Override
        public Expression get(Object key) {
            synchronized (map) {
                return map.get(key);
            }
        }

        @Override
        public Expression put(String key, Expression value) {
            synchronized (map) {
                Expression old = map.put(key, value);
                if (map.size() > maxSize) {
                    map.remove(map.entrySet().iterator().next().getKey());
                }
                return old;
            }
        }

        @Override
        public Expression remove(Object key) {
            synchronized (map) {
                return map.remove(key);
            }
        }

        @Override
        public void putAll(Map<? extends String, ? extends Expression> m) {
            synchronized (map) {
                map.putAll(m);
                while (map.size() > maxSize) {
                    map.remove(map.entrySet().iterator().next().getKey());
                }
            }
        }

        @Override
        public void clear() {
            synchronized (map) {
                map.clear();
            }
        }

        @Override
        public java.util.Set<String> keySet() {
            synchronized (map) {
                return new java.util.HashSet<>(map.keySet());
            }
        }

        @Override
        public java.util.Collection<Expression> values() {
            synchronized (map) {
                return new java.util.ArrayList<>(map.values());
            }
        }

        @Override
        public java.util.Set<java.util.Map.Entry<String, Expression>> entrySet() {
            synchronized (map) {
                return new java.util.HashSet<>(map.entrySet());
            }
        }

        @Override
        public Expression putIfAbsent(String key, Expression value) {
            synchronized (map) {
                Expression existing = map.get(key);
                if (existing != null) {
                    return existing;
                }
                return put(key, value);
            }
        }

        @Override
        public boolean remove(Object key, Object value) {
            synchronized (map) {
                Expression existing = map.get(key);
                if (existing != null && existing.equals(value)) {
                    map.remove(key);
                    return true;
                }
                return false;
            }
        }

        @Override
        public Expression replace(String key, Expression value) {
            synchronized (map) {
                if (!map.containsKey(key)) {
                    return null;
                }
                return put(key, value);
            }
        }

        @Override
        public boolean replace(String key, Expression oldValue, Expression newValue) {
            synchronized (map) {
                Expression existing = map.get(key);
                if (existing != null && existing.equals(oldValue)) {
                    put(key, newValue);
                    return true;
                }
                return false;
            }
        }

        @Override
        public Expression computeIfAbsent(String key, java.util.function.Function<? super String, ? extends Expression> mappingFunction) {
            synchronized (map) {
                Expression existing = map.get(key);
                if (existing != null) {
                    return existing;
                }
                Expression computed = mappingFunction.apply(key);
                if (computed != null) {
                    return put(key, computed);
                }
                return null;
            }
        }

        @Override
        public Expression computeIfPresent(String key, java.util.function.BiFunction<? super String, ? super Expression, ? extends Expression> remappingFunction) {
            synchronized (map) {
                Expression existing = map.get(key);
                if (existing == null) {
                    return null;
                }
                Expression computed = remappingFunction.apply(key, existing);
                if (computed != null) {
                    return put(key, computed);
                } else {
                    remove(key);
                    return null;
                }
            }
        }

        @Override
        public Expression compute(String key, java.util.function.BiFunction<? super String, ? super Expression, ? extends Expression> remappingFunction) {
            synchronized (map) {
                Expression existing = map.get(key);
                Expression computed = remappingFunction.apply(key, existing);
                if (computed != null) {
                    return put(key, computed);
                } else if (existing != null) {
                    remove(key);
                    return null;
                }
                return null;
            }
        }

        @Override
        public Expression merge(String key, Expression value, java.util.function.BiFunction<? super Expression, ? super Expression, ? extends Expression> remappingFunction) {
            synchronized (map) {
                Expression existing = map.get(key);
                Expression computed = (existing == null) ? value : remappingFunction.apply(existing, value);
                if (computed != null) {
                    return put(key, computed);
                } else {
                    remove(key);
                    return null;
                }
            }
        }
    }
}