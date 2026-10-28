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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HostServiceRegistryTest {

    private ExecutionContext createContext(HostServiceRegistry hsr) {
        return createContext(hsr,
            "property.read", "property.write", "permission.check",
            "routing.decide", "state.read", "state.write", "channel.resolve",
            "message.clone", "message.id");
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
            @Override public Optional<String> readProperty(int scope, int propId) {
                return Optional.ofNullable(value);
            }
        };
    }

    @Test
    void builtinServicesRegistered() {
        HostServiceRegistry hsr = new HostServiceRegistry();

        assertTrue(hsr.getService(HostServiceRegistry.PROPERTY_READ).isPresent());
        assertTrue(hsr.getService(HostServiceRegistry.PROPERTY_WRITE).isPresent());
        assertTrue(hsr.getService(HostServiceRegistry.PERMISSION_CHECK).isPresent());
        assertTrue(hsr.getService(HostServiceRegistry.ROUTE_DECISION).isPresent());
        assertTrue(hsr.getService(HostServiceRegistry.STATE_READ).isPresent());
        assertTrue(hsr.getService(HostServiceRegistry.STATE_WRITE).isPresent());
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
    void unknownRouteDecisionFailsClosed() {
        // IFLOW-AUD-034: an out-of-range decision code must reject, not deliver.
        HostServiceRegistry hsr = createConfiguredHsr();
        ExecutionContext ctx = createContext(hsr);

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, 200);

        assertEquals(ServiceResult.Status.INVALID_ARGUMENT, result.status());
        ModificationJournal journal = ctx.journal();
        assertEquals(1, journal.size());
        assertEquals(ModificationJournal.OpType.CANCEL_MESSAGE, journal.entries().get(0).type);
        assertEquals("route:REJECT", journal.entries().get(0).detail);
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
            @Override public Optional<String> readProperty(int scope, int propId) {
                captured[0] = scope;
                captured[1] = propId;
                return Optional.of("test-value");
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
            @Override public Optional<Object> readState(int key) { return Optional.ofNullable(state.get(key)); }
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
            @Override public Optional<String> resolveChannel(String name) {
                return "chat".equals(name) ? Optional.of("chat") : Optional.empty();
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

    @Test
    void propertyWriteRejectsUnknownColorMode() {
        // IFLOW-AUD-042: an invalid enum value must be INVALID_ARGUMENT, not an
        // exception that later surfaces as an opaque "VM internal error" REJECT.
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.write");
        ctx.hostContext(new HostContext() {
            @Override public boolean writeProperty(int scope, int propId, String value) {
                fail("an invalid colour mode must not reach the host");
                return false;
            }
        });

        byte[] value = "banana".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), value, 0, value.length);
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_WRITE,
            ctx.outputBufferVAddr(), value.length,
            (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_COLOR_MODE);

        assertEquals(ServiceResult.Status.INVALID_ARGUMENT, result.status());
    }

    @Test
    void transformApplyResolvesPlaceholders() {
        HostServiceRegistry hsr = new HostServiceRegistry().placeholderResolver(
            text -> text.replace("%player%", "Alice"));
        ExecutionContext ctx = createContext(hsr, "transform.apply");
        ctx.hostContext(propertyContext("hola %player%"));

        byte[] in = "hola %player%".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), in, 0, in.length);
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.TRANSFORM_APPLY,
            ctx.outputBufferVAddr(), in.length, HostServiceRegistry.TRANSFORM_RESOLVE_PLACEHOLDERS);

        assertEquals(ServiceResult.Status.OK, result.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), result.bytesTransferred());
        assertEquals("hola Alice", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void transformApplyDetectsLanguage() {
        HostServiceRegistry hsr = new HostServiceRegistry().translationDetector(text -> "en");
        ExecutionContext ctx = createContext(hsr, "transform.apply");

        byte[] in = "hello".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), in, 0, in.length);
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.TRANSFORM_APPLY,
            ctx.outputBufferVAddr(), in.length, HostServiceRegistry.TRANSFORM_DETECT_LANGUAGE);

        assertEquals(ServiceResult.Status.OK, result.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), result.bytesTransferred());
        assertEquals("en", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void transformApplyIsUnsupportedWithoutHook() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "transform.apply");
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.TRANSFORM_APPLY,
            ctx.outputBufferVAddr(), 64, HostServiceRegistry.TRANSFORM_RESOLVE_PLACEHOLDERS);
        assertEquals(ServiceResult.Status.UNSUPPORTED, result.status());
    }

    @Test
    void propertyReadResolvesPlaceholdersInContent() {
        HostServiceRegistry hsr = new HostServiceRegistry().placeholderResolver(
            text -> text.replace("%player%", "Alice"));
        ExecutionContext ctx = createContext(hsr, "property.read");
        ctx.hostContext(propertyContext("hola %player%"));

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ,
            ctx.outputBufferVAddr(), 64,
            (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_CONTENT);

        assertEquals(ServiceResult.Status.OK, result.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), result.bytesTransferred());
        assertEquals("hola Alice", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void propertyReadAutoDetectsMissingSourceLanguage() {
        HostServiceRegistry hsr = new HostServiceRegistry().translationDetector(text -> "de");
        ExecutionContext ctx = createContext(hsr, "property.read");
        // Content is available; the source language is unset (null).
        ctx.hostContext(new HostContext() {
            @Override public Optional<String> readProperty(int scope, int propId) {
                return propId == HostServiceRegistry.PROP_CONTENT ? Optional.of("hallo") : Optional.empty();
            }
        });

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ,
            ctx.outputBufferVAddr(), 64,
            (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_LANG_SOURCE);

        assertEquals(ServiceResult.Status.OK, result.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), result.bytesTransferred());
        assertEquals("de", new String(out, StandardCharsets.UTF_8));
    }

    // ===== Lote 6 (D26): services previously declared but not registered =====

    @Test
    void everyDeclaredServiceIsRegistered() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        int[] ids = {
            HostServiceRegistry.PROPERTY_READ, HostServiceRegistry.PROPERTY_WRITE,
            HostServiceRegistry.PROPERTY_EXISTS, HostServiceRegistry.PERMISSION_CHECK,
            HostServiceRegistry.PERMISSION_QUERY, HostServiceRegistry.ROUTE_DECISION,
            HostServiceRegistry.ROUTE_REDIRECT, HostServiceRegistry.ROUTE_RATE_LIMIT,
            HostServiceRegistry.TRANSFORM_APPLY, HostServiceRegistry.STATE_READ,
            HostServiceRegistry.STATE_WRITE, HostServiceRegistry.CHANNEL_RESOLVE,
            HostServiceRegistry.CHANNEL_POLICY, HostServiceRegistry.MESSAGE_CLONE,
            HostServiceRegistry.MESSAGE_ID,
        };
        for (int id : ids) {
            assertTrue(hsr.hasService(id),
                "service 0x" + Integer.toHexString(id) + " must be registered");
        }
    }

    @Test
    void propertyExistsReportsPresenceInR4() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.read");
        ctx.hostContext(new HostContext() {
            @Override public Optional<String> readProperty(int scope, int propId) {
                return propId == HostServiceRegistry.PROP_CONTENT ? Optional.of("text") : Optional.empty();
            }
        });

        int present = (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_CONTENT;
        ServiceResult yes = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_EXISTS, 0, 0, present);
        assertEquals(ServiceResult.Status.OK, yes.status());
        assertEquals(0, yes.bytesTransferred());
        assertEquals(1, yes.result());

        int absent = (HostServiceRegistry.SCOPE_MESSAGE << 8) | HostServiceRegistry.PROP_LANG_TARGET;
        ServiceResult no = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_EXISTS, 0, 0, absent);
        assertEquals(ServiceResult.Status.OK, no.status());
        assertEquals(0, no.result());
    }

    @Test
    void permissionQueryReportsOutcomeInStatus() {
        HostServiceRegistry hsr = new HostServiceRegistry()
            .permissionChecker((actor, perm) -> "allowed".equals(perm));
        ExecutionContext ctx = createContext(hsr, "permission.check");

        byte[] granted = "Alice\0allowed".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.inputBufferVAddr(), granted, 0, granted.length);
        ServiceResult ok = hsr.invoke(ctx, HostServiceRegistry.PERMISSION_QUERY,
            ctx.inputBufferVAddr(), granted.length, 0);
        assertEquals(ServiceResult.Status.OK, ok.status());
        assertEquals(1, ok.result());                       // R4
        assertEquals(granted.length, ok.bytesTransferred()); // R3

        byte[] refused = "Alice\0nope".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.inputBufferVAddr(), refused, 0, refused.length);
        ServiceResult denied = hsr.invoke(ctx, HostServiceRegistry.PERMISSION_QUERY,
            ctx.inputBufferVAddr(), refused.length, 0);
        assertEquals(ServiceResult.Status.DENIED, denied.status());
        assertEquals(0, denied.result());
    }

    @Test
    void permissionQueryReportsHostFailureWithoutChecker() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "permission.check");
        byte[] args = "Alice\0node".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.inputBufferVAddr(), args, 0, args.length);
        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.PERMISSION_QUERY,
            ctx.inputBufferVAddr(), args.length, 0);
        assertEquals(ServiceResult.Status.HOST_FAILURE, r.status());
    }

    @Test
    void routeRedirectJournalsActorAndChannelTargets() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);

        byte[] actor = "Bob".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), actor, 0, actor.length);
        ServiceResult toActor = hsr.invoke(ctx, HostServiceRegistry.ROUTE_REDIRECT,
            ctx.outputBufferVAddr(), actor.length, HostServiceRegistry.REDIRECT_KIND_ACTOR);
        assertEquals(ServiceResult.Status.OK, toActor.status());
        assertEquals(HostServiceRegistry.REDIRECT_KIND_ACTOR, toActor.result());
        ModificationJournal.Entry actorEntry = ctx.journal().entries().get(0);
        assertEquals(ModificationJournal.OpType.REDIRECT_TARGET, actorEntry.type);
        assertEquals("Bob", actorEntry.value);

        byte[] chan = "lobby".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), chan, 0, chan.length);
        ServiceResult toChannel = hsr.invoke(ctx, HostServiceRegistry.ROUTE_REDIRECT,
            ctx.outputBufferVAddr(), chan.length, HostServiceRegistry.REDIRECT_KIND_CHANNEL);
        assertEquals(ServiceResult.Status.OK, toChannel.status());
        ModificationJournal.Entry chanEntry = ctx.journal().entries().get(1);
        assertEquals(ModificationJournal.OpType.REDIRECT_CHANNEL, chanEntry.type);
        assertEquals("lobby", chanEntry.value);
    }

    @Test
    void routeRedirectRejectsEmptyTarget() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.ROUTE_REDIRECT, 0, 0,
            HostServiceRegistry.REDIRECT_KIND_ACTOR);
        assertEquals(ServiceResult.Status.INVALID_ARGUMENT, r.status());
    }

    @Test
    void routeRateLimitRequiresLimiterAndJournalsBackoff() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        byte[] key = "chat".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), key, 0, key.length);

        // Without a limiter the host does not offer rate limiting.
        ServiceResult noLimiter = hsr.invoke(ctx, HostServiceRegistry.ROUTE_RATE_LIMIT,
            ctx.outputBufferVAddr(), key.length, 0);
        assertEquals(ServiceResult.Status.UNSUPPORTED, noLimiter.status());

        me.majhrs16.suite.api.iflow.RateLimiter limiter =
            new me.majhrs16.suite.api.iflow.RateLimiter();
        try {
            HostServiceRegistry withLimiter = new HostServiceRegistry().rateLimiter(limiter);
            limiter.tryAcquire("chat", 1); // exhaust the single ticket of the window
            ServiceResult limited = withLimiter.invoke(ctx, HostServiceRegistry.ROUTE_RATE_LIMIT,
                ctx.outputBufferVAddr(), key.length, 0);
            assertEquals(ServiceResult.Status.OK, limited.status());
            assertTrue(limited.result() > 0, "an exhausted window must yield a positive backoff");
            ModificationJournal.Entry entry = ctx.journal().entries().get(0);
            assertEquals(ModificationJournal.OpType.RATE_LIMIT_BACKOFF, entry.type);
        } finally {
            limiter.close();
        }
    }

    @Test
    void channelPolicyReadsNamedChannel() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "channel.resolve");
        ctx.hostContext(new HostContext() {
            @Override public Optional<String> readChannelProperty(String channelName, int propId) {
                if (!"lobby".equals(channelName)) {
                    return Optional.empty();
                }
                return propId == HostServiceRegistry.PROP_CHAN_SEND_POL ? Optional.of("lobby.chat") : Optional.empty();
            }
        });

        // The name is a C string; R3 is the buffer capacity (also used for output).
        byte[] name = "lobby".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), name, 0, name.length);
        ServiceResult ok = hsr.invoke(ctx, HostServiceRegistry.CHANNEL_POLICY,
            ctx.outputBufferVAddr(), 64, HostServiceRegistry.CHANNEL_POLICY_SEND);
        assertEquals(ServiceResult.Status.OK, ok.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), ok.bytesTransferred());
        assertEquals("lobby.chat", new String(out, StandardCharsets.UTF_8));

        // Unknown channel -> NOT_FOUND.
        byte[] unknown = "nope".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.outputBufferVAddr(), unknown, 0, unknown.length);
        ServiceResult miss = hsr.invoke(ctx, HostServiceRegistry.CHANNEL_POLICY,
            ctx.outputBufferVAddr(), 64, HostServiceRegistry.CHANNEL_POLICY_SEND);
        assertEquals(ServiceResult.Status.NOT_FOUND, miss.status());
    }

    @Test
    void messageIdWritesCurrentMessageId() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        ctx.messageId("msg-42");

        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.MESSAGE_ID,
            ctx.outputBufferVAddr(), 64, 0);
        assertEquals(ServiceResult.Status.OK, r.status());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), r.bytesTransferred());
        assertEquals("msg-42", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void messageIdNotFoundWhenAbsent() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.MESSAGE_ID,
            ctx.outputBufferVAddr(), 64, 0);
        assertEquals(ServiceResult.Status.NOT_FOUND, r.status());
    }

    @Test
    void messageCloneCopiesMessagePayloadThroughTheDma() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        byte[] payload = "hola mundo".getBytes(StandardCharsets.UTF_8);
        ctx.softMMU().writeBlock(ctx.inputBufferVAddr(), payload, 0, payload.length);

        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.MESSAGE_CLONE,
            ctx.outputBufferVAddr(), payload.length, 0);
        assertEquals(ServiceResult.Status.OK, r.status());
        assertEquals(payload.length, r.bytesTransferred());
        byte[] out = ctx.softMMU().getRegion(ctx.outputBufferVAddr(), payload.length);
        assertEquals("hola mundo", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void messageCloneRejectsOversizedRequest() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);
        ServiceResult r = hsr.invoke(ctx, HostServiceRegistry.MESSAGE_CLONE,
            ctx.outputBufferVAddr(), ctx.ioBufferSize() + 1, 0);
        assertEquals(ServiceResult.Status.INVALID_ARGUMENT, r.status());
    }

    @Test
    void messageServicesRequireTheirCapability() {
        // The message services are deny-by-default like the rest: without the
        // declared capability the registry denies the call (IRQ_HOST to the program).
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr, "property.read"); // sin message.*

        VmException id = assertThrows(VmException.class, () -> hsr.invoke(ctx,
            HostServiceRegistry.MESSAGE_ID, ctx.outputBufferVAddr(), 64, 0));
        assertEquals(VmException.Code.HOST_SERVICE_DENIED, id.code());

        VmException clone = assertThrows(VmException.class, () -> hsr.invoke(ctx,
            HostServiceRegistry.MESSAGE_CLONE, ctx.outputBufferVAddr(), 4, 0));
        assertEquals(VmException.Code.HOST_SERVICE_DENIED, clone.code());
    }
}