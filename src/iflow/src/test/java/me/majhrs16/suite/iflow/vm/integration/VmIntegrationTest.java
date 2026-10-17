package me.majhrs16.suite.iflow.vm.integration;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.RouteOutcome;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.iflow.vm.engine.VmPolicyEngine;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import me.majhrs16.suite.iflow.vm.service.ServiceResult;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.Collections;
import java.nio.charset.StandardCharsets;

/**
 * Cross-component integration tests verifying the interaction between
 * VM interpreter, memory system, service registry, and policy engine.
 */
class VmIntegrationTest {

    private SoftMMU createFullMMU() {
        SoftMMU mmu = new SoftMMU();
        // Standard layout, derived from the single-source memory map (VmMemoryMap).
        for (int p = 0; p < VmMemoryMap.CODE_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CODE_BASE) + p,
                VmMemoryMap.CODE_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_EXECUTE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.CONSTANTS_BASE),
            VmMemoryMap.CONSTANTS_BASE, PageEntry.PERM_READ);
        for (int p = 0; p < VmMemoryMap.DATA_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.DATA_BASE) + p,
                VmMemoryMap.DATA_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        for (int p = 0; p < VmMemoryMap.STACK_PAGES; p++) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.STACK_BASE) + p,
                VmMemoryMap.STACK_BASE + p * VmMemoryMap.PAGE,
                PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
            VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        return mmu;
    }

    private ChannelRegistry createChannelRegistry() {
        Channel chat = Channel.builder("chat")
            .sendPermission("chat.send")
            .receivePermission("chat.receive")
            .rateLimitPerSecond(0)
            .build();
        Channel global = Channel.builder("global")
            .sendPermission("global.send")
            .receivePermission("global.receive")
            .rateLimitPerSecond(0)
            .build();
        return ChannelRegistry.builder()
            .register(chat)
            .register(global)
            .build();
    }

    private PermissionChecker createPermissionChecker() {
        return (actor, perm) -> true;
    }

