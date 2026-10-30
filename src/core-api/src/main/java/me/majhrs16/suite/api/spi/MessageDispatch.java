package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Message;

/**
 * Neutral entry point for injecting a message into the suite pipeline.
 *
 * <p>The host adapter binds this port to its dispatcher. Extensions and
 * packages use it instead of depending on the host's concrete
 * {@code MessageDispatcher}.</p>
 */
@FunctionalInterface
public interface MessageDispatch {

    /** Routes and delivers {@code message}; the implementation decides threading. */
    void dispatch(Message message);

    /**
     * Routes and delivers {@code message}, returning the observed counters.
     *
     * <p>The default keeps the port functional for simple adapters; hosts that
     * can report delivery override it.</p>
     */
    default DispatchOutcome dispatchAndReport(Message message) {
        dispatch(message);
        return DispatchOutcome.none();
    }
}
