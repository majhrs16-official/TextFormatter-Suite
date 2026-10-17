package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.iflow.vm.isa.*;
import me.majhrs16.suite.iflow.vm.execution.*;
import me.majhrs16.suite.iflow.vm.service.*;
import me.majhrs16.suite.iflow.vm.memory.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FirmwareIntegrationTest {

    @Test
    void runFirmwareBinary() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("/home/majhrs16/Documentos/textformatter-suite/tools/iflow-compiler/test/firmware.flow"));
        System.out.println("Loaded " + bytes.length + " bytes");
        
        ProgramImage program = ProgramImage.parse(bytes);
        System.out.println("Entry point: 0x" + Integer.toHexString(program.entryPoint()));
        System.out.println("Code length: " + program.code().length);
        System.out.println("Capabilities: " + String.join(", ", program.capabilities()));
        System.out.println("First 16 code bytes:");
        for (int i = 0; i < Math.min(16, program.code().length); i++) {
            System.out.printf("  code[%d] = 0x%02X%n", i, program.code()[i] & 0xFF);
        }
        
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
        ctx.setPageTable(softMMU.getPageTable());
        
        VmInterpreter interpreter = new VmInterpreter(program, softMMU, registry);
        
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
        } catch (Exception e) {
            System.out.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        } finally {
            pool.release(ctx);
            pool.close();
        }
    }
}
