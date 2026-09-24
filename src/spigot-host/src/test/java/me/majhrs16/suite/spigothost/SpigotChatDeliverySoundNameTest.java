package me.majhrs16.suite.spigothost;

import org.bukkit.Sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Sound name normalization against the Bukkit registry (JVM only). */
class SpigotChatDeliverySoundNameTest {

    @Test
    void keyspacedNamesMapToEnumConstants() {
        assertEquals(Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            SpigotChatDelivery.resolve("entity.experience_orb.pickup"));
        assertEquals(Sound.BLOCK_NOTE_BLOCK_PLING,
            SpigotChatDelivery.resolve("block.note_block.pling"));
    }

    @Test
    void legacyUppercaseConstantsPassThrough() {
        assertEquals(Sound.BLOCK_NOTE_BLOCK_PLING,
            SpigotChatDelivery.resolve("BLOCK_NOTE_BLOCK_PLING"));
    }

    @Test
    void audioExtensionsAreStripped() {
        assertEquals(Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            SpigotChatDelivery.resolve("entity.experience_orb.pickup.mp3"));
        assertEquals(Sound.BLOCK_NOTE_BLOCK_PLING,
            SpigotChatDelivery.resolve("block-note-block-pling.ogg"));
    }

    @Test
    void unknownOrBlankNamesResolveToNull() {
        assertNull(SpigotChatDelivery.resolve("no.such.sound.here"));
        assertNull(SpigotChatDelivery.resolve(""));
        assertNull(SpigotChatDelivery.resolve(null));
    }
}
