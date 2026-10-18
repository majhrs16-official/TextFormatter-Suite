package me.majhrs16.suite.iflow.vm.engine;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.channel.RateLimiter;
import me.majhrs16.suite.iflow.vm.isa.ProgramImage;
import me.majhrs16.suite.iflow.vm.memory.VmMemoryMap;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D32 end-to-end: the firmware ROM is <em>active</em>. A firmware-on image
 * resets into the BIOS (its reset routine), which performs POST — enabling and
 * configuring the text screen — and then chains to user {@code main}.
 * <p>
 * The fixture {@code bios_screen.flow} {@code main} only draws on the screen; it
 * never configures it. Seeing the character therefore proves the BIOS enabled
 * the device before the program ran.
 * </p>
 */
class BiosBootTest {

    /** Value the firmware reset routine writes to NVRAM to mark a cold boot. */
    private static final int BIOS_BOOT_MAGIC = 0xB105;

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
    void biosConfiguresTheScreenAndChainsToMain() throws Exception {
        ProgramImage program = ProgramImage.parse(
            Files.readAllBytes(fixture("tools/iflow-compiler/test/bios_screen.flow")));

        VmPolicyEngine engine = engine();
        engine.setProgram(program);

        Actor sender = Actor.unknown("Alice");
        Actor recipient = Actor.unknown("Bob");
        engine.route(message(sender), recipient);

        // The BIOS enabled and configured the text screen before main drew on it.
        assertTrue(engine.display().isEnabled(), "BIOS must enable the text screen");
        assertEquals(VmMemoryMap.DISPLAY_DEFAULT_BASE, engine.display().baseAddress(),
            "BIOS must map the screen at the default base");
        assertEquals('B', engine.display().cellChar(0),
            "user main must run after the BIOS chain");

        // The BIOS recorded its cold boot in NVRAM.
        assertEquals(BIOS_BOOT_MAGIC, engine.nvram().readWord(VmMemoryMap.DEVICE_NVRAM_BASE),
            "BIOS must mark its cold boot in NVRAM");
    }

    @Test
    void warmBootKeepsTheScreen() throws Exception {
        ProgramImage program = ProgramImage.parse(
            Files.readAllBytes(fixture("tools/iflow-compiler/test/bios_screen.flow")));

        VmPolicyEngine engine = engine();
        engine.setProgram(program);

        Actor sender = Actor.unknown("Alice");
        Actor recipient = Actor.unknown("Bob");
        Message msg = message(sender);
        engine.route(msg, recipient);

        // A second message takes the warm path (NVRAM already marked): the BIOS
        // must not clear or reconfigure the machine, and the screen keeps what the
        // first execution drew.
        engine.route(msg, recipient);
        assertTrue(engine.display().isEnabled());
        assertEquals('B', engine.display().cellChar(0));
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
