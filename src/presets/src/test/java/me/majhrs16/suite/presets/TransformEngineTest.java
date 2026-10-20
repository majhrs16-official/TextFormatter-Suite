package me.majhrs16.suite.presets;

import me.majhrs16.suite.api.message.Message;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformEngineTest {

    private final TransformEngine engine =
        new TransformEngine(null, null, null, (actor, permission) -> true);

    private Message message() {
        return Message.builder()
            .text("hola")
            .channel("chat.global")
            .build();
    }

    @Test
    void evaluatesSandboxedConditions() {
        Message message = message();

        assertTrue(engine.evaluateCondition("#content == 'hola'", message, null, null, actor -> true));
        assertFalse(engine.evaluateCondition("#content == 'bye'", message, null, null, actor -> true));
        assertTrue(engine.evaluateCondition("#channel == 'chat.global'", message, null, null, actor -> true));
    }

    @Test
    void appliesStringTransformToMessageText() {
        Message transformed = engine.applyTransform(
            "#content + '!'", message(), null, null, actor -> true
        );

        assertEquals("hola!", transformed.text());
        assertEquals("chat.global", transformed.channel());
    }

    @Test
    void invalidExpressionsLeaveTheMessageUntouchedAndConditionFalse() {
        Message original = message();

        Message transformed = engine.applyTransform(
            "this is not valid SpEL", original, null, null, actor -> true
        );

        assertEquals(original.text(), transformed.text());
        assertFalse(engine.evaluateCondition(
            "this is not valid SpEL", original, null, null, actor -> true
        ));
    }
}
