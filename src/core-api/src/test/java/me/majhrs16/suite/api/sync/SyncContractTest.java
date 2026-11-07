package me.majhrs16.suite.api.sync;

import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.message.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncContractTest {

    @Test
    void lcmPublishesMetadataAndMessageDelivery() {
        SyncSurface surface = SyncSurface.lcm("discord", SemVer.of(1, 0, 0));

        assertTrue(surface.hasLeastCommonMultiple());
        assertEquals("discord", surface.backend());
        assertEquals(2, surface.actions().size());

        SyncActionSpec info = surface.action(SyncSurface.BACKEND_INFO).orElseThrow();
        assertEquals(ActionKind.READ, info.kind());
        assertTrue(info.idempotent());
        assertEquals(5_000, info.timeoutMillis());

        SyncActionSpec send = surface.action(SyncSurface.MESSAGE_SEND).orElseThrow();
        assertEquals(ActionKind.WRITE, send.kind());
        assertTrue(send.idempotent());
        assertEquals(15_000, send.timeoutMillis());
        assertEquals(List.of("message", "destination"),
                send.params().stream().map(SyncParamSpec::name).toList());
        assertEquals(SyncValueType.MESSAGE_REF, send.params().get(0).type());
        assertTrue(send.params().get(0).required());
        assertFalse(send.params().get(1).required());
    }

    @Test
    void rejectsDuplicateActionIds() {
        SyncActionSpec first = new SyncActionSpec(
                "channel.create", ActionKind.WRITE, List.of(), null, false, 1_000);
        SyncActionSpec duplicate = new SyncActionSpec(
                "channel.create", ActionKind.DESTRUCTIVE, List.of(), null, false, 1_000);

        assertThrows(IllegalArgumentException.class, () -> new SyncSurface(
                "discord", SemVer.of(1, 0, 0), Set.of(first, duplicate)));
    }

    @Test
    void rejectsInvalidSurfaceIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new SyncSurface(
                " ", SemVer.of(1, 0, 0), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SyncSurface(
                "discord.eu", SemVer.of(1, 0, 0), Set.of()));
    }

    @Test
    void rejectsInvalidActionSpecs() {
        assertThrows(IllegalArgumentException.class, () -> new SyncActionSpec(
                " ", ActionKind.READ, List.of(), null, true, 1_000));
        assertThrows(IllegalArgumentException.class, () -> new SyncActionSpec(
                "channel.create", ActionKind.WRITE, List.of(), null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyncActionSpec(
                "channel.create", ActionKind.WRITE,
                List.of(
                        SyncParamSpec.required("name", SyncValueType.STRING),
                        SyncParamSpec.optional("name", SyncValueType.STRING)),
                null, false, 1_000));
    }

    @Test
    void rejectsInvalidParamSpecs() {
        assertThrows(IllegalArgumentException.class, () -> new SyncParamSpec(
                "", SyncValueType.STRING, true, null));
        assertThrows(NullPointerException.class, () -> new SyncParamSpec(
                "name", null, true, null));
    }

    @Test
    void principalIdentifiesItsOrigin() {
        assertEquals("suite.test",
                new Principal.Official("suite.test").moduleId());
        String hash = "a".repeat(64);
        assertEquals(hash, new Principal.Program(hash).sha256());

        assertThrows(IllegalArgumentException.class,
                () -> new Principal.Official(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new Principal.Program("not-a-sha256"));
        assertThrows(IllegalArgumentException.class,
                () -> new Principal.Program("A".repeat(64)));
    }

    @Test
    void callBuildsTheCanonicalNameAndFreezesArguments() {
        Message message = Message.builder().text("hello").build();
        SyncCall call = new SyncCall(
                "discord", "channel.permission.overwrite",
                Map.of("message", message, "channel", "123"),
                new Principal.Official("suite.test"), "key", "reason");

        assertEquals("discord.channel.permission.overwrite", call.canonicalName());
        assertEquals(message, call.args().get("message"));
        assertThrows(UnsupportedOperationException.class,
                () -> call.args().put("extra", "value"));
        assertThrows(IllegalArgumentException.class, () -> new SyncCall(
                "discord.eu", "message.send", Map.of(), new Principal.Official("suite.test"),
                "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SyncCall(
                "discord", " ", Map.of(), new Principal.Official("suite.test"), "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SyncCall(
                "discord", "message send", Map.of(), new Principal.Official("suite.test"),
                "", ""));
    }

    @Test
    void resultsAreExplicitAndNeverSilent() {
        assertTrue(new SyncResult.Ok(null).isOk());
        assertFalse(new SyncResult.Denied(null).isOk());
        assertEquals("denied", new SyncResult.Denied(" ").reason());
        assertEquals("unsupported", new SyncResult.Unsupported(null).reason());
        assertEquals("invalid argument", new SyncResult.InvalidArgument(null).reason());
        assertEquals("not found", new SyncResult.NotFound(null).reason());
        assertEquals("conflict", new SyncResult.Conflict(null).reason());
        assertEquals("timeout", new SyncResult.Timeout(null).reason());
        assertEquals("host failure", new SyncResult.HostFailure(null).reason());
        assertThrows(IllegalArgumentException.class, () -> new SyncResult.RateLimited(-1));
        assertEquals(250, new SyncResult.RateLimited(250).retryAfterMillis());
    }
}
