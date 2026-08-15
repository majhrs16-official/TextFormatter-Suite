package me.majhrs16.cht.core.scripting;

import java.util.Map;

/**
 * Port	for evaluating inline scripting expressions inside a template.
 *
 * <p>Templates may contain {@code <expr>...</expr>} tags whose content is an
 * expression evaluated against a binding map (player, content, languages, the
 * public API, ...). The result is embedded as escaped literal text. This is
 * the platform-neutral replacement of the old SpEL-via-PAPI expansion.</p>
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
     * stringification. Used by the rules engine so actions can chain and carry
     * objects ({@code ScriptSurface}).
     *
     * @param expression raw expression text.
     * @param bindings   named values exposed as {@code #name}.
     * @return the raw evaluation result, possibly {@code null}.
     * @throws ExpressionEvaluationException when the expression cannot be
     *                                       evaluated.
     */
    Object evaluateObject(String expression, Map<String, Object> bindings)
        throws ExpressionEvaluationException;
}