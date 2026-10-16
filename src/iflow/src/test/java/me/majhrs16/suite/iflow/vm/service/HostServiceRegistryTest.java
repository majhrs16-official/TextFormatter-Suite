package me.majhrs16.suite.iflow.vm.service;

import me.majhrs16.suite.iflow.vm.execution.ExecutionContext;
import me.majhrs16.suite.iflow.vm.execution.ModificationJournal;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.memory.PageEntry;
import me.majhrs16.suite.iflow.vm.memory.SoftMMU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostServiceRegistryTest {

    private ExecutionContext createContext(HostServiceRegistry hsr) {
        return createContext(hsr,
            "property.read", "property.write", "permission.check",
            "routing.decide", "state.read", "state.write", "channel.resolve");
    }

    private ExecutionContext createContext(HostServiceRegistry hsr, String... caps) {
        SoftMMU mmu = new SoftMMU();
        // Map I/O pages (page 6 and 7)
        mmu.getPageTable().mapPage(6, 6 * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        mmu.getPageTable().mapPage(7, 7 * SoftMMU.PAGE_SIZE, PageEntry.PERM_READ | PageEntry.PERM_WRITE);
        
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
        return new HostServiceRegistry()
            .propertyReader((scope, propId) -> "test-value")
            .propertyWriter((scope, value) -> {})
            .stateReader(() -> java.util.Map.of())
            .stateWriter((key, value) -> {});
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
    void serviceNotFoundThrows() {
        HostServiceRegistry hsr = new HostServiceRegistry();
        ExecutionContext ctx = createContext(hsr);

        assertThrows(VmException.class, () -> hsr.invoke(ctx, 0xFFFF, 0, 0, 0, 0));
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
        HostServiceRegistry hsr = new HostServiceRegistry()
            .propertyReader((scope, propId) -> "test-value");
        // Context only allowed routing.decide -> property.read must be denied.
        ExecutionContext ctx = createContext(hsr, "routing.decide");

        assertThrows(VmException.class, () ->
            hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0, 0));
    }

    @Test
    void serviceAllowedWithCapability() {
        HostServiceRegistry hsr = new HostServiceRegistry()
            .propertyReader((scope, propId) -> "test-value");
        ExecutionContext ctx = createContext(hsr, "property.read");

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0, 0);
        assertNotNull(result);
    }

    @Test
    void propertyReadServiceRequiresCapability() {
        HostServiceRegistry hsr = new HostServiceRegistry()
            .propertyReader((scope, propId) -> "test-value");
        ExecutionContext ctx = createContext(hsr);

        // Should work since property.read is declared
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.PROPERTY_READ, ctx.outputBufferVAddr(), 0, 0, 0);
        // Will return NOT_FOUND since propertyReader returns null for unknown props
        assertNotNull(result);
    }

    @Test
    void routeDecisionServiceRecordsJournal() {
        HostServiceRegistry hsr = createConfiguredHsr();
        ExecutionContext ctx = createContext(hsr);

        // options = decision code
        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, 0, HostServiceRegistry.DECISION_DROP);

        assertEquals(ServiceResult.Status.OK, result.status());
        ModificationJournal journal = ctx.journal();
        assertEquals(1, journal.size());
        assertEquals(ModificationJournal.OpType.CANCEL_MESSAGE, journal.entries().get(0).type);
    }

    @Test
    void routeDecisionChannelRedirectRecordsRedirect() {
        HostServiceRegistry hsr = createConfiguredHsr();
        ExecutionContext ctx = createContext(hsr);

        ServiceResult result = hsr.invoke(ctx, HostServiceRegistry.ROUTE_DECISION, 0, 0, 0, HostServiceRegistry.DECISION_CHAN_REDIR);

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
        ServiceResult r = ServiceResult.ok(1, 2, 3);
        assertEquals(1, r.primaryResult());
        assertEquals(2, r.bytesTransferred());
        assertEquals(3, r.auxResult());
    }
}