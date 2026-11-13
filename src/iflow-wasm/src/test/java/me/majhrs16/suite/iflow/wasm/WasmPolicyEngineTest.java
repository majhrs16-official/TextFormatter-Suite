package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.iflow.TlvCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.sync.HostActionContext;
import me.majhrs16.suite.api.sync.Principal;
import me.majhrs16.suite.api.sync.SyncBridgeCodec;
import me.majhrs16.suite.api.sync.SyncCall;
import me.majhrs16.suite.api.sync.SyncResult;
import org.junit.jupiter.api.Test;

class WasmPolicyEngineTest {

    private static final Actor EMITTER = Actor.unknown("Alice").withLanguage(Language.EN);
    private static final Actor RECIPIENT = Actor.unknown("Bob").withLanguage(Language.ES);
    private static final ChannelRegistry CHANNELS = ChannelRegistry.builder()
        .register(Channel.builder("chat").build())
        .register(Channel.builder("private").build())
        .build();

    private final WasmModuleLoader loader = new WasmModuleLoader();
    private final WasmPolicyEngine engine = new WasmPolicyEngine(loader);

    @Test
    void observesDeliverDecision() {
        PolicyOutcome outcome = evaluate(decisionModule(PolicyAbiV3.ACTION_DELIVER));

        assertEquals(PolicyAction.DELIVER, outcome.action());
        assertEquals("hola", outcome.message().text());
    }

    @Test
    void observesCancelEventDecision() {
        PolicyOutcome outcome = evaluate(decisionModule(PolicyAbiV3.ACTION_CANCEL_EVENT));

        assertEquals(PolicyAction.CANCEL_EVENT, outcome.action());
    }

