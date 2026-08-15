package me.majhrs16.cht.core.scripting;

/**
 * Signals a failed expression evaluation. Tie-breaking: the engine logs it and
 * keeps the raw tag literal so misconfigurations stay visible.
 */
public class ExpressionEvaluationException extends RuntimeException {

    public ExpressionEvaluationException(String expression, Throwable cause) {
        super("Cannot evaluate expression: " + expression, cause);
    }
}