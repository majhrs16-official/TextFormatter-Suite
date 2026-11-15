package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The official policy shipped with the suite must not declare the generic
 * bridge capabilities (ADR-036 §16.7): {@code sync.invoke} is only declared by
 * third-party programs, and an official policy never gets authority over the
 * whole bus by default.
 */
class PassThroughProgramTest {

    @Test
    void officialPolicyDoesNotDeclareTheSyncBridgeCapabilities() {
        List<String> capabilities =
            Arrays.asList(PassThroughProgram.INSTANCE.capabilities());

        assertFalse(capabilities.contains("sync.invoke"),
            "the official policy must not declare sync.invoke: " + capabilities);
        assertFalse(capabilities.contains("sync.surface"),
            "the official policy must not declare sync.surface: " + capabilities);
    }

    @Test
    void officialPolicyStillDeclaresItsRoutingCapability() {
        ProgramImage image = PassThroughProgram.INSTANCE;

        assertTrue(Arrays.asList(image.capabilities()).contains("routing.decide"),
            "the official policy needs routing.decide to take its decision");
    }
}
