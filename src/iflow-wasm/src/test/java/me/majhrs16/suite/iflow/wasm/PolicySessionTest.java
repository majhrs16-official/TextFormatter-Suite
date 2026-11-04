package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.ColorMode;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class PolicySessionTest {

    private static final Actor EMITTER = new Actor(
        UUID.randomUUID(), "Alice", Actor.ActorKind.PLAYER, Language.EN, null);
    private static final Actor RECIPIENT = new Actor(
        UUID.randomUUID(), "Bob", Actor.ActorKind.PLAYER, Language.ES, null);

    private static final ChannelRegistry CHANNELS = ChannelRegistry.builder()
        .register(Channel.builder("chat")
            .sendPermission("chat.send")
            .receivePermission("chat.receive")
            .rateLimitPerSecond(5)
            .build())
        .register(Channel.builder("staff")
            .sendPermission("staff.send")
            .receivePermission("staff.receive")
            .build())
        .register(Channel.builder("private")
            .permission("private.use")
            .build())
        .build();

    private static final Set<String> ALLOWED = Set.of(
        "chat.send", "chat.receive", "staff.send", "private.use");

    private static final PermissionChecker PERMISSIONS = (actor, permission) ->
        (actor.name().equals("Alice") || actor.name().equals("Bob"))
            && ALLOWED.contains(permission);

    private static PolicySession session() {
        Message message = Message.builder()
            .text("hola")
            .sender(EMITTER)
            .direction(Direction.others())
            .channel("chat")
            .langSource(Language.EN)
            .langTarget(Language.ES)
            .colorMode(ColorMode.FORCE)
            .formatPapi(true)
            .translate(true)
            .build();
        PolicyRequest request = new PolicyRequest(
            message,
            EMITTER,
            RECIPIENT,
            CHANNELS,
            PERMISSIONS,
            new PlaceholderResolver() {
                @Override
                public String resolve(Actor actor, String input) {
                    return input.replace("%name%", actor.name());
                }

                @Override
                public boolean available() {
                    return true;
                }
            },
            text -> text.equals("bonjour") ? Language.FR : Language.EN);
        return new PolicySession(request);
    }

    @Test
    void readsMessageProperties() {
        PolicySession session = session();

        assertEquals("hola", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CONTENT).asString());
        assertEquals("chat", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CHANNEL).asString());
        assertEquals("OTHERS", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_DIRECTION).asString());
        assertEquals("CUSTOM", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_TYPE).asString());
        assertEquals("en", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_LANG_SOURCE).asString());
        assertEquals("es", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_LANG_TARGET).asString());
        assertEquals("FORCE", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_COLOR_MODE).asString());
        assertTrue(session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_FORMAT_PAPI).asBool());
        assertTrue(session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_TRANSLATE).asBool());
    }

    @Test
    void readsActorAndChannelProperties() {
        PolicySession session = session();

        assertEquals("Alice", session.readProperty(
            PolicyAbiV3.SCOPE_EMITTER, PolicyAbiV3.ACTOR_NAME).asString());
        assertEquals("Bob", session.readProperty(
            PolicyAbiV3.SCOPE_RECIPIENT, PolicyAbiV3.ACTOR_NAME).asString());
        assertEquals("en", session.readProperty(
            PolicyAbiV3.SCOPE_EMITTER, PolicyAbiV3.ACTOR_LANGUAGE).asString());
        assertEquals("es", session.readProperty(
            PolicyAbiV3.SCOPE_RECIPIENT, PolicyAbiV3.ACTOR_LANGUAGE).asString());
        assertEquals(EMITTER.uuid().toString(), session.readProperty(
            PolicyAbiV3.SCOPE_EMITTER, PolicyAbiV3.ACTOR_UUID).asString());

        assertEquals("chat", session.readProperty(
            PolicyAbiV3.SCOPE_CHANNEL, PolicyAbiV3.CHANNEL_NAME).asString());
        assertEquals(5L, session.readProperty(
            PolicyAbiV3.SCOPE_CHANNEL, PolicyAbiV3.CHANNEL_RATE).asI64());
        assertEquals("chat.send", session.readProperty(
            PolicyAbiV3.SCOPE_CHANNEL, PolicyAbiV3.CHANNEL_SEND_POLICY).asString());
        assertEquals("chat.receive", session.readProperty(
            PolicyAbiV3.SCOPE_CHANNEL, PolicyAbiV3.CHANNEL_RECEIVE_POLICY).asString());
    }

    @Test
    void writePropertyUpdatesJournalReadYourWrites() {
        PolicySession session = session();

        session.writeProperty(PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CONTENT,
            TlvCodec.stringValue("adios"));
        assertEquals("adios", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CONTENT).asString());

        session.writeProperty(PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_TRANSLATE,
            TlvCodec.boolValue(false));
        assertFalse(session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_TRANSLATE).asBool());

        session.writeProperty(PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_LANG_TARGET,
            TlvCodec.stringValue("fr"));
        assertEquals("fr", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_LANG_TARGET).asString());

        session.writeProperty(PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_COLOR_MODE,
            TlvCodec.stringValue("DISABLE"));
        assertEquals("DISABLE", session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_COLOR_MODE).asString());

        session.writeProperty(PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_FORMAT_PAPI,
            TlvCodec.boolValue(false));
        assertFalse(session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_FORMAT_PAPI).asBool());

        assertEquals("adios", session.message().text());
    }

    @Test
    void rejectsReadOnlyAndInvalidWrites() {
        PolicySession session = session();

        assertStatus(PolicyAbiV3.STATUS_DENIED, () -> session.writeProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CHANNEL,
            TlvCodec.stringValue("private")));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT, () -> session.writeProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CONTENT,
            TlvCodec.boolValue(true)));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT, () -> session.writeProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_LANG_TARGET,
            TlvCodec.stringValue("xx")));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT, () -> session.writeProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_COLOR_MODE,
            TlvCodec.stringValue("rainbow")));
        assertStatus(PolicyAbiV3.STATUS_DENIED, () -> session.writeProperty(
            PolicyAbiV3.SCOPE_EMITTER, PolicyAbiV3.ACTOR_NAME,
            TlvCodec.stringValue("Mallory")));
    }

    @Test
    void setChannelValidatesExistenceAndPermissions() {
        PolicySession session = session();

        session.setChannel("private");
        assertEquals("private", session.message().channel());

        assertStatus(PolicyAbiV3.STATUS_NOT_FOUND, () -> session.setChannel("missing"));
        assertStatus(PolicyAbiV3.STATUS_DENIED, () -> session.setChannel("staff"));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT, () -> session.setChannel(" "));
    }

    @Test
    void transformsPlaceholdersAndDetectsLanguage() {
        PolicySession session = session();

        assertEquals("Hola Bob", session.transform(
            PolicyAbiV3.TRANSFORM_RESOLVE_PLACEHOLDERS, "Hola %name%"));
        assertEquals("fr", session.transform(
            PolicyAbiV3.TRANSFORM_DETECT_LANGUAGE, "bonjour"));
        assertEquals("en", session.transform(
            PolicyAbiV3.TRANSFORM_DETECT_LANGUAGE, "hello"));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            () -> session.transform(99, "x"));
    }

    @Test
    void checksPermissionsForKnownAndUnknownActors() {
        PolicySession session = session();

        assertTrue(session.permissionCheck("Alice", "chat.send"));
        assertFalse(session.permissionCheck("Bob", "staff.receive"));
        assertFalse(session.permissionCheck("Carol", "chat.send"));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            () -> session.permissionCheck("", "chat.send"));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            () -> session.permissionCheck("Alice", ""));
    }

    @Test
    void readsChannelPolicy() {
        PolicySession session = session();

        assertEquals("chat.send", session.channelPolicy(
            "chat", PolicyAbiV3.CHANNEL_POLICY_SEND).asString());
        assertEquals("chat.receive", session.channelPolicy(
            "chat", PolicyAbiV3.CHANNEL_POLICY_RECEIVE).asString());
        assertEquals(5L, session.channelPolicy(
            "chat", PolicyAbiV3.CHANNEL_POLICY_RATE).asI64());
        assertStatus(PolicyAbiV3.STATUS_NOT_FOUND,
            () -> session.channelPolicy("missing", PolicyAbiV3.CHANNEL_POLICY_SEND));
        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            () -> session.channelPolicy("chat", 99));
    }

    @Test
    void decisionIsSingleAndDefaultsToError() {
        PolicySession session = session();
        assertEquals(PolicyAction.ERROR, session.outcome().action());
        assertEquals("sin decisión", session.outcome().reason());

        assertEquals(PolicyAbiV3.STATUS_OK, session.decide(
            PolicyAbiV3.ACTION_DELIVER, "", "ok"));
        PolicyOutcome outcome = session.outcome();
        assertEquals(PolicyAction.DELIVER, outcome.action());
        assertEquals("ok", outcome.reason());
        assertEquals("hola", outcome.message().text());

        assertEquals(PolicyAbiV3.STATUS_CONFLICT, session.decide(
            PolicyAbiV3.ACTION_CANCEL_EVENT, "", "late"));
        assertEquals(PolicyAction.ERROR, session.outcome().action());
        assertEquals("decisión conflictiva", session.outcome().reason());

        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT,
            () -> session.decide(99, "", "bad"));
    }

    @Test
    void logIsBoundedAndValidatesLevel() {
        PolicySession session = session();

        assertEquals(PolicyAbiV3.STATUS_OK, session.log(PolicyAbiV3.LOG_INFO, "hola"));
        assertEquals(1, session.logs().size());
        assertEquals(PolicyAbiV3.LOG_INFO, session.logs().get(0).level());

        assertStatus(PolicyAbiV3.STATUS_INVALID_ARGUMENT, () -> session.log(99, "x"));

        for (int i = 0; i < PolicySession.MAX_LOGS + 10; i++) {
            session.log(PolicyAbiV3.LOG_DEBUG, "x");
        }
        assertEquals(PolicySession.MAX_LOGS, session.logs().size());
    }

    @Test
    void absentChannelReadsAsNullAndChannelScopeIsNotFound() {
        Message message = Message.builder().text("x").sender(EMITTER).build();
        PolicySession session = new PolicySession(new PolicyRequest(
            message, EMITTER, RECIPIENT, CHANNELS, PERMISSIONS, null, null));

        assertTrue(session.readProperty(
            PolicyAbiV3.SCOPE_MESSAGE, PolicyAbiV3.MESSAGE_CHANNEL).isNull());
        assertNull(session.message().channel());
        assertStatus(PolicyAbiV3.STATUS_NOT_FOUND,
            () -> session.readProperty(PolicyAbiV3.SCOPE_CHANNEL, PolicyAbiV3.CHANNEL_NAME));
    }

    private static void assertStatus(int expected, Executable executable) {
        PolicyException error = assertThrows(PolicyException.class, executable);
        assertEquals(expected, error.status(), error.getMessage());
    }
}
