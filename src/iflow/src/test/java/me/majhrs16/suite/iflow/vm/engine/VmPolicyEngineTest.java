package me.majhrs16.suite.iflow.vm.engine;

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
import me.majhrs16.suite.iflow.vm.VmConfigLoader;
import me.majhrs16.suite.iflow.vm.isa.Opcode;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.isa.VmException;
import me.majhrs16.suite.iflow.vm.service.HostServiceRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.Collections;
import java.nio.charset.StandardCharsets;

class VmPolicyEngineTest {

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

    private VmPolicyEngine createEngine() {
        return new VmPolicyEngine(createChannelRegistry(), createPermissionChecker(), createRateLimiter());
    }

    private Actor createActor(String name) {
        return Actor.unknown(name);
    }

    private Message createMessage(String text, String channel, Actor sender) {
        return new Message.Builder()
            .id(UUID.randomUUID())
            .text(text)
            .channel(channel)
            .direction(Direction.initiator())
            .sender(sender)
            .build();
    }

    @Test
    void passThroughProgramAllowsMessage() {
        VmPolicyEngine engine = createEngine();
        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("Hello world", "chat", sender);
        
        RouteOutcome outcome;
        try {
            outcome = engine.route(msg, recipient);
        } catch (VmException e) {
            throw new AssertionError("VM Exception: " + e.code() + " at IP=" + e.ip() + ": " + e.getMessage());
        }
        
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

@Test
    void dropDecisionFromProgram() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                // Read message.content into the input buffer (0x7000)
                // HOST ABI v2: R0=service, R1=options, R2=base, R3=length
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8), (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)0x10,  // options = (SCOPE_MESSAGE<<8)|PROP_CONTENT
                Opcode.LI, 2, (byte)0x70, (byte)0x00,  // R2 = 0x7000 (base vaddr)
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,  // R3 = 255 (max len)
                Opcode.HOST, (byte)0x00,               // HOST (2 bytes)
                // Check if first byte == 's' (0x73) → discard
                Opcode.LDB, 0, (byte)0x70, (byte)0x00,
                Opcode.LI, 1, (byte)0x00, (byte)0x73,
                Opcode.CMP, (0 << 4) | 1,
                Opcode.JZ, (byte)0x00, (byte)0x0B,    // jump to drop if equal (Z=1), offset=11
                // Continue
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_CONTINUE >> 8), (byte)(HostServiceRegistry.DECISION_CONTINUE & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT,
                // Drop
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_DROP >> 8), (byte)(HostServiceRegistry.DECISION_DROP & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("spam message", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.DROP, outcome.decision().target());
    }

    @Test
    void rejectDecisionFromProgram() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_REJECT >> 8), (byte)(HostServiceRegistry.DECISION_REJECT & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("hello world", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.REJECT, outcome.decision().target());
        assertTrue(outcome.decision().rejected());
    }

    @Test
    void continueDecisionFromProgram() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8), (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)0x10,  // options = (SCOPE_MESSAGE<<8)|PROP_CONTENT
                Opcode.LI, 2, (byte)0x70, (byte)0x00,  // base
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,  // length
                Opcode.HOST, (byte)0x00,
                Opcode.LDB, 0, (byte)0x70, (byte)0x00,
                Opcode.LI, 1, (byte)0x00, (byte)0x73,
                Opcode.CMP, (0 << 4) | 1,
                Opcode.JZ, (byte)0x00, (byte)0x0B,    // jump to drop if equal (Z=1), offset=11
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_CONTINUE >> 8), (byte)(HostServiceRegistry.DECISION_CONTINUE & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT,
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_DROP >> 8), (byte)(HostServiceRegistry.DECISION_DROP & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("hello world", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

    @Test
    void setProgramHotReload() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{ Opcode.HALT })
            .build();
        engine.setProgram(program);
        assertSame(program, engine.program());
    }

    @Test
    void setRulesThrowsUnsupported() {
        VmPolicyEngine engine = createEngine();
        assertThrows(UnsupportedOperationException.class, () -> engine.setRules(Collections.emptyList()));
    }

    @Test
    void rulesReturnsEmpty() {
        VmPolicyEngine engine = createEngine();
        assertTrue(engine.rules().isEmpty());
    }

    @Test
    void checkEmissionRateLimitUsesChannel() {
        VmPolicyEngine engine = createEngine();
        Actor sender = createActor("Alice");
        Message msg = createMessage("test", "chat", sender);
        assertTrue(engine.checkEmissionRateLimit(msg));
    }

    @Test
    void nanosUntilNextRateLimitWindow() {
        VmPolicyEngine engine = createEngine();
        Actor sender = createActor("Alice");
        Message msg = createMessage("test", "chat", sender);
        assertEquals(0, engine.nanosUntilNextRateLimitWindow(msg));
    }

    @Test
    void closeReleasesResources() {
        VmPolicyEngine engine = createEngine();
        engine.close();
        engine.close(); // double close should not throw
    }

    @Test
    void programWithPropertyWriteAndRouteDecision() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8), (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)0x10,  // options = (SCOPE_MESSAGE<<8)|PROP_CONTENT
                Opcode.LI, 2, (byte)0x70, (byte)0x00,  // base
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,  // length
                Opcode.HOST, (byte)0x00,
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_WRITE >> 8), (byte)(HostServiceRegistry.PROPERTY_WRITE & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)0x11,  // options = (SCOPE_MESSAGE<<8)|PROP_CHANNEL
                Opcode.LI, 2, (byte)0x70, (byte)0x00,  // base
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,  // length
                Opcode.HOST, (byte)0x00,
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)(HostServiceRegistry.DECISION_CONTINUE >> 8), (byte)(HostServiceRegistry.DECISION_CONTINUE & 0xFF),
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "property.write", "routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("test message", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

    @Test
    void vmExceptionDuringExecutionResultsInReject() {
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)0x00, (byte)0x0A,
                Opcode.LI, 1, (byte)0x00, (byte)0x00,
                Opcode.DIV, (0 << 4) | 1,
                Opcode.HALT
            })
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("test", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.REJECT, outcome.decision().target());
    }

    @Test
    void oversizedMessageDegradesInsteadOfThrowing() {
        VmPolicyEngine engine = createEngine();
        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        // The text alone fills the 4 KiB input page and the channel name pushes
        // the write offset to the very end. Before the clamp this produced a
        // negative length and an uncaught IndexOutOfBoundsException.
        Message msg = createMessage("x".repeat(5000), "c".repeat(300), sender);

        RouteOutcome outcome = engine.route(msg, recipient);

        assertNotNull(outcome);
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

    @Test
    void programCapabilitiesAreFilteredByTheHostGrant() {
        // A program may request whatever it wants, but only what the host grants
        // survives: capabilities are an allowlist, not a self-declaration.
        String[] effective = VmPolicyEngine.grantedCapabilities(
            new String[]{"routing.decide", "root.everything", "property.read"},
            java.util.Set.of("routing.decide", "property.read"));

        assertArrayEquals(new String[]{"routing.decide", "property.read"}, effective);
    }

    @Test
    void passThroughProgramDelivers() {
        // The default program (no policy.flow) must reach HALT and yield LOG,
        // not throw when it runs off the end of the code.
        VmPolicyEngine engine = createEngine();
        engine.setProgram(VmConfigLoader.loadProgram(java.nio.file.Paths.get("/nonexistent"), null));

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");
        Message msg = createMessage("hello", "chat", sender);
        RouteOutcome outcome = engine.route(msg, recipient);
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
        assertTrue(outcome.decision().delivered());
    }

    @Test
    void propertyReadServesMessageContentThroughHostContext() {
        // The program reads PROP_CONTENT through the host port and branches on the
        // first byte: 'p' -> LOG, anything else -> DROP. This proves the message
        // view is actually wired into HOST service calls (IFLOW-AUD-016).
        VmPolicyEngine engine = createEngine();
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8), (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, (byte)0x01, (byte)HostServiceRegistry.PROP_CONTENT, // options = SCOPE_MESSAGE|PROP_CONTENT
                Opcode.LI, 2, (byte)0x80, (byte)0x00,  // base = output buffer
                Opcode.LI, 3, (byte)0x00, (byte)0xFF,
                Opcode.HOST, (byte)0x00,
                Opcode.LDB, 0, (byte)0x80, (byte)0x00, // R0 = first content byte
                Opcode.LI, 1, (byte)0x00, (byte)0x70,  // 'p'
                Opcode.CMP, (0 << 4) | 1,
                Opcode.JNZ, (byte)0x00, (byte)0x0B,    // no match -> drop
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)0x00, (byte)HostServiceRegistry.DECISION_LOG,
                Opcode.HOST, (byte)0x00,
                Opcode.HALT,
                // drop:
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8), (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, (byte)0x00, (byte)HostServiceRegistry.DECISION_DROP,
                Opcode.HOST, (byte)0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "routing.decide"})
            .build();
        engine.setProgram(program);

        Actor sender = createActor("Alice");
        Actor recipient = createActor("Bob");

        Message ping = createMessage("ping", "chat", sender);
        assertEquals(PolicyTarget.LOG, engine.route(ping, recipient).decision().target());

        Message other = createMessage("xyz", "chat", sender);
        assertEquals(PolicyTarget.DROP, engine.route(other, recipient).decision().target());
    }

    // ===== AUD-028: PROPERTY_WRITE honours (scope, propId) =====

    /** Builds a program that writes one byte ('X') at 0x3000 and publishes it. */
    private static ProgramImage propertyWriteProgram(int scope, int propId) {
        return ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, 0x00, 0x58,                        // R0 = 'X'
                Opcode.STB, 0, 0x30, 0x00,                       // byte[0x3000] = R0
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_WRITE >> 8),
                              (byte)(HostServiceRegistry.PROPERTY_WRITE & 0xFF),
                Opcode.LI, 1, (byte)scope, (byte)propId,         // options = (scope << 8) | propId
                Opcode.LI, 2, 0x30, 0x00,                        // base = 0x3000
                Opcode.LI, 3, 0x00, 0x01,                        // length = 1
                Opcode.HOST, 0x00,
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8),
                              (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.DECISION_CONTINUE,
                Opcode.HOST, 0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.write", "routing.decide"})
            .build();
    }

    @Test
    void propertyWriteContentUpdatesText() {
        VmPolicyEngine engine = createEngine();
        engine.setProgram(propertyWriteProgram(
            HostServiceRegistry.SCOPE_MESSAGE, HostServiceRegistry.PROP_CONTENT));

        RouteOutcome outcome = engine.route(
            createMessage("original", "chat", createActor("Alice")), createActor("Bob"));

        assertEquals(PolicyTarget.LOG, outcome.decision().target());
        assertEquals("X", outcome.message().text());
        assertEquals("chat", outcome.message().channel(), "content must not change the channel");
    }

    @Test
    void propertyWriteChannelUpdatesChannel() {
        VmPolicyEngine engine = createEngine();
        engine.setProgram(propertyWriteProgram(
            HostServiceRegistry.SCOPE_MESSAGE, HostServiceRegistry.PROP_CHANNEL));

        RouteOutcome outcome = engine.route(
            createMessage("original", "chat", createActor("Alice")), createActor("Bob"));

        assertEquals(PolicyTarget.LOG, outcome.decision().target());
        assertEquals("X", outcome.message().channel());
        assertEquals("original", outcome.message().text(), "channel write must not change the text");
    }

    // ===== Lote 6 (D26): CHANNEL_POLICY reaches the channel registry =====

    @Test
    void channelPolicyServiceReadsNamedChannelThroughHostContext() {
        VmPolicyEngine engine = createEngine();
        // Writes "chat" at 0x8000, queries the channel's send policy, then checks
        // that exactly len("chat.send") = 9 bytes came back: CONTINUE when found,
        // DROP otherwise. Exercises MessageHostContext.readChannelProperty.
        ProgramImage program = ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, 0x00, 0x63,   // 'c'
                Opcode.STB, 0, (byte)0x80, 0x00,
                Opcode.LI, 0, 0x00, 0x68,   // 'h'
                Opcode.STB, 0, (byte)0x80, 0x01,
                Opcode.LI, 0, 0x00, 0x61,   // 'a'
                Opcode.STB, 0, (byte)0x80, 0x02,
                Opcode.LI, 0, 0x00, 0x74,   // 't'
                Opcode.STB, 0, (byte)0x80, 0x03,
                Opcode.LI, 0, (byte)(HostServiceRegistry.CHANNEL_POLICY >> 8),
                              (byte)(HostServiceRegistry.CHANNEL_POLICY & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.CHANNEL_POLICY_SEND,
                Opcode.LI, 2, (byte)0x80, 0x00,   // base = 0x8000
                Opcode.LI, 3, 0x00, 0x40,   // capacity = 64
                Opcode.HOST, 0x00,
                Opcode.LI, 0, 0x00, 0x09,   // R0 = 9 = len("chat.send")
                Opcode.CMP, (3 << 4) | 0,   // compare R3 (bytes) with R0
                Opcode.JZ, 0x00, 0x0B,      // equal -> continue (offset 11)
                // Not found -> DROP
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8),
                              (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.DECISION_DROP,
                Opcode.HOST, 0x00,
                Opcode.HALT,
                // Found -> CONTINUE
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8),
                              (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.DECISION_CONTINUE,
                Opcode.HOST, 0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"channel.resolve", "routing.decide"})
            .build();
        engine.setProgram(program);

        RouteOutcome outcome = engine.route(
            createMessage("Hello world", "chat", createActor("Alice")), createActor("Bob"));
        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

    // ===== Privilege model (design.md §14): kernel pages are supervisor-only =====

    /** Program that stores 'X' at {@code target} and then continues. */
    private static ProgramImage storeThenContinue(int target) {
        return ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, 0x00, 0x58,                        // R0 = 'X'
                Opcode.STB, 0, (byte)((target >> 8) & 0xFF), (byte)(target & 0xFF),
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8),
                              (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.DECISION_CONTINUE,
                Opcode.HOST, 0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"routing.decide"})
            .build();
    }

    @Test
    void programWritingConstantsKernelPageIsRejected() {
        VmPolicyEngine engine = createEngine();
        engine.setProgram(storeThenContinue(0x2000)); // constants: kernel-owned

        RouteOutcome outcome = engine.route(
            createMessage("original", "chat", createActor("Alice")), createActor("Bob"));

        assertEquals(PolicyTarget.REJECT, outcome.decision().target(),
            "a program must not write a kernel-owned page");
    }

    @Test
    void programWritingDataPageIsAllowed() {
        VmPolicyEngine engine = createEngine();
        engine.setProgram(storeThenContinue(0x3000)); // data: program-owned

        RouteOutcome outcome = engine.route(
            createMessage("original", "chat", createActor("Alice")), createActor("Bob"));

        assertEquals(PolicyTarget.LOG, outcome.decision().target());
    }

    // ===== Interpreter reuse (IFLOW-AUD-029) =====

    @Test
    void concurrentRoutesShareThePerProgramInterpreterSafely() throws Exception {
        // The engine holds one stateless interpreter per program; the per-execution
        // state (bus/MMU, registers, instruction window) lives in the pooled context.
        // This hammers the shared interpreter from many threads: every route must be
        // correct (a data-race in the fetch path would corrupt executions).
        VmPolicyEngine engine = createEngine();
        engine.setProgram(ProgramImage.builder()
            .entryPoint(0)
            .code(new byte[]{
                Opcode.LI, 0, (byte)(HostServiceRegistry.PROPERTY_READ >> 8),
                              (byte)(HostServiceRegistry.PROPERTY_READ & 0xFF),
                Opcode.LI, 1, 0x01, 0x10,                 // (SCOPE_MESSAGE<<8)|PROP_CONTENT
                Opcode.LI, 2, 0x70, 0x00,                 // base = 0x7000
                Opcode.LI, 3, 0x00, (byte)0xFF,           // length = 255
                Opcode.HOST, 0x00,
                Opcode.LI, 0, (byte)(HostServiceRegistry.ROUTE_DECISION >> 8),
                              (byte)(HostServiceRegistry.ROUTE_DECISION & 0xFF),
                Opcode.LI, 1, 0x00, (byte)HostServiceRegistry.DECISION_CONTINUE,
                Opcode.HOST, 0x00,
                Opcode.HALT
            })
            .capabilities(new String[]{"property.read", "routing.decide"})
            .build());

        int threads = 8;
        int perThread = 150;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    RouteOutcome o = engine.route(
                        createMessage("hi " + i, "chat", createActor("Alice")), createActor("Bob"));
                    if (o.decision().target() != PolicyTarget.LOG) {
                        return false;
                    }
                }
                return true;
            }));
        }
        for (java.util.concurrent.Future<Boolean> f : futures) {
            assertTrue(f.get(), "every concurrent route must be LOG");
        }
        pool.shutdownNow();
    }
}