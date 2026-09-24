package me.majhrs16.suite.kernel;

/**
 * Final state of a module after graph resolution.
 */
public enum ResolutionStatus {
    /** Activated: all requirements satisfiable, contract and JVM ok. */
    RESOLVED,
    /** Could not activate; one or more requirements are unmet. */
    UNSATISFIED_REQUIREMENT,
    /** Compiled against a {@code *-api} version incompatible with the host. */
    CONTRACT_MISMATCH,
    /** Requires a JVM the host is not running. */
    JVM_MISMATCH,
    /** Unsat only because it depends on its own cycle. */
    CYCLE
}