package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Message;

/**
 * Neutral entry point for injecting a message into the suite pipeline.
 *
 * <p>The runtime publishes this port and binds it to its pipeline engine.
 * Extensions and packages use it instead of depending on a host type.</p>
 */
public interface MessageDispatch {

    /** Routes and delivers {@code message}; the implementation decides threading. */
    void dispatch(Message message);

    /**
     * Routes and delivers {@code message}, returning the observed status and
     * counters.
     *
     * <p>Implementations that cannot report must say so explicitly instead of
     * returning a fabricated success.</p>
     */
    DispatchOutcome dispatchAndReport(Message message);
}
