package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.iflow.vm.isa.*;
import me.majhrs16.suite.iflow.vm.execution.*;
import me.majhrs16.suite.iflow.vm.service.*;
import me.majhrs16.suite.iflow.vm.memory.*;
import me.majhrs16.suite.iflow.vm.video.VmDisplay;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareIntegrationTest {

    @Test
    void runFirmwareBinary() throws Exception {
        byte[] bytes = Files.readAllBytes(fixture("tools/iflow-compiler/test/firmware.flow"));
        System.out.println("Loaded " + bytes.length + " bytes");
        
        ProgramImage program = ProgramImage.parse(bytes);
        System.out.println("Entry point: 0x" + Integer.toHexString(program.entryPoint()));
        System.out.println("Code length: " + program.code().length);
        System.out.println("Capabilities: " + String.join(", ", program.capabilities()));

        // With the firmware ROM active the machine resets into the BIOS reset
        // routine (D32), which lives in the ROM page (0x0000-0x0FFF) and chains
        // to the user entry at 0x1000. The code section spans both pages.
        assertTrue(program.entryPoint() < VmMemoryMap.PAGE,
            "firmware-on images must reset into the BIOS, inside the ROM page");
        assertTrue(program.code().length > VmMemoryMap.PAGE,
            "firmware-on code section must span ROM + user code");
        
        HostServiceRegistry registry = new HostServiceRegistry();
        registry.declareCapability("property.read");
        registry.declareCapability("property.write");
        registry.declareCapability("permission.check");
        registry.declareCapability("routing.decide");
        registry.declareCapability("state.read");
        registry.declareCapability("state.write");
        registry.declareCapability("channel.resolve");
        
        // Create SoftMMU with the standard layout (see VmMemoryMap)
        SoftMMU softMMU = new SoftMMU();
        for (int p = 0; p < VmMemoryMap.CODE_PAGES; p++) {
            softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                VmMemoryMap.CODE_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }
        softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.CONSTANTS_BASE),
            VmMemoryMap.CONSTANTS_BASE, PageEntry.PERM_READ);
        for (int p = 0; p < VmMemoryMap.DATA_PAGES; p++) {
            softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.DATA_BASE) + p,
                VmMemoryMap.DATA_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        for (int p = 0; p < VmMemoryMap.STACK_PAGES; p++) {
            softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.STACK_BASE) + p,
                VmMemoryMap.STACK_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        softMMU.mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
            VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        
        // Load the image into memory: the CPU now fetches instructions through the
        // MMU, so the code must live at CODE_BASE (IFLOW-AUD-023). The loader is a
        // supervisor master: writable temporarily, then demoted to R/X.
        byte[] image = program.code();
        int codePages = (image.length + VmMemoryMap.PAGE - 1) / VmMemoryMap.PAGE;
        for (int p = 0; p < codePages; p++) {
            softMMU.getPageTable().protect(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE | PageEntry.PERM_EXECUTE);
        }
        VmBus.supervisor(softMMU).writeBlock(VmMemoryMap.CODE_BASE, image, 0, image.length);
        for (int p = 0; p < codePages; p++) {
            softMMU.getPageTable().protect(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }

        // Attach the machine peripherals after the image is laid out (as the engine
        // does, so the loader never writes into the devices): the BIOS drives the
        // text screen and stores its cold-boot flag in NVRAM through MMIO.
        VmDisplay display = new VmDisplay();
        softMMU.attach(display);
        VmNvram nvram = new VmNvram();
        softMMU.attach(nvram);
        
        // Write test message content at the input buffer
        String testMsg = "spam message";
        for (int i = 0; i < testMsg.length(); i++) {
            softMMU.writeByte(VmMemoryMap.INPUT_BASE + i, (byte)testMsg.charAt(i));
        }
        
        ExecutionContextPool pool = new ExecutionContextPool(registry, ctx -> {
            ctx.fuelBudget(100000);
        });
        
        ExecutionContext ctx = pool.acquire();
        ctx.softMMU(softMMU);
        ctx.allowedCapabilities(new String[]{
            "property.read", "property.write", "permission.check",
            "routing.decide", "state.read", "state.write", "channel.resolve"
        });
        ctx.initialize();

        VmInterpreter interpreter = new VmInterpreter(program, registry);
        
        // Debug: check what the VM will fetch at IP=0
        byte[] vmCode = program.code();
        System.out.println("VM code array first 4 bytes: " + 
            String.format("0x%02X 0x%02X 0x%02X 0x%02X", 
                vmCode[0] & 0xFF, vmCode[1] & 0xFF, vmCode[2] & 0xFF, vmCode[3] & 0xFF));
        
        // Verify program identity
        System.out.println("Program identity: " + System.identityHashCode(program));
        
        try {
            interpreter.execute(ctx);
            System.out.println("Execution completed successfully!");
            System.out.println("Fuel remaining: " + ctx.fuelRemaining());
            for (int i = 0; i < 12; i++) {
                System.out.println("R" + i + ": " + ctx.registers().get(i));
            }
            assertTrue(ctx.fuelRemaining() > 0, "Should have fuel remaining");

            // The BIOS ran before user main (D32): it enabled and configured the
            // text screen and marked its cold boot in NVRAM.
            assertTrue(display.isEnabled(), "BIOS must enable the text screen");
            assertEquals(VmMemoryMap.DISPLAY_DEFAULT_BASE, display.baseAddress(),
                "BIOS must map the screen at the default base");
            assertEquals(VmMemoryMap.DISPLAY_DEFAULT_COLS, display.columns());
            assertEquals(VmMemoryMap.DISPLAY_DEFAULT_ROWS, display.rows());
            assertEquals(0xB105, nvram.readWord(VmMemoryMap.DEVICE_NVRAM_BASE),
                "BIOS must record its cold boot in NVRAM");

            // Test 8 in the fixture writes "Hi\0" at FW_STR and calls the ROM's
            // fw_strlen (R1 = src -> R0 = length), storing the result at
            // RESULT_BUFFER (0x3100). This exercises the real firmware ROM code
            // over actual bytes (the ISA-v2 indirect loads).
            int strlenResult = softMMU.readWord(0x3100);
            assertEquals(2, strlenResult, "CALL fw_strlen over \"Hi\" must return 2");
        } catch (Exception e) {
            System.out.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            pool.release(ctx);
            pool.close();
        }
    }

    /**
     * Resolves a repository-relative fixture without hardcoding an absolute path:
     * walks up from the working directory until it finds {@code settings.gradle}
     * (the repo root) and resolves {@code relative} from there.
     */
    private static Path fixture(String relative) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                "Repository root (settings.gradle) not found from " + Paths.get("").toAbsolutePath());
        }
        return dir.resolve(relative);
    }
}
