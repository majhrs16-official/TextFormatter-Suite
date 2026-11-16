package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ExecutionBudgetTest {

    @Test
    void defaultsMatchTheHostQuantumAndWatchdog() {
        ExecutionBudget budget = ExecutionBudget.defaults();

        assertEquals(1_000_000, budget.maxInstructions());
        assertEquals(50_000_000, budget.maxGuestNanos());
    }

    @Test
    void rejectsNonPositiveInstructionBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(-1, 1));
    }

    @Test
    void rejectsNonPositiveWallBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionBudget(1, -1));
    }
}
