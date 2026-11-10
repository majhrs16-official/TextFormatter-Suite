package me.majhrs16.suite.api.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Outputs selected for one emission.
 *
 * <p>Each target is {@code sync.<backend>}; {@code sync} is the only
 * namespace and the backend names the stage that consumes it. A backend may
 * contain dots. {@code declared=false} means "inherit the configuration" and
 * requires an empty target list. A declared empty list means "no outputs",
 * which is not the same as inheriting.</p>
 *
 * @param declared whether the plan came from the message/config instead of
 *                 the module default
 * @param targets  {@code sync.<backend>} strings, in order
 * @param parallel finalization mode
 */
public record OutputPlan(boolean declared, List<String> targets, ParallelMode parallel) {

    /** Namespace shared by every declared output target. */
    public static final String NAMESPACE = "sync";

    private static final String PREFIX = NAMESPACE + ".";

    public OutputPlan {
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        parallel = Objects.requireNonNull(parallel, "parallel");
        if (!declared && !targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "an inherited output plan cannot declare targets");
        }
        for (String target : targets) {
            if (target == null || !target.startsWith(PREFIX)) {
                throw new IllegalArgumentException(
                        "output target must be 'sync.<backend>': " + target);
            }
            String backend = target.substring(PREFIX.length());
            if (backend.isBlank() || backend.startsWith(".")
                    || backend.endsWith(".") || backend.contains("..")) {
                throw new IllegalArgumentException(
                        "output target contains an empty backend segment: " + target);
            }
        }
    }

    /**
     * @param target declared {@code sync.<backend>} target
     * @return the backend segment
     * @throws IllegalArgumentException when the target does not use the
     *                                  {@code sync} namespace
     */
    public static String backend(String target) {
        if (target == null || !target.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                    "output target must be 'sync.<backend>': " + target);
        }
        return target.substring(PREFIX.length());
    }

    /** Inherit the configuration; the module applies its default. */
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
