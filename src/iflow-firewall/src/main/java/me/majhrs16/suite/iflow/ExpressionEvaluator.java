package me.majhrs16.suite.iflow;

import java.util.Map;

/**
 * Evaluates legacy iFlow rule expressions (conditions and actions).
 *
 * <p>This SPI belongs to {@code iflow-firewall}; it is not used by templates,
 * by {@code iflow-vm} or by {@code iflow-wasm}. The host no longer exposes
 * {@code <expr>} tags.</p>
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