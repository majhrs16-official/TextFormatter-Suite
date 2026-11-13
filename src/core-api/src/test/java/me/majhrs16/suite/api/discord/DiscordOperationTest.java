package me.majhrs16.suite.api.discord;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordOperationTest {

    @Test
    void numericIdsAreValidatedBeforeAnyAdapterRuns() {
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.CreateChannel("not-a-number", "general", null));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.DeleteChannel("0", null));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.DeleteChannel(" ", null));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.DeleteMessage("42", "abc", null));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.AssignRole("7", "9", "12x", null));
    }

    @Test
    void banDeleteWindowIsBounded() {
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.BanMember("7", "9", -1, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DiscordOperation.BanMember("7", "9", 604_801, null));
        assertEquals(604_800,
            new DiscordOperation.BanMember("7", "9", 604_800, null).deleteMessageSeconds());
    }

    @Test
    void blankOptionalValuesBecomeNull() {
        assertNull(new DiscordOperation.CreateChannel("7", "general", " ").topic());
        assertNull(new DiscordOperation.DeleteMessage("42", "99", "").reason());
        assertNull(new DiscordOperation.AssignRole("7", "9", "11", "  ").reason());
    }

    @Test
    void resultDefaultsBlankReasonsAndCopiesTheValue() {
        assertEquals("not found", new DiscordResult.NotFound(" ").reason());
        assertEquals("forbidden", new DiscordResult.Forbidden(null).reason());
        assertTrue(new DiscordResult.Ok(null).value().isEmpty());
        assertEquals(Map.of("id", "c1"),
            new DiscordResult.Ok(Map.of("id", "c1")).value());
    }
}
