package me.majhrs16.suite.iflow.vm.service;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import me.majhrs16.suite.iflow.vm.memory.VmDmaController;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HostServiceRegistryTest {

    private ExecutionContext createContext(HostServiceRegistry hsr) {
        return createContext(hsr,
            "property.read", "property.write", "permission.check",
            "routing.decide", "state.read", "state.write", "channel.resolve");
    }

    private ExecutionContext createContext(HostServiceRegistry hsr, String... caps) {
        SoftMMU mmu = new SoftMMU();
        // Map the standard I/O buffers (input 0x7000, output 0x8000)
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
            VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
            VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        
        ExecutionContext ctx = new ExecutionContext()
            .executionId("test")
            .fuelBudget(10000)
            .softMMU(mmu)
            .serviceRegistry(hsr)
            .allowedCapabilities(caps);
        ctx.initialize();
        return ctx;
    }

    private HostServiceRegistry createConfiguredHsr() {
        return new HostServiceRegistry();
    }

    /** Host context that serves a single value for every property read. */
    private static HostContext propertyContext(String value) {
        return new HostContext() {
            @Override public String readProperty(int scope, int propId) {
                return value;
            }
        };
    }

    @Test
    void builtinServicesRegistered() {
        HostServiceRegistry hsr = new HostServiceRegistry();

        assertNotNull(hsr.getService(HostServiceRegistry.PROPERTY_READ));
        assertNotNull(hsr.getService(HostServiceRegistry.PROPERTY_WRITE));
        assertNotNull(hsr.getService(HostServiceRegistry.PERMISSION_CHECK));
        assertNotNull(hsr.getService(HostServiceRegistry.ROUTE_DECISION));
        assertNotNull(hsr.getService(HostServiceRegistry.STATE_READ));
        assertNotNull(hsr.getService(HostServiceRegistry.STATE_WRITE));
    }

    @Test
    void serviceNotFoundReturnsUnsupported() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);

        // Unknown service ids are recoverable: R0 = 5 (UNSUPPORTED), no exception.
        ServiceResult result = hsr.invoke(ctx, 0xFFFF, 0, 0, 0);
        assertEquals(ServiceResult.Status.UNSUPPORTED, result.status());
    }

    @Test
    void capabilityCheck() {
        // Capabilities are now per-execution (IFLOW-001), enforced via ExecutionContext.
        ExecutionContext ctx = createContext(new HostServiceRegistry());
        assertTrue(ctx.hasCapability("property.read"));
        assertTrue(ctx.hasCapability("property.write"));
        assertTrue(ctx.hasCapability("permission.check"));
        assertTrue(ctx.hasCapability("routing.decide"));
        assertTrue(ctx.hasCapability("state.read"));
        assertTrue(ctx.hasCapability("state.write"));
        assertFalse(ctx.hasCapability("unknown.cap"));
    }

    @Test
    void serviceDeniedWithoutCapability() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        // Context only allowed routing.decide -> property.read must be denied.
        ExecutionContext ctx = createContext(hsr, "routing.decide");

        assertThrows(VmException.class, () ->
            hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0));
    }

    @Test
    void serviceAllowedWithCapability() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.read");
        ctx.hostContext(propertyContext("test-value"));

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0);
        assertNotNull(result);
    }

    @Test
    void propertyReadServiceRequiresCapability() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);

        // Should work since property.read is declared
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0);
        // No host value for the property -> NOT_FOUND
        assertEquals(ServiceResult.Status.NOT_FOUND, result.status());
        assertNotNull(result);
    }

    @Test
    void routeDecisionServiceRecordsJournal() {
        HostServiceRegistry hsr = createConfiguredHsr();
        ExecutionContext ctx = createContext(hsr);

        // options = decision code
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, HostServiceRegistry.DECISION_DROP);

        assertEquals(ServiceResult.Status.OK, result.status());
        ModificationJournal journal = ctx.journal();
        assertEquals(1, journal.size());
        assertEquals(ModificationJournal.OpType.CANCEL_MESSAGE, journal.entries().get(0).type);
    }

    @Test
    void routeDecisionChannelRedirectRecordsRedirect() {
        HostServiceRegistry hsr = createConfiguredHsr();
        ExecutionContext ctx = createContext(hsr);

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, HostServiceRegistry.DECISION_CHAN_REDIR);

        assertEquals(ServiceResult.Status.OK, result.status());
        ModificationJournal journal = ctx.journal();
        assertEquals(1, journal.size());
        assertEquals(ModificationJournal.OpType.REDIRECT_CHANNEL, journal.entries().get(0).type);
    }

    @Test
    void serviceResultStatuses() {
        assertTrue(ServiceResult.ok(1).isSuccess());
        assertTrue(ServiceResult.partial(1, 2).isSuccess());
        assertFalse(ServiceResult.notFound().isSuccess());
        assertFalse(ServiceResult.denied().isSuccess());
        assertFalse(ServiceResult.invalidArgument("").isSuccess());
        assertFalse(ServiceResult.bufferTooSmall(10).isSuccess());
        assertFalse(ServiceResult.unsupported().isSuccess());
        assertFalse(ServiceResult.conflict().isSuccess());
        assertFalse(ServiceResult.hostFailure("").isSuccess());
    }

    @Test
    void serviceResultFields() {
        ServiceResult r = ServiceResult.ok(2, 3);
        assertEquals(ServiceResult.Status.OK, r.status());
        assertEquals(2, r.bytesTransferred()); // R3
        assertEquals(3, r.result());           // R4
    }

    @Test
    void statusCodesMatchAbi() {
        // Numeric status codes are part of the ABI (written to R0) and must be stable.
        assertEquals(0, ServiceResult.Status.OK.code());
        assertEquals(1, ServiceResult.Status.NOT_FOUND.code());
        assertEquals(2, ServiceResult.Status.DENIED.code());
        assertEquals(3, ServiceResult.Status.INVALID_ARGUMENT.code());
        assertEquals(4, ServiceResult.Status.BUFFER_TOO_SMALL.code());
        assertEquals(5, ServiceResult.Status.UNSUPPORTED.code());
        assertEquals(6, ServiceResult.Status.CONFLICT.code());
        assertEquals(7, ServiceResult.Status.HOST_FAILURE.code());
        assertEquals(8, ServiceResult.Status.PARTIAL.code());
    }

    @Test
    void propertyReadDecodesScopeAndPropIdFromOptions() {
        int[] captured = new int[2];
        HostContext hc = new HostContext() {
            @Override public String readProperty(int scope, int propId) {
                captured[0] = scope;
                captured[1] = propId;
                return "test-value";
            }
        };
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.read");
        ctx.hostContext(hc);

        // options = (scope << 8) | propId ; propId must be only the low 8 bits.
        int options = (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_CONTENT;
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ,
            ctx.outputBufferVAddr(), 64, options);

        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals(HostServiceRegistry.SCOPE_MESSAGE, captured[0]);
        assertEquals(HostServiceRegistry.PROP_CONTENT, captured[1]);
        assertEquals("test-value".length(), result.bytesTransferred());
    }

    @Test
    void permissionCheckReturnsScalarResultInR4() {
        HostServiceRegistry hsr = new HostServiceRegistry()
            .permissionChecker((actor, perm) -> "allowed".equals(perm));
        ExecutionContext ctx = createContext(hsr, "permission.check");

        byte[] args = "Alice\0allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.inputBufferVAddr(), args, 0, args.length);

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PERMISSION_CHECK,
            ctx.inputBufferVAddr(), args.length, 0);

        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals(1, result.result());                     // allowed -> R4
        assertEquals(args.length, result.bytesTransferred()); // bytes read -> R3
    }

    @Test
    void bufferTooSmallReportsRequiredSizeInLength() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.read");
        ctx.hostContext(propertyContext("0123456789"));

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ,
            ctx.outputBufferVAddr(), 4, 0);

        assertEquals(ServiceResult.Status.BUFFER_TOO_SMALL, result.status());
        assertEquals(10, result.bytesTransferred()); // required size -> R3
    }

    @Test
    void stateReadAndWriteUseHostContext() {
        java.util.Map<Integer, Object> state = new java.util.HashMap<>();
        HostContext hc = new HostContext() {
            @Override public Object readState(int key) { return state.get(key); }
            @Override public void writeState(int key, Object value) { state.put(key, value); }
        };
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "state.read", "state.write");
        ctx.hostContext(hc);

        // STATE_WRITE: key in options, value read from the buffer.
        byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), value, 0, value.length);
        ServiceResult w = hsr.invoke(ctx, HostServiceRegistry.STATE_WRITE,
            ctx.outputBufferVAddr(), value.length, 7);
        assertEquals(ServiceResult.Status.OK, w.status());
        assertEquals("hello", state.get(7));

        // STATE_READ: writes the stored value back into the buffer.
        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.STATE_READ,
            ctx.outputBufferVAddr(), 64, 7);
        assertEquals(ServiceResult.Status.OK, r.status());
        byte[] out = new byte[5];
        ctx.softMMU().readBlock(ctx.outputBufferVAddr(), out, 0, out.length);
        assertEquals("hello", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void channelResolveUsesHostContext() {
        HostContext hc = new HostContext() {
            @Override public String resolveChannel(String name) {
                return "chat".equals(name) ? "chat" : null;
            }
        };
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "channel.resolve");
        ctx.hostContext(hc);

        byte[] name = "chat".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), name, 0, name.length);
        ServiceResult ok = hsr.invoke(ctx, HostServiceRegistry.CHANNEL_RESOLVE,
            ctx.outputBufferVAddr(), name.length, 0);
        assertEquals(ServiceResult.Status.OK, ok.status());

        // Unknown channel -> NOT_FOUND (the host returns null).
        byte[] unknown = "nope".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), unknown, 0, unknown.length);
        ServiceResult miss = hsr.invoke(ctx, HostServiceRegistry.CHANNEL_RESOLVE,
            ctx.outputBufferVAddr(), unknown.length, 0);
        assertEquals(ServiceResult.Status.NOT_FOUND, miss.status());
    }

    @Test
    void hostTransfersGoThroughTheDmaNotSoftMMU() {
        // Regression guard (D17): a HOST service must move bytes through the
        // execution's DMA. We wire a DMA over a *second* MMU; if the code bypassed
        // it and wrote ctx.softMMU() directly, the value would land in contextMmu.
        HostServiceRegistry hsr = new HostServiceRegistry();
        SoftMMU contextMmu = new SoftMMU();
        SoftMMU dmaMmu = new SoftMMU();
        for (SoftMMU mmu : new SoftMMU[]{contextMmu, dmaMmu}) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
                VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
                VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        ExecutionContext ctx = new ExecutionContext()
            .executionId("dma")
            .fuelBudget(10000)
            .softMMU(contextMmu)
            .dmaController(new VmDmaController(dmaMmu))
            .serviceRegistry(hsr)
            .allowedCapabilities(new String[]{"property.read"});
        ctx.initialize();
        ctx.hostContext(propertyContext("via-dma"));

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ,
            ctx.outputBufferVAddr(), 64, 0);

        assertEquals(ServiceResult.Status.OK, result.status());
        byte[] viaDma = dmaMmu.getRegion(ctx.outputBufferVAddr(), 7);
        assertEquals("via-dma", new String(viaDma, StandardCharsets.UTF_8));
        assertEquals(0, contextMmu.readByte(ctx.outputBufferVAddr()),
            "the transfer must not touch the context MMU directly");
    }

    @Test
    void propertyWriteConsumesTheOutputBufferThroughTheDma() {
        // AUD-020 (D19): 0x8000 is a real output region. The guest writes its
        // transformed text there and publishes it by pointing R2/R3 at it; the
        // host consumes it through the DMA (never through ctx.softMMU() directly),
        // which is why the buffer must not be auto-read on HALT.
        java.util.List<String> written = new java.util.ArrayList<>();
        HostContext hc = new HostContext() {
            @Override public boolean writeProperty(int scope, int propId, String value) {
                written.add(scope + ":" + propId + "=" + value);
                return true;
            }
        };

        HostServiceRegistry hsr = new HostServiceRegistry();
        SoftMMU contextMmu = new SoftMMU();
        SoftMMU dmaMmu = new SoftMMU();
        for (SoftMMU mmu : new SoftMMU[]{contextMmu, dmaMmu}) {
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.INPUT_BASE),
                VmMemoryMap.INPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
            mmu.getPageTable().mapPage(VmMemoryMap.pageOf(VmMemoryMap.OUTPUT_BASE),
                VmMemoryMap.OUTPUT_BASE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        }
        ExecutionContext ctx = new ExecutionContext()
            .executionId("output")
            .fuelBudget(10000)
            .softMMU(contextMmu)
            .dmaController(new VmDmaController(dmaMmu))
            .serviceRegistry(hsr)
            .allowedCapabilities(new String[]{"property.write"});
        ctx.initialize();
        ctx.hostContext(hc);

        // Simulate the guest writing the transformed text into the output buffer.
        byte[] text = "transformado".getBytes(StandardCharsets.UTF_8);
        dmaMmu.writeBlock(ctx.outputBufferVAddr(), text, 0, text.length);

        int options = (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_CONTENT;
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_WRITE,
            ctx.outputBufferVAddr(), text.length, options);

        assertEquals(ServiceResult.Status.OK, result.status());
        assertEquals(text.length, result.bytesTransferred());
        assertEquals(java.util.List.of(HostServiceRegistry.SCOPE_MESSAGE + ":" +
                HostServiceRegistry.PROP_CONTENT + "=transformado"), written,
            "the output buffer must be consumed through the DMA bus path");
    }

    @Test
    void propertyWriteUnsupportedPairIsReported() {
        // AUD-028: when the host refuses a (scope, propId) pair, the service must
        // report UNSUPPORTED instead of silently dropping the write.
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.write");
        ctx.hostContext(new HostContext() {
            @Override public boolean writeProperty(int scope, int propId, String value) {
                return false;
            }
        });

        byte[] value = "x".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), value, 0, value.length);
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_WRITE,
            ctx.outputBufferVAddr(), value.length,
            (HostServiceRegistry.SCOPE_EMITTER << 8) | HostServiceRegistry.PROP_ACTOR_NAME);

        assertEquals(ServiceResult.Status.UNSUPPORTED, result.status());
    }
}