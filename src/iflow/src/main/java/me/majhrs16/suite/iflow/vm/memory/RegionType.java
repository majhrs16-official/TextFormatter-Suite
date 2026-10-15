package me.majhrs16.suite.iflow.vm.memory;

/**
 * Region types in the VM memory model.
 */
public enum RegionType {
    /** Executable code section (read + execute) */
    CODE,
    /** Constant data (read-only) */
    CONSTANTS,
    /** Private data per execution (read + write) */
    PRIVATE_DATA,
    /** Call stack (read + write) */
    STACK,
    /** Input buffers from host (read-only typically) */
    INPUT_BUFFER,
    /** Output buffers to host (write-only typically) */
    OUTPUT_BUFFER,
    /** Modification journal (managed by runtime) */
    JOURNAL,
    /** Persistent state (requires capability) */
    PERSISTENT_STATE,
    /** Firmware routines (read + execute, runtime-owned) */
    FIRMWARE,
    /** Service tables (runtime-owned, protected) */
    SERVICE_TABLE
}