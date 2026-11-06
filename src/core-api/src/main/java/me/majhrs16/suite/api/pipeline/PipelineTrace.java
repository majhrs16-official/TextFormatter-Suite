package me.majhrs16.suite.api.pipeline;

import java.util.List;

/**
 * Ordered record of the stages that ran or were skipped in one dispatch.
 *
 * <p>The runtime keeps a bounded ring of recent traces for diagnostics; a
 * trace is never used as a metric store.</p>
 */
public interface PipelineTrace {

    /** Appends one entry. Called by the engine, not by feature stages. */
    void record(StageTrace entry);

    /** @return immutable snapshot of the entries in execution order */
    List<StageTrace> entries();
}
