package me.majhrs16.suite.api.spi;

/**
 * Thrown when an inline scripting expression cannot be evaluated.
 */
public class ExpressionEvaluationException extends RuntimeException {

    public ExpressionEvaluationException(String message) {
        super(message);
    }

    public ExpressionEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}