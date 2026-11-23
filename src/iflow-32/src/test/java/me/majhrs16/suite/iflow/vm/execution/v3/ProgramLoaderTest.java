package me.majhrs16.suite.iflow.vm.execution.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.iflow.vm.host.v3.HostAbi;
import me.majhrs16.suite.iflow.vm.host.v3.HostBridge;
import me.majhrs16.suite.iflow.vm.host.v3.HostServiceException;
import me.majhrs16.suite.iflow.vm.host.v3.PropertyWrite;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.VirtualClock;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineMemory;
import me.majhrs16.suite.iflow.vm.memory.v3.Mmu;
import me.majhrs16.suite.iflow.vm.rom.RomImage;
import me.majhrs16.suite.iflow.vm.rom.RomParser;

/**
 * Loads the single {@code PROGRAM} of a v3 ROM from the guest kernel.
 *
 * <p>The fixture covers ADR-037 §9.3: the kernel reads {@code PRG3} from the
 * ROM, copies the segment to a physical alias, zero-fills up to {@code memSize},
 * maps code R/X and data/bss R/W with {@code U = 1}, assigns {@code PIDENT} and
 * {@code MIDENT} and enters user mode. The program calls
 * {@code HOST MESSAGE_ID}, records the status and returns with {@code ECALL}.
 * The kernel handler verifies the user ECALL vector and halts.</p>
 *
 * <p>The host is the only component that verifies SHA-256 and capability names
 * ({@link RomParser}); the guest revalidates the PRG3 structure it consumes.</p>
 */
class ProgramLoaderTest {

    private static final int SCRATCH = 0x0200_E000;
    private static final int BOOT_MARKER = SCRATCH + 0x30;
    private static final int TRAP_MARKER = SCRATCH + 0x34;
    private static final int TRAP_CAUSE = SCRATCH + 0x38;
    private static final int BOOT_READY = SCRATCH + 0x40;
    private static final int KERNEL_READY = SCRATCH + 0x44;
    private static final int PROGRAM_LOADED = SCRATCH + 0xA0;
    private static final int PROGRAM_DONE = SCRATCH + 0xA4;
    private static final int LAST_HOST_STATUS = SCRATCH + 0xA8;
    private static final int LAST_HOST_LENGTH = SCRATCH + 0xAC;
    private static final int PROGRAM_PAGES = SCRATCH + 0xB0;

    private static final int ALIAS = 0x0280_0000;
    private static final int STACK_PHYSICAL = 0x0290_0000;
    private static final int USER_TABLE = 0x0200_7000;
    private static final int PROGRAM_PTE = USER_TABLE + 0x0400;
    private static final int BSS_PTE = USER_TABLE + 0x0404;
    private static final int STACK_PTE = USER_TABLE + 0x0800;
    private static final int PROGRAM_PAGE_FLAGS = 0x35;
    private static final int DATA_PAGE_FLAGS = 0x2D;

    private static final int BOOT_MARKER_VALUE = 0x600D_0001;
    private static final int ECALL_USER_VECTOR = 7;
    private static final int PATCHED_MEM_SIZE = 0x2000;
    private static final int MAX_MEM_SIZE_MARKER = 0xBAD0_0006;
    private static final int MAX_ROUNDS = 2_000;

