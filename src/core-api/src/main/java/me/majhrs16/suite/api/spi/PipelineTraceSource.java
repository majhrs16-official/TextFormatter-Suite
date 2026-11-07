package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.pipeline.PipelineTrace;

import java.util.List;

/**
 * Neutral read port for the runtime's bounded trace buffer.
 *
 * <p>The runtime records the trace of every dispatch against the message id.
 * Diagnostic consumers read it instead of reaching into the engine.</p>
 */
public interface PipelineTraceSource {

    /**
     * @param messageId {@link me.majhrs16.suite.api.message.Message#id()}
     *                  rendered as text
     * @return the last trace recorded for that message, or {@code null} when it
     *         is not in the buffer
     */
    PipelineTrace lastTrace(String messageId);

    /** @return the buffered traces, oldest first */
    List<PipelineTrace> recentTraces();
}
