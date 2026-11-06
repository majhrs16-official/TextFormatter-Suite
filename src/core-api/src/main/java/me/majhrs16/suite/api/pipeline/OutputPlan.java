package me.majhrs16.suite.api.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Outputs selected for one emission.
 *
 * <p>Each target is {@code <multiplexer>.<backend>}; the first segment is the
 * multiplexer and the rest may contain dots. {@code declared=false} means
 * "inherit the configuration" and requires an empty target list. A declared
 * empty list means "no outputs", which is not the same as inheriting.</p>
 *
 * @param declared whether the plan came from the message/config instead of
 *                 the multiplexer default
 * @param targets  {@code <multiplexer>.<backend>} strings, in order
 * @param parallel finalization mode
 */
public record OutputPlan(boolean declared, List<String> targets, ParallelMode parallel) {

    public OutputPlan {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        parallel = Objects.requireNonNull(parallel, "parallel");
        if (!declared && !targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "an inherited output plan cannot declare targets");
        }
        for (String target : targets) {
            int dot = target == null ? -1 : target.indexOf('.');
            if (dot <= 0 || dot == target.length() - 1) {
                throw new IllegalArgumentException(
                        "output target must be '<multiplexer>.<backend>': " + target);
            }
            if (target.indexOf("..") >= 0) {
                throw new IllegalArgumentException(
                        "output target contains an empty segment: " + target);
            }
        }
    }

    /** Inherit the configuration; the multiplexer applies its default. */
    public static OutputPlan inherit() {
        return new OutputPlan(false, List.of(), ParallelMode.INHERIT);
    }

    /** Declare "no outputs" explicitly. */
    public static OutputPlan none() {
        return new OutputPlan(true, List.of(), ParallelMode.INHERIT);
    }

    /** Build a declared plan from ordered targets. */
    public static OutputPlan of(List<String> targets, ParallelMode parallel) {
        return new OutputPlan(true, targets, parallel);
    }
}