    @Test
    void loadsProgramRunsHostAndHaltsOnUserEcall() throws Exception {
        byte[] bytes = fixture();
        int segmentSize = patchProgramMemSize(bytes, PATCHED_MEM_SIZE);
        RomImage image = RomParser.parse(bytes);
        VirtualClock clock = new VirtualClock();
        MachineMemory memory = MachineMemory.fromRom(
                new CsrFile(0), image, MachineFixture.RAM_SIZE, clock, false, 1);

        // Garbage beyond size must be zeroed by the loader, not by the copy.
        int bssStart = (segmentSize + 3) & ~3;
        for (int offset = bssStart; offset < PATCHED_MEM_SIZE; offset += 4) {
            memory.physical().writeWord(ALIAS + offset, 0xDEAD_BEEF);
        }

        FakeBridge bridge = new FakeBridge();
        HostAbi host = new HostAbi(bridge);
        host.beginExecution(1);
        MachineRuntime runtime = new MachineRuntime(
                memory,
                clock,
                image.header().resetVector(),
                new ExecutionQuantum(4_096, 50_000_000L),
                new WatchdogConfig(false),
                ignored -> host,
                System::nanoTime);

        runUntilHalted(runtime);
        host.commitExecution(1);

        assertEquals(BOOT_MARKER_VALUE, word(memory, BOOT_MARKER));
        assertEquals(1, word(memory, BOOT_READY));
        assertEquals(1, word(memory, KERNEL_READY));
        assertEquals(1, word(memory, PROGRAM_LOADED));
        assertEquals(1, word(memory, PROGRAM_DONE));
        assertEquals(0, word(memory, TRAP_MARKER));
        assertEquals(ECALL_USER_VECTOR, word(memory, TRAP_CAUSE));
        assertEquals(2, word(memory, PROGRAM_PAGES));

        // MESSAGE_ID returned OK and a TLV map with the id.
        assertEquals(0, word(memory, STACK_PHYSICAL + 0x400));
        int length = word(memory, STACK_PHYSICAL + 0x404);
        assertTrue(length > 0, "HOST returned no result");
        Map<String, TlvCodec.TlvValue> result = TlvCodec
                .decode(bytes(memory, STACK_PHYSICAL + 0x800, length)).asMap();
        assertEquals("msg-loader-0001", result.get(HostAbi.KEY_ID).asString());

        // The last HOST (ROUTE_DECISION) has no result payload.
        assertEquals(0, word(memory, LAST_HOST_STATUS));
        assertEquals(0, word(memory, LAST_HOST_LENGTH));

        // The loader zero-filled from size to memSize.
        for (int offset = bssStart; offset < PATCHED_MEM_SIZE; offset += 4) {
            assertEquals(0, word(memory, ALIAS + offset),
                    "nonzero bss at alias + 0x" + Integer.toHexString(offset));
        }

        // W^X page permissions: code R/X/U, data and stack R/W/U. A/D are
        // hardware-maintained, so they are masked out of the comparison.
        int hardware = Mmu.PTE_A | Mmu.PTE_D;
        assertEquals(ALIAS | PROGRAM_PAGE_FLAGS, word(memory, PROGRAM_PTE) & ~hardware);
        assertEquals(ALIAS + 0x1000 | DATA_PAGE_FLAGS, word(memory, BSS_PTE) & ~hardware);
        assertEquals(STACK_PHYSICAL | DATA_PAGE_FLAGS, word(memory, STACK_PTE) & ~hardware);
        assertFalse(bridge.messageIdRequested.isEmpty());
        assertEquals(List.of(1), bridge.messageIdRequested);

        // ROUTE_DECISION latched in the journal and applied on commit.
        assertEquals(0, word(memory, STACK_PHYSICAL + 0x408));
        assertEquals(0, word(memory, STACK_PHYSICAL + 0x40C));
        assertEquals(1, bridge.decisions);
        assertEquals(HostAbi.TARGET_DELIVER, bridge.lastTarget);
        assertEquals("loader", bridge.lastReason);
        assertNull(bridge.lastError);
        assertEquals(List.of(), bridge.appliedWrites);
    }

    @Test
    void rejectsProgramAboveTheSliceMemoryLimit() {
        byte[] bytes = fixture();
        patchProgramMemSize(bytes, 0x20000);
        RomImage image = RomParser.parse(bytes);
        VirtualClock clock = new VirtualClock();
        MachineMemory memory = MachineMemory.fromRom(
                new CsrFile(0), image, MachineFixture.RAM_SIZE, clock, false, 1);
        FakeBridge bridge = new FakeBridge();
        HostAbi host = new HostAbi(bridge);
        host.beginExecution(1);
        MachineRuntime runtime = new MachineRuntime(
                memory,
                clock,
                image.header().resetVector(),
                new ExecutionQuantum(4_096, 50_000_000L),
                new WatchdogConfig(false),
                ignored -> host,
                System::nanoTime);

        runUntilHalted(runtime);
        host.abortExecution(1);

        assertEquals(MAX_MEM_SIZE_MARKER, word(memory, TRAP_MARKER));
        assertEquals(0, word(memory, PROGRAM_LOADED));
        assertTrue(bridge.messageIdRequested.isEmpty());
    }

