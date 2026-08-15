package me.majhrs16.cht.core.scripting;

import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.Map;

/**
 * {@link ExpressionEvaluator} backed by Spring SpEL (the same engine the old
 * CoreTranslator used).
 *
 * <p>Bindings are exposed as SpEL variables referenced by {@code #name}. Any
 * result type is converted to string.</p>
 */
public final class SpelExpressionEvaluator implements ExpressionEvaluator {

    private final ExpressionParser parser = new SpelExpressionParser();

    @Override
    public String evaluate(String expression, Map<String, Object> bindings) {
        Object result = evaluateObject(expression, bindings);
        return result == null ? "" : String.valueOf(result);
    }

    @Override
    public Object evaluateObject(String expression, Map<String, Object> bindings) {
        try {
            Expression parsed = parser.parseExpression(expression);
            StandardEvaluationContext context = new StandardEvaluationContext();
            bindings.forEach(context::setVariable);
            Object root = bindings.get(ROOT_VAR);
            return parsed.getValue(context, root);
        } catch (ParseException | EvaluationException e) {
            throw new ExpressionEvaluationException(expression, e);
        }
    }

    /** Binding key treated as the SpEL root object (methods callable bare). */
    public static final String ROOT_VAR = "msg";
}