private RateLimiter createRateLimiter() {
        return new RateLimiter();
    }

    @Test
    void vmInterpreterWithSoftMMUMemoryOperations() {
        SoftMMU mmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        
// Program: write to data page, read back, verify
        // STW Rs, [vaddr] format: opcode + rs + vaddr16 (4 bytes)
        // LDW Rd, [vaddr] format: opcode + rd + vaddr16 (4 bytes)
        byte[] code = new byte[]{
            Opcode.LI, 0, 0x30, 0x00,  // LI R0, 0x3000 (data region)
            Opcode.LI, 1, 0x00, 0x42,  // LI R1, 0x42
            Opcode.STW, 1, 0x30, 0x00,  // STW R1, [0x3000]
            Opcode.LDW, 0, 0x30, 0x00,  // LDW R0, [0x3000]
            Opcode.HALT
        };
        
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(code)
            .build();
        
        // Use VmInterpreter directly with our MMU
        me.majhrs16.suite.iflow.vm.execution.VmInterpreter interpreter = 
            new me.majhrs16.suite.iflow.vm.execution.VmInterpreter(program, mmu, new HostServiceRegistry());
        
        // Create a minimal execution context
        me.majhrs16.suite.iflow.vm.execution.ExecutionContext ctx = 
            new me.majhrs16.suite.iflow.vm.execution.ExecutionContext()
                .executionId("test")
                .fuelBudget(10000)
                .softMMU(mmu)
                .serviceRegistry(new HostServiceRegistry());
        ctx.initialize();
        
        interpreter.execute(ctx);
        
        assertEquals(0x42, ctx.registers().get(0));
    }

    @Test
    void hostServiceRegistryWithVirtualAddresses() {
        SoftMMU mmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();

        // Test property read writes to the input buffer virtual address
        int vaddr = VmMemoryMap.INPUT_BASE;
        me.majhrs16.suite.iflow.vm.execution.ExecutionContext ctx = 
            new me.majhrs16.suite.iflow.vm.execution.ExecutionContext()
                .executionId("test")
                .fuelBudget(10000)
                .softMMU(mmu)
                .serviceRegistry(hsr)
                .allowedCapabilities(new String[]{"property.read"});
        ctx.initialize();
        ctx.hostContext(new me.majhrs16.suite.iflow.vm.service.HostContext() {
            @Override public String readProperty(int scope, int propId) { return "test-value"; }
        });
        
        ServiceResult result = hsr.invoke(
            ctx,
            HostServiceRegistry.PROPERTY_READ,
            vaddr, 100, 0
        );

        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals("test-value".length(), result.bytesTransferred());
        
        // Verify data written to virtual address
        byte[] data = new byte["test-value".length()];
        mmu.readBlock(vaddr, data, 0, data.length);
        assertEquals("test-value", new String(data, StandardCharsets.UTF_8));
    }

    @Test
    void hostServiceRegistryPermissionCheckWithVirtualArgs() {
        SoftMMU mmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry()
            .permissionChecker((actor, perm) -> "allowed".equals(perm));
        
        // Write args to virtual address: actor\0perm
        int vaddr = VmMemoryMap.INPUT_BASE;
        String args = "Alice\0allowed";
        byte[] argsBytes = args.getBytes(StandardCharsets.UTF_8);
        
        me.majhrs16.suite.iflow.vm.execution.ExecutionContext ctx = 
            new me.majhrs16.suite.iflow.vm.execution.ExecutionContext()
                .executionId("test")
                .fuelBudget(10000)
                .softMMU(mmu)
                .serviceRegistry(hsr)
                .allowedCapabilities(new String[]{"permission.check"});
        ctx.initialize();
        
        // Write test data AFTER initialize() (which clears the I/O buffers)
        mmu.writeBlock(vaddr, argsBytes, 0, argsBytes.length);
        
        ServiceResult result = hsr.invoke(
            ctx,
            HostServiceRegistry.PERMISSION_CHECK,
            vaddr, argsBytes.length, 0
        );

        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals(1, result.result()); // allowed -> R4
    }

    @Test
    void hostServiceRegistryRouteDecisionRecordsJournal() {
        SoftMMU mmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        me.majhrs16.suite.iflow.vm.execution.ExecutionContext ctx = 
            new me.majhrs16.suite.iflow.vm.execution.ExecutionContext()
                .executionId("test")
                .fuelBudget(10000)
                .softMMU(mmu)
                .serviceRegistry(hsr)
                .allowedCapabilities(new String[]{"routing.decide"});
        ctx.initialize();
        
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, HostServiceRegistry.DECISION_DROP);
        
        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals(1, ctx.journal().size());
        assertEquals(me.majhrs16.suite.iflow.vm.execution.ModificationJournal.OpType.CANCEL_MESSAGE, ctx.journal().entries().get(0).type);
    }

    @Test
    void softMMUCopyConstructorIsolation() {
        SoftMMU original = createFullMMU();
        original.writeWord(VmMemoryMap.DATA_BASE, 0xDEAD);
        
        SoftMMU copy = new SoftMMU(original);
        copy.writeWord(VmMemoryMap.DATA_BASE, 0xBEEF);
        
        assertEquals(0xDEAD, original.readWord(VmMemoryMap.DATA_BASE));
        assertEquals(0xBEEF, copy.readWord(VmMemoryMap.DATA_BASE));
    }

    @Test
    void pageTablePermissionsEnforcedAcrossComponents() {
        SoftMMU mmu = createFullMMU();
        
        // The data region (page 3) has R/W
        mmu.writeByte(VmMemoryMap.DATA_BASE, 0x01);
        assertEquals(0x01, mmu.readByte(VmMemoryMap.DATA_BASE));
        
        // The constants page (page 2) is read-only - write should fail
        assertThrows(me.majhrs16.suite.iflow.vm.isa.VmException.class, 
            () -> mmu.writeByte(VmMemoryMap.CONSTANTS_BASE, 0x01));
        
        // But read should still work
        assertEquals(0x00, mmu.readByte(VmMemoryMap.CONSTANTS_BASE)); // Default value is 0
    }

    @Test
    void fuelConsumptionAcrossInstructions() {
        SoftMMU mmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        
        // Program with many instructions
        byte[] code = new byte[100];
        for (int i = 0; i < 99; i++) code[i] = Opcode.ADD;
        code[99] = Opcode.HALT;
        
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(code)
            .build();
        
        me.majhrs16.suite.iflow.vm.execution.VmInterpreter interpreter = 
            new me.majhrs16.suite.iflow.vm.execution.VmInterpreter(program, mmu, hsr);
        me.majhrs16.suite.iflow.vm.execution.ExecutionContext ctx = 
            new me.majhrs16.suite.iflow.vm.execution.ExecutionContext()
                .executionId("test")
                .fuelBudget(50)
                .softMMU(mmu)
                .serviceRegistry(hsr);
        ctx.initialize();
        
        assertThrows(me.majhrs16.suite.iflow.vm.isa.VmException.class, () -> {
            interpreter.execute(ctx);
        });
    }

    @Test
    void crossPageMemoryOperations() {
        SoftMMU mmu = createFullMMU();
        
        // Write block crossing a page boundary within the data/stack region
        byte[] src = new byte[5000];
        for (int i = 0; i < src.length; i++) src[i] = (byte)(i & 0xFF);
        mmu.writeBlock(0x3F00, src, 0, src.length);
        
        byte[] dst = new byte[src.length];
        mmu.readBlock(0x3F00, dst, 0, dst.length);
        assertArrayEquals(src, dst);
    }

    @Test
    void executionContextPoolWithVmInterpreter() {
        SoftMMU templateMmu = createFullMMU();
        HostServiceRegistry hsr = new HostServiceRegistry();
        
        // Can't directly test pool without ExecutionContextPool, but we can verify
        // that multiple contexts created from same template work independently
        SoftMMU mmu1 = new SoftMMU(templateMmu);
        SoftMMU mmu2 = new SoftMMU(templateMmu);
        
        mmu1.writeWord(VmMemoryMap.DATA_BASE, 0x1111);
        mmu2.writeWord(VmMemoryMap.DATA_BASE, 0x2222);
        
        assertEquals(0x1111, mmu1.readWord(VmMemoryMap.DATA_BASE));
        assertEquals(0x2222, mmu2.readWord(VmMemoryMap.DATA_BASE));
    }

    @Test
    void fullPolicyEngineWithSoftMMU() {
        // End-to-end test: VmPolicyEngine with custom program using SoftMMU
        VmPolicyEngine engine = new VmPolicyEngine(
            createChannelRegistry(),
            (actor, perm) -> true,
            new RateLimiter()
        );
        
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                // Read message.content into the input buffer.
                // HOST ABI v2: R0=op, R1=options, R2=base, R3=length.
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8), (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)0x10,   // options = (SCOPE_MESSAGE<<8)|PROP_CONTENT
                Opcode.LI, 2, (byte)0x70, (byte)0x00,   // base = 0x7000 (input buffer)
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,   // length = 255
                Opcode.HOST, (byte)0x00,
                // Continue
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_CONTINUE >> 8), (byte)(HostServiceRegistry.DECISION_CONTINUE & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = Actor.unknown("Alice");
        Actor recipient = Actor.unknown("Bob");
        Message msg = new Message.Builder()
            .id(UUID.randomUUID())
            .text("test message")
            .channel("chat")
            .direction(Direction.initiator())
            .sender(sender)
            .build();
        
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }
}