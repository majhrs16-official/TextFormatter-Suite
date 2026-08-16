package me.majhrs16.suite.textformatter.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelRegistryTest {

    @Test
    void resolvesExactPath() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(channel("chat"))
            .register(channel("chat.global"))
            .build();

        assertSame("chat.global", registry.resolve("chat.global").name());
        assertSame("chat", registry.resolve("chat").name());
    }

    @Test
    void fallsBackThroughAncestors() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(channel("chat"))
            .register(channel("chat.team"))
            .build();

        Channel resolved = registry.resolve("chat.team.blue");
        assertEquals("chat.team", resolved.name());
    }

    @Test
    void fallsBackToBareChatWhenNothingMatches() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(channel("chat"))
            .build();

        Channel resolved = registry.resolve("discord.global.pt");
        assertEquals("chat", resolved.name());
    }

    @Test
    void resolvesNullAndEmptyToChat() {
        ChannelRegistry registry = ChannelRegistry.builder().build();

        assertEquals("chat", registry.resolve(null).name());
        assertEquals("chat", registry.resolve("").name());
    }

    @Test
    void unknownPathYieldsSyntheticChatChannel() {
        ChannelRegistry registry = ChannelRegistry.builder().build();
        assertFalse(registry.has("chat"));
        Channel resolved = registry.resolve("anything");
        assertTrue(resolved.name().equals("chat"));
    }

    @Test
    void preservesInsertionOrder() {
        ChannelRegistry registry = ChannelRegistry.builder()
            .register(channel("b"))
            .register(channel("a"))
            .register(channel("c"))
            .build();

        assertEquals(java.util.List.of("b", "a", "c"), registry.paths());
    }

    private static Channel channel(String name) {
        return Channel.builder(name).build();
    }
}