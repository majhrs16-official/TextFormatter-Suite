package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.spi.ExpressionEvaluator;
import me.majhrs16.suite.api.spi.ExpressionEvaluationException;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeLocator;
import org.springframework.expression.spel.support.ReflectiveMethodResolver;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SpEL-based {@link ExpressionEvaluator} for rule conditions and actions.
 * <p>
 * Uses a {@link StandardEvaluationContext} with a restricted {@link StandardTypeLocator}
 * that blocks type references (T()) and a custom {@link MethodResolver} that allows
 * getter methods on registered domain objects.
 * </p>
 */
public final class RuleExpressionEvaluator implements ExpressionEvaluator {

    private static final int MAX_CACHE_SIZE = 1024;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final PlaceholderResolver placeholders;
    private final TranslationService translation;
    private final PluginLogger logger;

    // Bounded LRU cache for compiled expressions
    private final ConcurrentMap<String, Expression> expressionCache = new LruExpressionCache(MAX_CACHE_SIZE);

    public RuleExpressionEvaluator(PlaceholderResolver placeholders,
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
     * Creates an evaluation context with the provided bindings.
     * Uses StandardEvaluationContext with a TypeLocator that blocks type references
     * and a custom MethodResolver that allows getter methods on domain objects.
     */
    private EvaluationContext createContext(Map<String, Object> bindings) {
        Map<String, Object> safeBindings = new HashMap<>(bindings);

        // Add helper functions as read-only values
        safeBindings.putIfAbsent("hasPermission", false);
        safeBindings.putIfAbsent("papi", "");
        safeBindings.putIfAbsent("translate", "");

        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setRootObject(safeBindings);
        
        // Also register bindings as variables for #variable syntax
        for (Map.Entry<String, Object> entry : safeBindings.entrySet()) {
            context.setVariable(entry.getKey(), entry.getValue());
        }
        
        // Block type references (T())
        context.setTypeLocator(new StandardTypeLocator() {
            @Override
            public Class<?> findType(String typeName) throws org.springframework.expression.EvaluationException {
                throw new org.springframework.expression.EvaluationException("Type references are not allowed: " + typeName);
            }
        });
        
        // Add custom MethodResolver that allows getters on domain objects
        context.setMethodResolvers(List.of(new DomainMethodResolver()));
        
        return context;
    }

    /**
     * Custom MethodResolver that allows property accessor methods on explicitly
     * allowed domain classes only. Uses a strict ALLOWLIST approach.
     * <p>
     * Allows zero-argument methods (property getters) that return non-void values
     * on explicitly whitelisted domain classes. Blocks all other method invocations
     * including static methods, constructors, methods with args, etc.
     * </p>
     */
    private static final class DomainMethodResolver implements MethodResolver {
        private final ReflectiveMethodResolver delegate = new ReflectiveMethodResolver();

        // Explicit allowlist of allowed class prefixes
        private static final String[] ALLOWED_PREFIXES = {
            "me.majhrs16.suite.api.message.",
            "me.majhrs16.suite.textformatter.channel.",
            "me.majhrs16.suite.iflow."
        };

        // Explicit blocklist for system/internal classes (defense in depth)
        private static final String[] BLOCKED_PREFIXES = {
            "java.", "javax.", "sun.", "com.sun.",
            "org.springframework.", "org.yaml.",
            "org.apache.", "org.codehaus.", "org.json."
        };

        @Override
        public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name, List<TypeDescriptor> argumentTypes) throws AccessException {
            // Only allow zero-argument methods (property accessors)
            if (argumentTypes != null && !argumentTypes.isEmpty()) {
                throw new AccessException("Method arguments not allowed: " + name);
            }

            // Check if target object class is allowed
            if (targetObject != null && !isAllowedClass(targetObject.getClass())) {
                throw new AccessException("Access to class not allowed: " + targetObject.getClass().getName());
            }

            // Delegate to standard resolver for actual method lookup
            return delegate.resolve(context, targetObject, name, argumentTypes);
        }

        private boolean isAllowedClass(Class<?> clazz) {
            String name = clazz.getName();

            // First check blocklist (defense in depth)
            for (String blocked : BLOCKED_PREFIXES) {
                if (name.startsWith(blocked)) {
                    return false;
                }
            }

            // Then check allowlist - must match at least one allowed prefix
            for (String allowed : ALLOWED_PREFIXES) {
                if (name.startsWith(allowed)) {
                    return true;
                }
            }

            // Default deny - class not in allowlist
            return false;
        }
    }

    /**
     * Thread-safe bounded LRU cache for compiled SpEL expressions.
     */
    private static final class LruExpressionCache implements ConcurrentMap<String, Expression> {

        private final int maxSize;
        private final java.util.LinkedHashMap<String, Expression> map;

        LruExpressionCache(int maxSize) {
            this.maxSize = maxSize;
            this.map = new java.util.LinkedHashMap<>(16, 0.75f, true);
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
                    put(key, computed);
                    return computed;
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