    private static void runUntilHalted(MachineRuntime runtime) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            ExecutionReport report = runtime.runCore(0);
            if (report.status() == ExecutionStatus.WATCHDOG) {
                fail("core 0 hit the watchdog");
            }
            if (runtime.core(0).cpu().halted()) {
                return;
            }
        }
        fail("program loader did not halt within " + MAX_ROUNDS + " rounds");
    }

    private static int word(MachineMemory memory, int address) {
        return memory.physical().readWord(address);
    }

    private static byte[] bytes(MachineMemory memory, int address, int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) memory.physical().readByte(address + index);
        }
        return result;
    }

    /**
     * Raises the TOC {@code memSize} above {@code size} without touching the
     * segment bytes. Only the ROM global hash changes. Returns the original
     * segment size.
     */
    private static int patchProgramMemSize(byte[] rom, int memSize) {
        int toc = readU32(rom, 28);
        int count = readU16(rom, 32);
        for (int index = 0; index < count; index++) {
            int entry = toc + index * 64;
            if ((rom[entry] & 0xFF) == 4) {
                int size = readU32(rom, entry + 8);
                putU32(rom, entry + 20, memSize);
                byte[] hash = sha256(rom);
                System.arraycopy(hash, 0, rom, 48, 32);
                return size;
            }
        }
        throw new IllegalStateException("PROGRAM segment is missing");
    }

    private static byte[] sha256(byte[] rom) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] copy = rom.clone();
            java.util.Arrays.fill(copy, 48, 80, (byte) 0);
            return digest.digest(copy);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static int readU16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void putU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] fixture() {
        try (InputStream input = ProgramLoaderTest.class
                .getResourceAsStream("/rom/v3/program-loader.rom")) {
            assert input != null : "program-loader fixture is missing";
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read program-loader fixture", exception);
        }
    }

    private static final class FakeBridge implements HostBridge {

        private final Set<String> capabilities = Set.of(
                HostAbi.CAP_MESSAGE_ID, HostAbi.CAP_ROUTE_DECISION);
        private final List<Integer> messageIdRequested = new java.util.ArrayList<>();
        private int decisions;
        private int lastTarget;
        private String lastReason;
        private String lastError;
        private List<PropertyWrite> appliedWrites = List.of();

        @Override
        public boolean verifyProgram(int pident) {
            return pident == 0;
        }

        @Override
        public boolean verifyMessage(int mident) {
            return mident == 1;
        }

        @Override
        public Set<String> capabilities(int pident) {
            return capabilities;
        }

        @Override
        public Optional<TlvCodec.TlvValue> propertyRead(int pident, int mident,
                                                       String scope, String property,
                                                       Map<String, TlvCodec.TlvValue> arguments) {
            throw new UnsupportedOperationException("PROPERTY_READ is not used");
        }

        @Override
        public void propertyWrite(int pident, int mident, String scope, String property,
                                  TlvCodec.TlvValue value) {
            throw new UnsupportedOperationException("PROPERTY_WRITE is not used");
        }

        @Override
        public void validatePropertyWrite(int pident, int mident, String scope,
                                          String property, TlvCodec.TlvValue value) {
            throw new UnsupportedOperationException("PROPERTY_WRITE is not used");
        }

        @Override
        public void applyPropertyWrites(List<PropertyWrite> writes) {
            appliedWrites = List.copyOf(writes);
        }

        @Override
        public boolean propertyExists(int pident, int mident, String scope, String property) {
            throw new UnsupportedOperationException("PROPERTY_EXISTS is not used");
        }

        @Override
        public boolean permissionCheck(int pident, int mident, String actor,
                                       String channel, String permission) {
            throw new UnsupportedOperationException("PERMISSION_CHECK is not used");
        }

        @Override
        public void routeDecision(int pident, int mident, int target,
                                  String reason, String error) {
            assertEquals(0, pident);
            assertEquals(1, mident);
            decisions++;
            lastTarget = target;
            lastReason = reason;
            lastError = error;
        }

        @Override
        public TlvCodec.TlvValue transformApply(int pident, int mident, String kind,
                                                TlvCodec.TlvValue input) {
            throw new UnsupportedOperationException("TRANSFORM_APPLY is not used");
        }

        @Override
        public String messageId(int mident) {
            messageIdRequested.add(mident);
            return "msg-loader-0001";
        }

        @Override
        public Optional<List<SyncSurface>> syncSurfaces(int pident) {
            return Optional.empty();
        }

        @Override
        public SyncResult syncInvoke(int pident, int mident, String backend, String action,
                                     TlvCodec.TlvValue args, String idempotencyKey,
                                     String reason) throws HostServiceException {
            throw new UnsupportedOperationException("SYNC_INVOKE is not used");
        }
    }
}
