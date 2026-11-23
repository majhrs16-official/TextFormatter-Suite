package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomParser;

/**
 * Boots the guest BIOS/BOOT/KERNEL chain emitted by the v3 assembler.
 *
 * <p>The host only maps the ROM container and starts at {@code resetVector};
 * the guest BIOS scans the TOC, builds the page tables, copies BOOT to RAM and
 * enables the MMU. The bootloader copies KERNEL and jumps to it. The kernel
 * writes a marker and halts; scheduler, IPI and TLB shootdown belong to the
 * next V3-2b slice.</p>
 *
 * <p>A boot may span several host quantum slices. The test resumes the same
 * core until it halts, which is the contract of a preemptible quantum; it does
 * not treat {@code PREEMPTED} as a failure.</p>
 */
class GuestBootTest {

    private static final int MARKER = 0x0200_E030;
    private static final int BOOT_MARKER = 0x600D_0001;
    private static final int MAX_SLICES = 1_000;

    @Test
    void bootsGuestChainOnCoreZeroAndParksTheOthers() {
        RomImage image = RomParser.parse(fixture());
        VirtualClock clock = new VirtualClock();
        MachineMemory memory = MachineMemory.fromRom(
                new CsrFile(0), image, MachineFixture.RAM_SIZE, clock, false, 2);
        MachineRuntime runtime = new MachineRuntime(memory, clock,
                image.header().resetVector(), ExecutionQuantum.defaults(),
                new WatchdogConfig(false));

        ExecutionReport boot = runToHalt(runtime, 0);

        assertEquals(ExecutionStatus.HALTED, boot.status());
        assertEquals(BOOT_MARKER, memory.physical().readWord(MARKER));

        ExecutionReport parked = runToHalt(runtime, 1);

        assertEquals(ExecutionStatus.HALTED, parked.status());
        assertEquals(BOOT_MARKER, memory.physical().readWord(MARKER));
    }

    private static ExecutionReport runToHalt(MachineRuntime runtime, int coreId) {
        ExecutionReport report = runtime.runCore(coreId);
        int slices = 1;
        while (report.status() == ExecutionStatus.PREEMPTED && slices < MAX_SLICES) {
            report = runtime.runCore(coreId);
            slices++;
        }
        assertTrue(slices < MAX_SLICES, "core did not halt within " + MAX_SLICES + " slices");
        return report;
    }

    private static byte[] fixture() {
        try (InputStream input = GuestBootTest.class
                .getResourceAsStream("/rom/v3/boot-chain.rom")) {
            assert input != null : "boot-chain fixture is missing";
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read boot-chain fixture", exception);
        }
    }
}
