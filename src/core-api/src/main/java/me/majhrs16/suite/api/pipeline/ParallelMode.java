package me.majhrs16.suite.api.pipeline;

/** How the sync pipeline finalizes its selected outputs. */
public enum ParallelMode {

    /** Use the configured default. */
    INHERIT,

    /** Start every selected output and wait for all of them. */
    PARALLEL,

    /** Deliver one output at a time, in declaration order. */
    SEQUENTIAL
}
