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
}