package me.majhrs16.suite.synccommon;

import java.util.Map;

/**
 * Platform event bound to the message currently being processed.
 *
 * <p>A local event action never receives an arbitrary event object: the
 * platform listener binds the event around the dispatch call and the sink
 * operates on that binding. This keeps the action scoped to the message that
 * originated it.</p>
 */
public interface LocalEvent {

    /** @return neutral description of the bound event */
    Map<String, Object> info();

    /**
     * Cancels the bound event.
     *
     * @throws LocalActionException with kind {@code UNSUPPORTED} when the
     *         platform event cannot be cancelled
     */
    void cancel();
}