    @Test
    void secondDecisionProducesError() {
        WasmTestModule module = new WasmTestModule();
        int decisionType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{
            0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
            0x10, 0x00, 0x1a,
            0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
            0x10, 0x00, 0x1a,
            0x41, 0x00
        });

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.ERROR, outcome.action());
        assertEquals("decisión conflictiva", outcome.reason());
    }

    @Test
    void missingDecisionIsDeniedByDefault() {
        WasmTestModule module = new WasmTestModule();
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{0x41, 0x00});

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.ERROR, outcome.action());
        assertEquals("sin decisión", outcome.reason());
    }

    @Test
    void readPropertyWithoutOutputBufferReturnsBufferTooSmall() {
        WasmTestModule module = new WasmTestModule();
        int readType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.READ_PROPERTY, readType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{
            0x41, 0x01, // scope MESSAGE
            0x41, 0x01, // property CONTENT
            0x41, 0x00, // outPtr
            0x41, 0x00, // outCap
            0x10, 0x00  // call read_property
        });

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.ERROR, outcome.action());
        assertTrue(outcome.reason().contains("estado "
            + PolicyAbiV3.STATUS_BUFFER_TOO_SMALL), outcome.reason());
    }

    @Test
    void writePropertyFromGuestMemoryUpdatesMessage() {
        WasmTestModule module = new WasmTestModule();
        int writeType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.WRITE_PROPERTY, writeType);
        int decisionType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.memory(1, 1);
        module.data(0, TlvCodec.encodeString("adios"));
        module.code(new byte[]{
            0x41, 0x01, // scope MESSAGE
            0x41, 0x01, // property CONTENT
            0x41, 0x00, // inPtr
            0x41, 0x0a, // inLen (TLV de "adios")
            0x10, 0x00, 0x1a, // write_property, drop
            0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
            0x10, 0x01, 0x1a, // decision DELIVER, drop
            0x41, 0x00
        });

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.DELIVER, outcome.action());
        assertEquals("adios", outcome.message().text());
    }

    @Test
    void dedicatedSetChannelImportIsRejected() {
        WasmTestModule module = new WasmTestModule();
        int setChannelType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, "set_channel", setChannelType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{0x41, 0x00});

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.ERROR, outcome.action());
        assertTrue(outcome.reason().contains("set_channel"), outcome.reason());
        assertFalse(PolicyAbiV3.imports().containsKey("iflow.set_channel"),
            "the dedicated set_channel import must not survive in the v3 ABI");
    }

    @Test
    void transformApplyWritesResultBackToGuestMemory() {
        WasmTestModule module = new WasmTestModule();
        int transformType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.TRANSFORM_APPLY, transformType);
        int writeType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.WRITE_PROPERTY, writeType);
        int decisionType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.memory(1, 1);
        module.data(0, "Hola %name%".getBytes(StandardCharsets.UTF_8));
        module.code(new byte[]{
            0x41, 0x00, // kind RESOLVE_PLACEHOLDERS
            0x41, 0x00, // inPtr
            0x41, 0x0b, // inLen
            0x41, (byte) 0xC0, 0x00, // outPtr 64 (sleb)
            0x41, (byte) 0xC0, 0x00, // outCap 64
            0x10, 0x00, 0x1a, // transform_apply, drop
            0x41, 0x01, // scope MESSAGE
            0x41, 0x01, // property CONTENT
            0x41, (byte) 0xC0, 0x00, // inPtr 64
            0x41, 0x0d, // inLen 13 (TLV de "Hola Bob")
            0x10, 0x01, 0x1a, // write_property, drop
            0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
            0x10, 0x02, 0x1a, // decision DELIVER, drop
            0x41, 0x00
        });

        PolicyOutcome outcome = evaluate(module);

        assertEquals(PolicyAction.DELIVER, outcome.action());
        assertEquals("Hola Bob", outcome.message().text());
    }

    @Test
    void syncInvokeAppliesTheChannelMutationReturnedByTheBus() {
        byte[] request = SyncBridgeCodec.encodeInvokeRequest(
            new SyncBridgeCodec.InvokeRequest("textformatter", "channel.set",
                java.util.Map.of("channel", "private")));

        WasmTestModule module = new WasmTestModule();
        int syncType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.SYNC_INVOKE, syncType);
        int decisionType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.memory(1, 1);
        module.data(0, request);
        module.code(concat(
            new byte[]{
                0x41, 0x00,                 // inPtr
                0x41}, WasmTestModule.sleb(request.length), // inLen
            new byte[]{
                0x41}, WasmTestModule.sleb(256), // outPtr
            new byte[]{
                0x41}, WasmTestModule.sleb(256), // outCap
            new byte[]{
                0x10, 0x00, 0x1a,           // sync_invoke, drop
                0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
                0x10, 0x01, 0x1a,           // decision DELIVER, drop
                0x41, 0x00}));

        engine.syncBus(() -> fakeBus((call, context) -> new SyncResult.Ok(
            context.message().withChannel((String) call.args().get("channel")))));
        PolicyOutcome outcome = evaluate(module,
            new Principal.Program("a".repeat(64)));

        assertEquals(PolicyAction.DELIVER, outcome.action());
        assertEquals("private", outcome.message().channel());
    }

    @Test
    void syncInvokeWithoutBusIsUnsupported() {
        PolicySession session = new PolicySession(request());
        SyncResult result = session.syncInvoke(new SyncBridgeCodec.InvokeRequest(
            "discord", "channel.create", java.util.Map.of()));

        assertTrue(result instanceof SyncResult.Unsupported, result.toString());
        PolicyException surface = org.junit.jupiter.api.Assertions.assertThrows(
            PolicyException.class, () -> session.syncSurface(""));
        assertEquals(PolicyAbiV3.STATUS_UNSUPPORTED, surface.status());
    }

    @Test
    void syncInvokeRejectsASecondChannelMutation() {
        PolicySession session = new PolicySession(request(),
            new Principal.Program("b".repeat(64)), () -> fakeBus((call, context) ->
                new SyncResult.Ok(context.message().withChannel(
                    (String) call.args().get("channel")))));

        SyncResult first = session.syncInvoke(new SyncBridgeCodec.InvokeRequest(
            "textformatter", "channel.set", java.util.Map.of("channel", "private")));
        SyncResult second = session.syncInvoke(new SyncBridgeCodec.InvokeRequest(
            "textformatter", "channel.set", java.util.Map.of("channel", "chat")));

        assertEquals(new SyncResult.Ok(null), first);
        assertTrue(second instanceof SyncResult.Conflict, second.toString());
        assertEquals("private", session.message().channel());
    }

    @Test
    void syncStatusMapsEveryResult() {
        assertEquals(PolicyAbiV3.STATUS_OK, PolicyHost.syncStatus(new SyncResult.Ok(null)));
        assertEquals(PolicyAbiV3.STATUS_DENIED, PolicyHost.syncStatus(new SyncResult.Denied("no")));
        assertEquals(PolicyAbiV3.STATUS_UNSUPPORTED,
            PolicyHost.syncStatus(new SyncResult.Unsupported("no")));
        assertEquals(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            PolicyHost.syncStatus(new SyncResult.InvalidArgument("bad")));
        assertEquals(PolicyAbiV3.STATUS_NOT_FOUND,
            PolicyHost.syncStatus(new SyncResult.NotFound("absent")));
        assertEquals(PolicyAbiV3.STATUS_CONFLICT,
            PolicyHost.syncStatus(new SyncResult.Conflict("twice")));
        assertEquals(PolicyAbiV3.STATUS_RATE_LIMITED,
            PolicyHost.syncStatus(new SyncResult.RateLimited(10)));
        assertEquals(PolicyAbiV3.STATUS_TIMEOUT,
            PolicyHost.syncStatus(new SyncResult.Timeout("slow")));
        assertEquals(PolicyAbiV3.STATUS_HOST_FAILURE,
            PolicyHost.syncStatus(new SyncResult.HostFailure("boom")));
    }

    private static SyncBus fakeBus(
            java.util.function.BiFunction<SyncCall, HostActionContext, SyncResult> invokeHost) {
        return (SyncBus) java.lang.reflect.Proxy.newProxyInstance(
            SyncBus.class.getClassLoader(), new Class<?>[]{SyncBus.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "surfaces" -> java.util.Set.of();
                case "sinkNames" -> java.util.Set.of();
                case "invokeHost" -> invokeHost.apply((SyncCall) args[0],
                    (HostActionContext) args[1]);
                case "toString" -> "FakeSyncBus";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private PolicyOutcome evaluate(WasmTestModule module, Principal.Program principal) {
        return engine.evaluate(loader.load(module.build()), request(), principal);
    }

    private static PolicyRequest request() {
        Message message = Message.builder()
            .text("hola")
            .sender(EMITTER)
            .channel("chat")
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .build();
        PlaceholderResolver placeholders = new PlaceholderResolver() {
            @Override
            public String resolve(Actor actor, String input) {
                return input.replace("%name%", actor.name());
            }

            @Override
            public boolean available() {
                return true;
            }
        };
        return new PolicyRequest(message, EMITTER, RECIPIENT, CHANNELS,
            PermissionChecker.ALLOW_ALL, placeholders,
            text -> text.equals("bonjour") ? Language.FR : Language.EN);
    }

    private PolicyOutcome evaluate(WasmTestModule module) {
        return engine.evaluate(loader.load(module.build()), request());
    }

    private static WasmTestModule decisionModule(int action) {
        WasmTestModule module = new WasmTestModule();
        int decisionType = module.functionType(
            new int[]{WasmTestModule.I32, WasmTestModule.I32, WasmTestModule.I32,
                WasmTestModule.I32, WasmTestModule.I32},
            new int[]{WasmTestModule.I32});
        module.importFunction(PolicyAbiV3.IMPORT_MODULE, PolicyAbiV3.DECISION, decisionType);
        int decideType = module.functionType(new int[0], new int[]{WasmTestModule.I32});
        int decide = module.function(decideType);
        module.exportFunction(PolicyAbiV3.EXPORT_DECIDE, decide);
        module.code(new byte[]{
            0x41, (byte) action,
            0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0x41, 0x00,
            0x10, 0x00, 0x1a,
            0x41, 0x00
        });
        return module;
    }
}
