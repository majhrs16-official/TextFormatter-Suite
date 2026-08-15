package me.majhrs16.cht.core.message;

import me.majhrs16.cht.core.language.Language;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultJsonCodecTest {

    private static final Actor SENDER = new Actor(
        java.util.UUID.randomUUID(), "Majhrs", Actor.ActorKind.PLAYER, Language.ES, null);

    @Test
    void roundTripsFullMessage() {
        Message original = Message.builder()
            .type(MessageType.CHAT)
            .sender(SENDER)
            .direction(Direction.others())
            .messages(new Formats(
                new String[] { "hola", "mundo" },
                new String[] { "<gray>%ct_messages%</gray>", "<blue>%ct_messages%</blue>" }))
            .toolTips(new Formats(new String[] { "tip" }, new String[0]))
            .sounds("entity.experience_orb.pickup;1.0;1.0")
            .langSource(Language.ES)
            .langTarget(Language.EN)
            .colorMode(ColorMode.BY_PERMISSION)
            .translate(true)
            .build();

        DefaultJsonCodec codec = new DefaultJsonCodec();
        String json = codec.write(original);
        Message decoded = codec.read(json);

        assertNotNull(decoded);
        assertEquals(MessageType.CHAT, decoded.type());
        assertEquals("Majhrs", decoded.sender().name());
        assertEquals(Actor.ActorKind.PLAYER, decoded.sender().kind());
        assertEquals(Direction.Kind.OTHERS, decoded.direction().kind());
        assertEquals(2, decoded.messages().size());
        assertEquals("hola", decoded.texts()[0]);
        assertEquals("<gray>%ct_messages%</gray>", decoded.messages().format(0));
        assertEquals(1, decoded.toolTips().size());
        assertEquals(Language.ES, decoded.langSource());
        assertEquals(Language.EN, decoded.langTarget());
        assertTrue(decoded.shouldTranslate());
    }

    @Test
    void roundTripsSpecialCharacters() {
        Message original = Message.builder()
            .type(MessageType.CHAT)
            .sender(new Actor(null, "C\\'on\"sole", Actor.ActorKind.CONSOLE, Language.EN, null))
            .direction(Direction.console())
            .texts("con \"quotes\" and \\ backslash")
            .langSource(Language.EN)
            .build();

        DefaultJsonCodec codec = new DefaultJsonCodec();
        Message decoded = codec.read(codec.write(original));

        assertNotNull(decoded);
        assertEquals("con \"quotes\" and \\ backslash", decoded.text());
        assertEquals("C\\'on\"sole", decoded.sender().name());
    }

    @Test
    void jsonContainsExpectedShape() {
        Message original = Message.builder()
            .type(MessageType.CHAT)
            .sender(SENDER)
            .direction(Direction.others())
            .texts("hola")
            .langSource(Language.ES)
            .build();

        String json = new DefaultJsonCodec().write(original);
        assertTrue(json.contains("\"sender\":\"Majhrs\""));
        assertTrue(json.contains("\"direction\":\"OTHERS\""));
        assertTrue(json.contains("\"texts\":[\"hola\"]"));
        assertTrue(json.contains("\"langSource\":\"es\""));
    }
}