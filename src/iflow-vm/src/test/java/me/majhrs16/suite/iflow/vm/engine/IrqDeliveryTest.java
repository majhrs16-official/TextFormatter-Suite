package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.RateLimiter;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.irq.VmPic;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D33 end-to-end: the BIOS publishes an interrupt vector table in its NVRAM and
 * the runtime dispatches raised lines to the registered handlers as a hardware
 * CALL, transparently to the interrupted code.
 * <p>
 * The fixture {@code irq.flow} installs its own handler for line 5, enables and
 * raises it, then checks that the handler ran (draws {@code 'I'}) and that a
 * register the handler clobbered survived (draws {@code 'S'}, not {@code 'C'}).
 * </p>
 */
class IrqDeliveryTest {

    private ChannelRegistry channels() {
        Channel chat = Channel.builder("chat")
            .sendPermission("chat.send")
            .receivePermission("chat.receive")
            .rateLimitPerSecond(0)
            .build();
        return ChannelRegistry.builder().register(chat).build();
    }

    private VmPolicyEngine engine() {
        PermissionChecker allow = (actor, perm) -> true;
        return new VmPolicyEngine(channels(), allow, new RateLimiter());
    }

    private static Message message(Actor sender) {
        return new Message.Builder()
            .id(UUID.randomUUID())
            .text("hi")
            .channel("chat")
            .direction(Direction.initiator())
            .sender(sender)
            .build();
    }

    @Test
    void dispatchesARaisedLineToItsHandlerTransparently() throws Exception {
        ProgramImage program = ProgramImage.parse(
            Files.readAllBytes(fixture("tools/iflow-compiler/test/irq.flow")));

        VmPolicyEngine engine = engine();
        engine.setProgram(program);

        engine.route(message(Actor.unknown("Alice")), Actor.unknown("Bob"));

        // The BIOS ran first (it published the table and enabled the screen).
        assertTrue(engine.display().isEnabled(), "the active BIOS must run before main");

        // The handler ran and drew its marker in cell 1...
        assertEquals('I', engine.display().cellChar(1), "the IRQ handler must have run");
        // ...without disturbing main: the clobbered register was restored.
        assertEquals('S', engine.display().cellChar(0),
            "the interrupt must be transparent to the interrupted code");

        // The handler acknowledged the line; nothing is left in service.
        VmPic pic = engine.pic();
        assertEquals(0, pic.inServiceMask(), "the handler acknowledged its line");
        assertEquals(-1, pic.current(), "no line is being serviced");
        // The "message ready" I/O source is latched but stays masked (line 0).
        assertEquals(1, pic.pendingMask() & 0x0001, "message arrival raises IRQ 0");
        assertFalse(pic.isEnabled(VmPic.IRQ_MESSAGE), "IRQ 0 stays masked by default");
    }

    /** Resolves a repository-relative path from the repo root (settings.gradle). */
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
