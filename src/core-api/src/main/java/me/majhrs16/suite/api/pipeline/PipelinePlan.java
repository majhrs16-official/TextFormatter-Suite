package me.majhrs16.suite.api.pipeline;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated, immutable order of stage ids.
 *
 * <p>It is the syntactic half of the plan: it checks the contract rules that
 * do not need the module graph (id syntax, duplicates, {@code sync} terminal).
 * The runtime resolves each id against the installed modules and records
 * unavailable stages as {@code SKIPPED}.</p>
 *
 * <p>An empty list is valid and means "no stages"; the runtime maps it to
 * {@link PipelineStatus#NO_PIPELINE}. Invalid input throws
 * {@link IllegalArgumentException}; the runtime turns that into
 * {@link PipelineStatus#INVALID}.</p>
 *
 * @param stageIds ordered contract ids
 * @param version monotonic plan version
 */
public record PipelinePlan(List<String> stageIds, long version) {

    private static final Pattern STAGE_ID = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** Terminal stage id; nothing may follow it. */
    public static final String TERMINAL_STAGE = "sync";

    public PipelinePlan {
        stageIds = List.copyOf(Objects.requireNonNull(stageIds, "stageIds"));
        if (version < 0) {
            throw new IllegalArgumentException("plan version must not be negative");
        }
        for (int i = 0; i < stageIds.size(); i++) {
            String id = stageIds.get(i);
            if (id == null || !STAGE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid stage id: " + id);
            }
            if (stageIds.subList(0, i).contains(id)) {
                throw new IllegalArgumentException("duplicate stage id: " + id);
            }
            if (TERMINAL_STAGE.equals(id) && i != stageIds.size() - 1) {
                throw new IllegalArgumentException(
                        "stage '" + TERMINAL_STAGE + "' must be the last entry");
            }
        }
    }

    /** Build a plan at {@code version}. */
    public static PipelinePlan of(List<String> stageIds, long version) {
        return new PipelinePlan(stageIds, version);
    }

    /** Build the first plan, with an empty stage list. */
    public static PipelinePlan empty() {
        return new PipelinePlan(List.of(), 0L);
    }

    /** @return {@code true} when the plan has no stages */
    public boolean isEmpty() {
        return stageIds.isEmpty();
    }

    /** @return the status implied by the stage list alone */
    public PipelineStatus status() {
        return stageIds.isEmpty() ? PipelineStatus.NO_PIPELINE : PipelineStatus.READY;
    }
}
