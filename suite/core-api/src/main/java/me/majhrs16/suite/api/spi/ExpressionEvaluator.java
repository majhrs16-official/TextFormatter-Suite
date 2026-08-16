package me.majhrs16.suite.api.spi;

import java.util.Map;

/**
 * Evaluates inline scripting expressions inside a template.
 *
 * <p>Templates may contain {@code <expr>...</expr>} tags whose content is an
 * expression evaluated against a binding map (player, content, languages, ...).
 * The result is embedded as escaped literal text.</p>
 */
public interface ExpressionEvaluator {

    /**
     * Evaluates an expression.
     *
     * @param expression raw expression text.
     * @param bindings   named values exposed as {@code #name} in the
     *                   expression language.
     * @return the result serialized to a string; never null.
     * @throws ExpressionEvaluationException when the expression cannot be
     *                                       evaluated.
     */
    String evaluate(String expression, Map<String, Object> bindings)
        throws ExpressionEvaluationException;

    /**
     * Evaluates an expression and returns its live object result instead of a
     * stringification.
     *
     * @return the raw evaluation result, possibly {@code null}.
     */
    Object evaluateObject(String expression, Map<String, Object> bindings)
        throws ExpressionEvaluationException;
}