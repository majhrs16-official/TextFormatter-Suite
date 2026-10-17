package me.majhrs16.suite.host;

import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Test-only helpers that assemble tiny VM policy programs for the host
 * integration tests.
 *
 * <p>The iflow VM executes binary programs only (the old {@code Rule} list API
 * was removed), so tests that need a specific routing decision build the
 * equivalent program here. The programs call the {@code ROUTE_DECISION} host
 * service (R0 = service id, R3 = decision code) and then {@code HALT}.</p>
 */
final class PolicyPrograms {

    /** Data page base (0x2000), mapped read/write by {@code VmPolicyEngine}. */
    private static final int SCRATCH = 0x2000;

    private PolicyPrograms() {}

    /** Installs a program on the host's router that unconditionally emits {@code decision}. */
    static void setDecision(SuiteHost host, int decision) {
        ((VmPolicyEngine) host.router()).setProgram(decision(decision));
    }

    /** Installs a program on the host's router that unconditionally redirects to {@code channel}. */
    static void setChannelRedirect(SuiteHost host, String channel) {
        ((VmPolicyEngine) host.router()).setProgram(channelRedirect(channel));
    }

    /**
     * Program that unconditionally emits {@code decision} (LOG/DROP/REJECT/REDIRECT).
     */
    static ProgramImage decision(int decision) {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        emitLi(code, 0, HostServiceRegistry.ROUTE_DECISION);
        emitLi(code, 1, decision); // HOST ABI v2: R1 = options (decision code)
        emitHost(code);
        code.write(Opcode.HALT);
        return build(code);
    }

    /**
     * Program that unconditionally redirects to another {@code channel}
     * (CHANNEL_REDIRECT). The channel name is written into the data page and
     * passed to the host service as its argument buffer.
     */
    static ProgramImage channelRedirect(String channel) {
        byte[] name = channel.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream code = new ByteArrayOutputStream();

        // HOST ABI v2: R1 = options (decision), R2 = base (arg buffer vaddr), R3 = length.
        emitLi(code, 0, HostServiceRegistry.ROUTE_DECISION);
        emitLi(code, 1, HostServiceRegistry.DECISION_CHAN_REDIR);
        emitLi(code, 2, SCRATCH);
        emitLi(code, 3, name.length);

        // Write the channel name into the scratch buffer.
        for (int i = 0; i < name.length; i++) {
            emitLi(code, 5, name[i] & 0xFF);
            int addr = SCRATCH + i;
            code.write(Opcode.STB);
            code.write(5);
            code.write((addr >> 8) & 0xFF);
            code.write(addr & 0xFF);
        }

        emitHost(code);
        code.write(Opcode.HALT);
        return build(code);
    }

    private static void emitLi(ByteArrayOutputStream code, int rd, int imm) {
        code.write(Opcode.LI);
        code.write(rd);
        code.write((imm >> 8) & 0xFF);
        code.write(imm & 0xFF);
    }

    private static void emitHost(ByteArrayOutputStream code) {
        code.write(Opcode.HOST);
        code.write(0x00);
    }

    private static ProgramImage build(ByteArrayOutputStream code) {
        return ProgramImage.builder()
            .entryPoint(0)
            .code(code.toByteArray())
            .capabilities(new String[]{"routing.decide"})
            .build();
    }
}
