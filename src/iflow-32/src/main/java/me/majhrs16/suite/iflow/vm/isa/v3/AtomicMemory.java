package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Memory port that implements the v3 atomics of ADR-037 §4.
 *
 * <p>The reservation of {@code LR.W} belongs to the core that issued it. A
 * conditional store succeeds only while that reservation is still valid. The
 * implementation must serialize read-modify-write operations and reject MMIO
 * and unaligned accesses before touching memory.</p>
 */
public interface AtomicMemory {

    /** Operation performed by an {@code AMO*.W} instruction. */
    enum Operation {
        ADD,
        SWAP,
        AND,
        OR
    }

    /**
     * Loads a 32-bit word and leaves a reservation on its physical address.
     */
    int loadLinked(int address);

    /**
     * Stores a 32-bit word only if the current core still holds the reservation
     * for that physical address.
     *
     * @return {@code true} when the store was performed
     */
    boolean storeConditional(int address, int value);

    /**
     * Reads, combines and writes a 32-bit word atomically.
     *
     * @return the value before the operation
     */
    int atomic(int address, Operation operation, int operand);

    /**
     * Drops the current core's reservation. Traps, {@code ERET}, {@code HALT},
     * reset and a context switch must call this.
     */
    void clearReservation();
}
