package me.majhrs16.suite.iflow.vm.execution.v3;

/**
 * Terminal state of one host execution window.
 */
public enum ExecutionStatus {

    /** The vCPU halted and no armed wake source remains. */
    HALTED,

    /** The instruction budget was exhausted. */
    QUANTUM_EXCEEDED,

    /** The wall-clock budget charged to guest execution was exhausted. */
    HOST_TIMEOUT
}
