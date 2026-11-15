package me.majhrs16.suite.iflow.vm.host.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.sync.SyncBridgeCodec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;
import me.majhrs16.suite.iflow.vm.isa.v3.Cpu;
import me.majhrs16.suite.iflow.vm.isa.v3.Csr;
import me.majhrs16.suite.iflow.vm.isa.v3.CsrFile;
import me.majhrs16.suite.iflow.vm.isa.v3.HostCall;
import me.majhrs16.suite.iflow.vm.isa.v3.HostResult;
import me.majhrs16.suite.iflow.vm.isa.v3.HostStatus;
import me.majhrs16.suite.iflow.vm.isa.v3.MemorySize;
import me.majhrs16.suite.iflow.vm.isa.v3.Register;
import me.majhrs16.suite.iflow.vm.memory.v3.MachineFixture;

class HostAbiTest {

    private static final int PIDENT = 7;
    private static final int MIDENT = 11;
    private static final int ARGUMENTS = 0x0208_0000;
    private static final int RESULT = 0x0208_1000;
    private static final int RESULT_CAPACITY = 64 * 1024;

    private MachineFixture fixture;
    private FakeBridge bridge;
    private HostAbi abi;

    @BeforeEach
    void setUp() {
        fixture = new MachineFixture();
        bridge = new FakeBridge();
        abi = new HostAbi(bridge);
    }

    @Test
    void cpuHostInstructionInstallsIdentityAndAbiResult() {
        int argumentLength = writeArguments(propertyArguments("world", "name"));
        bridge.properties.put("world.name", TlvCodec.stringValue("Arena"));
        Cpu cpu = new Cpu(0x0200_0000, fixture.memory, fixture.memory,
                fixture.clock::get, abi, fixture.memory, fixture.csrs);
        fixture.csrs.write(Csr.PIDENT, PIDENT, 0);
        fixture.csrs.write(Csr.MIDENT, MIDENT, 0);
        fixture.memory.physical().writeByte(0x0200_0000, 0xC0);
        fixture.memory.physical().writeByte(0x0200_0001, 0);
        fixture.memory.physical().writeByte(0x0200_0002, 0);
        cpu.registers().set(Register.R0, HostServiceId.PROPERTY_READ);
        cpu.registers().set(Register.R1, 0);
        cpu.registers().set(Register.R2, ARGUMENTS);
        cpu.registers().set(Register.R3, argumentLength);
        cpu.registers().set(Register.R4, RESULT);
        cpu.registers().set(Register.R5, RESULT_CAPACITY);

        assertTrue(cpu.step());

        assertEquals(HostStatus.OK, cpu.registers().get(Register.R0));
        HostResult result = new HostResult(cpu.registers().get(Register.R0),
                cpu.registers().get(Register.R1));
        assertEquals("Arena",
                resultMap(result).get(HostAbi.KEY_VALUE).asString());
    }

    @Test
    void unknownServiceIsUnsupportedEvenWithoutIdentity() {
        HostResult result = call(0x0900, mapOf(), CsrFile.NO_IDENTITY, CsrFile.NO_IDENTITY,
                RESULT_CAPACITY);

        assertEquals(HostStatus.UNSUPPORTED, result.status());
        assertEquals(0, result.resultLength());
    }

