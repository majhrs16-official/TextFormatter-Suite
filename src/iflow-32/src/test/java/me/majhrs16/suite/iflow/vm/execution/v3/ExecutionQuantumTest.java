package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutionQuantumTest {

    @Test
    void defaultsMatchTheHostQuantumAndWatchdog() {
        ExecutionQuantum quantum = ExecutionQuantum.defaults();

        assertEquals(1_000_000, quantum.maxInstructions());
        assertEquals(50_000_000, quantum.maxGuestNanos());
    }

    @Test
    void rejectsNonPositiveInstructionQuantum() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionQuantum(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionQuantum(-1, 1));
    }

    @Test
    void rejectsNonPositiveWallQuantum() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionQuantum(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionQuantum(1, -1));
    }
}
