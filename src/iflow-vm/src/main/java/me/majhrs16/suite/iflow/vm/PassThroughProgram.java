package me.majhrs16.suite.iflow.vm;

import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

/**
 * Minimal pass-through policy (AUD-027).
 * <p>
 * Calls {@code ROUTE_DECISION} with {@code DECISION_CONTINUE} and halts, leaving
 * the message untouched. This is the single source of the fallback program used
 * by the loader (no installed {@code .flow}) and by the engine (no configured
 * program), so the bytecode cannot diverge between both paths.
 * </p>
 */
public final class PassThroughProgram {

    public static final ProgramImage INSTANCE = ProgramImage.builder()
        .entryPoint(0)
        .code(new byte[]{
            // HOST ABI v2: R0 = service ID, R1 = options (decision code).
            Opcode.LI, 0,
                (HostServiceRegistry.ROUTE_DECISION >> 8) & 0xFF,
                HostServiceRegistry.ROUTE_DECISION & 0xFF,
            Opcode.LI, 1,
                (HostServiceRegistry.DECISION_CONTINUE >> 8) & 0xFF,
                HostServiceRegistry.DECISION_CONTINUE & 0xFF,
            Opcode.HOST, 0x00, // opcode + reserved padding byte
            Opcode.HALT
        })
        .capabilities(new String[]{"routing.decide"})
        .build();

    private PassThroughProgram() {
    }
}