    @Test
    void missingIdentityAndCapabilityAreExplicit() {
        HostResult noProgram = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), CsrFile.NO_IDENTITY, MIDENT,
                RESULT_CAPACITY);
        assertEquals(HostStatus.NOT_READY, noProgram.status());

        HostResult malformedWithoutIdentity = call(HostServiceId.PROPERTY_READ,
                TlvCodec.stringValue("not a map"), CsrFile.NO_IDENTITY, MIDENT,
                RESULT_CAPACITY);
        assertEquals(HostStatus.NOT_READY, malformedWithoutIdentity.status());

        HostResult noMessage = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, CsrFile.NO_IDENTITY,
                RESULT_CAPACITY);
        assertEquals(HostStatus.NOT_READY, noMessage.status());

        bridge.messageVerified = false;
        HostResult unverified = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, unverified.status());

        bridge.messageVerified = true;
        bridge.capabilities.clear();
        HostResult denied = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, denied.status());

        HostResult noIdentity = call(HostServiceId.MESSAGE_ID, mapOf(),
                CsrFile.NO_IDENTITY, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.NOT_READY, noIdentity.status());
    }

    @Test
    void syncSurfaceDoesNotRequireMessageIdentity() {
        bridge.capabilities = Set.of(HostAbi.CAP_SYNC_SURFACE);
        bridge.surfaces = Optional.of(List.of(
                SyncSurface.lcm("discord", SemVer.of(1, 0, 0))));

        HostResult result = call(HostServiceId.SYNC_SURFACE, mapOf(), PIDENT,
                CsrFile.NO_IDENTITY, RESULT_CAPACITY);

        assertEquals(HostStatus.OK, result.status());
        List<SyncSurface> surfaces = SyncBridgeCodec.decodeSurfaceResponse(
                readResult(result));
        assertEquals(1, surfaces.size());
        assertEquals("discord", surfaces.get(0).backend());
    }

    @Test
    void propertyReadReportsPresenceAndTypedValue() {
        bridge.properties.put("world.name", TlvCodec.stringValue("Arena"));

        HostResult present = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, MIDENT, RESULT_CAPACITY);
        Map<String, TlvCodec.TlvValue> presentResult = resultMap(present);
        assertEquals(HostStatus.OK, present.status());
        assertTrue(presentResult.get(HostAbi.KEY_PRESENT).asBool());
        assertEquals("Arena", presentResult.get(HostAbi.KEY_VALUE).asString());

        HostResult absent = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "missing"), PIDENT, MIDENT, RESULT_CAPACITY);
        Map<String, TlvCodec.TlvValue> absentResult = resultMap(absent);
        assertFalse(absentResult.get(HostAbi.KEY_PRESENT).asBool());
        assertTrue(absentResult.get(HostAbi.KEY_VALUE).isNull());

        bridge.capabilities.remove(HostAbi.CAP_PROPERTY_READ);
        HostResult noCapability = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, noCapability.status());
    }

    @Test
    void propertyWriteExistsPermissionAndMessageIdUseTheBridge() {
        Map<String, TlvCodec.TlvValue> write = new LinkedHashMap<>();
        write.put(HostAbi.KEY_SCOPE, TlvCodec.stringValue("world"));
        write.put(HostAbi.KEY_PROPERTY, TlvCodec.stringValue("name"));
        write.put(HostAbi.KEY_VALUE, TlvCodec.stringValue("Nether"));
        assertEquals(HostStatus.OK, call(HostServiceId.PROPERTY_WRITE, write,
                PIDENT, MIDENT, RESULT_CAPACITY).status());
        assertEquals("Nether", bridge.lastWritten.asString());

        bridge.properties.put("world.name", TlvCodec.stringValue("Nether"));
        HostResult exists = call(HostServiceId.PROPERTY_EXISTS,
                propertyArguments("world", "name"), PIDENT, MIDENT, RESULT_CAPACITY);
        assertTrue(resultMap(exists).get(HostAbi.KEY_PRESENT).asBool());

        bridge.allowed = false;
        HostResult permission = call(HostServiceId.PERMISSION_CHECK,
                permissionArguments("actor", "channel", "message.send"), PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertFalse(resultMap(permission).get(HostAbi.KEY_ALLOWED).asBool());
        assertEquals("actor", bridge.lastPermissionActor);
        assertFalse(bridge.lastPermissionAllowed);

        bridge.allowed = true;
        HostResult blank = call(HostServiceId.PERMISSION_CHECK,
                permissionArguments("", "channel", ""), PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertFalse(resultMap(blank).get(HostAbi.KEY_ALLOWED).asBool());
        assertEquals("actor", bridge.lastPermissionActor);

        HostResult message = call(HostServiceId.MESSAGE_ID, mapOf(), PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertEquals("msg-1", resultMap(message).get(HostAbi.KEY_ID).asString());

        bridge.messageId = null;
        HostResult missing = call(HostServiceId.MESSAGE_ID, mapOf(), PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertEquals(HostStatus.NOT_FOUND, missing.status());
    }

    @Test
    void routeDecisionIsWrittenOncePerExecution() {
        Map<String, TlvCodec.TlvValue> arguments = new LinkedHashMap<>();
        arguments.put(HostAbi.KEY_TARGET, TlvCodec.i64Value(HostAbi.TARGET_DELIVER));
        arguments.put(HostAbi.KEY_REASON, TlvCodec.stringValue("ok"));

        HostResult first = call(HostServiceId.ROUTE_DECISION, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.OK, first.status());
        assertEquals(HostAbi.TARGET_DELIVER, bridge.lastTarget);
        assertEquals("ok", bridge.lastReason);

        HostResult second = call(HostServiceId.ROUTE_DECISION, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.CONFLICT, second.status());
        assertEquals(1, bridge.decisions);

        abi.beginExecution();
        arguments.put(HostAbi.KEY_TARGET, TlvCodec.i64Value(HostAbi.TARGET_ERROR));
        arguments.put(HostAbi.KEY_ERROR, TlvCodec.stringValue("boom"));
        assertEquals(HostStatus.OK, call(HostServiceId.ROUTE_DECISION, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY).status());
        assertEquals(HostAbi.TARGET_ERROR, bridge.lastTarget);
        assertEquals("boom", bridge.lastError);
    }

    @Test
    void transformApplyReturnsATypedOutput() {
        Map<String, TlvCodec.TlvValue> arguments = new LinkedHashMap<>();
        arguments.put(HostAbi.KEY_KIND, TlvCodec.stringValue("uppercase"));
        arguments.put(HostAbi.KEY_INPUT, TlvCodec.stringValue("hello"));

        HostResult result = call(HostServiceId.TRANSFORM_APPLY, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY);

        assertEquals(HostStatus.OK, result.status());
        assertEquals("HELLO",
                resultMap(result).get(HostAbi.KEY_OUTPUT).asString());
    }

    @Test
    void syncInvokeChecksConcreteCapabilityAndMapsSyncResult() {
        bridge.syncResult = new SyncResult.Ok("sent");
        Map<String, TlvCodec.TlvValue> arguments = syncArguments("discord",
                "message.send", mapOf());

        HostResult ok = call(HostServiceId.SYNC_INVOKE, arguments, PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertEquals(HostStatus.OK, ok.status());
        assertTrue(SyncBridgeCodec.decodeResult(readResult(ok)) instanceof SyncResult.Ok);

        bridge.syncResult = new SyncResult.Denied("no");
        HostResult denied = call(HostServiceId.SYNC_INVOKE, arguments, PIDENT, MIDENT,
                RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, denied.status());
        assertTrue(SyncBridgeCodec.decodeResult(readResult(denied)) instanceof SyncResult.Denied);

        bridge.capabilities.remove("sync.discord.message.send");
        HostResult missingCapability = call(HostServiceId.SYNC_INVOKE, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, missingCapability.status());
    }

    @Test
    void moduleActionUsesTheModuleCapabilityPrefix() {
        bridge.capabilities.add("module.textformatter.channel.set");
        Map<String, TlvCodec.TlvValue> arguments = syncArguments("textformatter",
                "channel.set", mapOf());

        assertEquals(HostStatus.OK, call(HostServiceId.SYNC_INVOKE, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY).status());

        bridge.capabilities.remove("module.textformatter.channel.set");
        assertEquals(HostStatus.DENIED, call(HostServiceId.SYNC_INVOKE, arguments,
                PIDENT, MIDENT, RESULT_CAPACITY).status());
    }

    @Test
    void syncSurfaceWithoutBridgeIsUnsupported() {
        bridge.capabilities = Set.of(HostAbi.CAP_SYNC_SURFACE);
        bridge.surfaces = Optional.empty();

        HostResult result = call(HostServiceId.SYNC_SURFACE, mapOf(), PIDENT,
                CsrFile.NO_IDENTITY, RESULT_CAPACITY);

        assertEquals(HostStatus.UNSUPPORTED, result.status());
    }

    @Test
    void insufficientCapacityReturnsLimitAndLeavesBufferUntouched() {
        bridge.properties.put("world.name", TlvCodec.stringValue("Arena"));
        writeByte(RESULT, 0xAA);

        HostResult result = call(HostServiceId.PROPERTY_READ,
                propertyArguments("world", "name"), PIDENT, MIDENT, 1);

        assertEquals(HostStatus.LIMIT, result.status());
        assertTrue(result.resultLength() > 1);
        assertEquals(0xAA, fixture.memory.read(RESULT, MemorySize.BYTE));
    }

    @Test
    void badResultAddressIsFault() {
        bridge.properties.put("world.name", TlvCodec.stringValue("Arena"));
        HostCall call = new HostCall(0, me.majhrs16.suite.iflow.vm.isa.v3.Privilege.SUPERVISOR,
                HostServiceId.PROPERTY_READ, 0, ARGUMENTS,
                writeArguments(propertyArguments("world", "name")),
                0xFFFF_FFFF, RESULT_CAPACITY, fixture.memory, PIDENT, MIDENT);

        HostResult result = abi.call(call);

        assertEquals(HostStatus.FAULT, result.status());
    }

    @Test
    void malformedArgumentsAndFaultsUseTheExplicitStatuses() {
        HostResult notMap = call(HostServiceId.PROPERTY_READ,
                TlvCodec.stringValue("not a map"), PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.INVALID_ARGUMENT, notMap.status());

        HostResult missingKey = call(HostServiceId.PROPERTY_READ,
                mapOf(), PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.INVALID_ARGUMENT, missingKey.status());

        HostCall badArgumentAddress = new HostCall(0,
                me.majhrs16.suite.iflow.vm.isa.v3.Privilege.SUPERVISOR,
                HostServiceId.PROPERTY_READ, 0, 0xFFFF_FFFF, 4,
                RESULT, RESULT_CAPACITY, fixture.memory, PIDENT, MIDENT);
        assertEquals(HostStatus.FAULT, abi.call(badArgumentAddress).status());
    }

    @Test
    void bridgeErrorsAreMappedWithoutLosingTheStatus() {
        bridge.writeError = HostServiceException.denied("denied");
        Map<String, TlvCodec.TlvValue> write = new LinkedHashMap<>();
        write.put(HostAbi.KEY_SCOPE, TlvCodec.stringValue("world"));
        write.put(HostAbi.KEY_PROPERTY, TlvCodec.stringValue("name"));
        write.put(HostAbi.KEY_VALUE, TlvCodec.nullValue());

        HostResult denied = call(HostServiceId.PROPERTY_WRITE, write,
                PIDENT, MIDENT, RESULT_CAPACITY);
        assertEquals(HostStatus.DENIED, denied.status());

        bridge.writeError = HostServiceException.notFound("missing");
        assertEquals(HostStatus.NOT_FOUND,
                call(HostServiceId.PROPERTY_WRITE, write, PIDENT, MIDENT,
                        RESULT_CAPACITY).status());

        bridge.transformError = HostServiceException.unsupported("no transform");
        Map<String, TlvCodec.TlvValue> transform = new LinkedHashMap<>();
        transform.put(HostAbi.KEY_KIND, TlvCodec.stringValue("none"));
        transform.put(HostAbi.KEY_INPUT, TlvCodec.stringValue("x"));
        assertEquals(HostStatus.UNSUPPORTED,
                call(HostServiceId.TRANSFORM_APPLY, transform, PIDENT, MIDENT,
                        RESULT_CAPACITY).status());
    }

    private HostResult call(int service, Map<String, TlvCodec.TlvValue> arguments,
                            int pident, int mident, int capacity) {
        return call(service, TlvCodec.mapValue(arguments), pident, mident, capacity);
    }

    private HostResult call(int service, TlvCodec.TlvValue arguments, int pident,
                            int mident, int capacity) {
        int length = writeArguments(arguments);
        HostCall call = new HostCall(0,
                me.majhrs16.suite.iflow.vm.isa.v3.Privilege.SUPERVISOR,
                service, 0, ARGUMENTS, length, RESULT, capacity,
                fixture.memory, pident, mident);
        HostResult result = abi.call(call);
        if (result.status() != HostStatus.LIMIT && result.status() != HostStatus.FAULT) {
            assertTrue(result.resultLength() <= capacity);
        }
        return result;
    }

    private int writeArguments(Map<String, TlvCodec.TlvValue> arguments) {
        return writeArguments(TlvCodec.mapValue(arguments));
    }

    private int writeArguments(TlvCodec.TlvValue arguments) {
        if (arguments == null) {
            return 0;
        }
        byte[] bytes = TlvCodec.encode(arguments.type(), arguments.payload());
        for (int index = 0; index < bytes.length; index++) {
            writeByte(ARGUMENTS + index, bytes[index]);
        }
        return bytes.length;
    }

    private void writeByte(int address, int value) {
        fixture.memory.write(address, MemorySize.BYTE, value);
    }

    private byte[] readResult(HostResult result) {
        byte[] bytes = new byte[result.resultLength()];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) fixture.memory.read(RESULT + index, MemorySize.BYTE);
        }
        return bytes;
    }

    private Map<String, TlvCodec.TlvValue> resultMap(HostResult result) {
        TlvCodec.TlvValue value = TlvCodec.decode(readResult(result));
        assertEquals(TlvCodec.TYPE_MAP, value.type());
        return value.asMap();
    }

    private static Map<String, TlvCodec.TlvValue> mapOf() {
        return Map.of();
    }

    private static Map<String, TlvCodec.TlvValue> propertyArguments(String scope,
                                                                    String property) {
        Map<String, TlvCodec.TlvValue> arguments = new LinkedHashMap<>();
        arguments.put(HostAbi.KEY_SCOPE, TlvCodec.stringValue(scope));
        arguments.put(HostAbi.KEY_PROPERTY, TlvCodec.stringValue(property));
        return arguments;
    }

    private static Map<String, TlvCodec.TlvValue> permissionArguments(String actor,
                                                                      String channel,
                                                                      String permission) {
        Map<String, TlvCodec.TlvValue> arguments = new LinkedHashMap<>();
        arguments.put(HostAbi.KEY_ACTOR, TlvCodec.stringValue(actor));
        arguments.put(HostAbi.KEY_CHANNEL, TlvCodec.stringValue(channel));
        arguments.put(HostAbi.KEY_PERMISSION, TlvCodec.stringValue(permission));
        return arguments;
    }

    private static Map<String, TlvCodec.TlvValue> syncArguments(String backend,
                                                                String action,
                                                                Map<String, TlvCodec.TlvValue> args) {
        Map<String, TlvCodec.TlvValue> arguments = new LinkedHashMap<>();
        arguments.put(HostAbi.KEY_BACKEND, TlvCodec.stringValue(backend));
        arguments.put(HostAbi.KEY_ACTION, TlvCodec.stringValue(action));
        arguments.put(HostAbi.KEY_ARGS, TlvCodec.mapValue(args));
        arguments.put(HostAbi.KEY_IDEMPOTENCY_KEY, TlvCodec.stringValue("key-1"));
        return arguments;
    }

    private static final class FakeBridge implements HostBridge {

        private boolean programVerified = true;
        private boolean messageVerified = true;
        private Set<String> capabilities = new java.util.HashSet<>(Set.of(
                HostAbi.CAP_PROPERTY_READ,
                HostAbi.CAP_PROPERTY_WRITE,
                HostAbi.CAP_PERMISSION_CHECK,
                HostAbi.CAP_ROUTE_DECISION,
                HostAbi.CAP_TRANSFORM_APPLY,
                HostAbi.CAP_MESSAGE_ID,
                HostAbi.CAP_SYNC_SURFACE,
                HostAbi.CAP_SYNC_INVOKE,
                "sync.discord.message.send"));
        private final Map<String, TlvCodec.TlvValue> properties = new LinkedHashMap<>();
        private TlvCodec.TlvValue lastWritten;
        private boolean allowed = true;
        private String lastPermissionActor;
        private boolean lastPermissionAllowed;
        private String messageId = "msg-1";
        private Optional<List<SyncSurface>> surfaces = Optional.empty();
        private SyncResult syncResult = new SyncResult.Ok(null);
        private HostServiceException writeError;
        private HostServiceException transformError;
        private int decisions;
        private int lastTarget;
        private String lastReason;
        private String lastError;

        @Override
        public boolean verifyProgram(int pident) {
            return programVerified;
        }

        @Override
        public boolean verifyMessage(int mident) {
            return messageVerified;
        }

        @Override
        public Set<String> capabilities(int pident) {
            return capabilities;
        }

        @Override
        public Optional<TlvCodec.TlvValue> propertyRead(int pident, int mident,
                                                       String scope, String property,
                                                       Map<String, TlvCodec.TlvValue> arguments) {
            return Optional.ofNullable(properties.get(scope + "." + property));
        }

        @Override
        public void propertyWrite(int pident, int mident, String scope, String property,
                                  TlvCodec.TlvValue value) throws HostServiceException {
            if (writeError != null) {
                throw writeError;
            }
            lastWritten = value;
            properties.put(scope + "." + property, value);
        }

        @Override
        public boolean propertyExists(int pident, int mident, String scope,
                                      String property) {
            return properties.containsKey(scope + "." + property);
        }

        @Override
        public boolean permissionCheck(int pident, int mident, String actor,
                                       String channel, String permission) {
            lastPermissionActor = actor;
            lastPermissionAllowed = allowed;
            return allowed;
        }

        @Override
        public void routeDecision(int pident, int mident, int target,
                                  String reason, String error) {
            decisions++;
            lastTarget = target;
            lastReason = reason;
            lastError = error;
        }

        @Override
        public TlvCodec.TlvValue transformApply(int pident, int mident, String kind,
                                                TlvCodec.TlvValue input)
                throws HostServiceException {
            if (transformError != null) {
                throw transformError;
            }
            return TlvCodec.stringValue(input.asString().toUpperCase(java.util.Locale.ROOT));
        }

        @Override
        public String messageId(int mident) {
            return messageId;
        }

        @Override
        public Optional<List<SyncSurface>> syncSurfaces(int pident) {
            return surfaces;
        }

        @Override
        public SyncResult syncInvoke(int pident, int mident, String backend,
                                     String action, TlvCodec.TlvValue args,
                                     String idempotencyKey, String reason) {
            return syncResult;
        }
    }
}
