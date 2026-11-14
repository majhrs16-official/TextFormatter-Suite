package me.majhrs16.suite.api.sync;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramGrantTest {

    private static final String SHA = "a".repeat(64);

    @Test
    void rejectsInvalidIdentityAndQuotas() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant("not-a-hash", Set.of(), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant("A".repeat(64), Set.of(), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of(), false, 0, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of(), false, 60, 0));
    }

    @Test
    void rejectsWildcardsThatAreNotATrailingSegment() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of("sync.*.create"), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of("sync.discord*"), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of("*"), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of("sync..*"), false, 60, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> new ProgramGrant(SHA, Set.of("sync discord.*"), false, 60, 1024));
    }

    @Test
    void exactCapabilityOnlyMatchesItself() {
        ProgramGrant grant = new ProgramGrant(SHA,
                Set.of("sync.discord.channel.create"), false, 60, 1024);

        assertTrue(grant.allows("sync.discord.channel.create"));
        assertFalse(grant.allows("sync.discord.channel.delete"));
        assertFalse(grant.allows("sync.discord.channel"));
        assertFalse(grant.allows(null));
        assertFalse(grant.allows(""));
    }

    @Test
    void trailingWildcardCoversOneOrMoreSegments() {
        ProgramGrant grant = new ProgramGrant(SHA, Set.of("sync.discord.*"),
                false, 60, 1024);

        assertTrue(grant.allows("sync.discord.channel.create"));
        assertTrue(grant.allows("sync.discord.channel.permission.overwrite"));
        assertFalse(grant.allows("sync.discord"),
                "the wildcard must not match the prefix alone");
        assertFalse(grant.allows("sync.discordx.channel.create"));
        assertFalse(grant.allows("sync.telegram.channel.create"));
    }

    @Test
    void wildcardMatchingIsExposedForTheStoreValidation() {
        assertTrue(ProgramGrant.matches("module.textformatter.*",
                "module.textformatter.channel.set"));
        assertFalse(ProgramGrant.matches("module.textformatter.*",
                "module.textformatter"));
        assertThrows(IllegalArgumentException.class,
                () -> ProgramGrant.matches("sync.*.create", "sync.discord.create"));
    }

    @Test
    void destructiveAndModerationRequireTheExplicitFlag() {
        ProgramGrant readWrite = new ProgramGrant(SHA,
                Set.of("sync.discord.*"), false, 60, 1024);
        ProgramGrant full = new ProgramGrant(SHA,
                Set.of("sync.discord.*"), true, 60, 1024);

        assertTrue(readWrite.permits(ActionKind.READ));
        assertTrue(readWrite.permits(ActionKind.WRITE));
        assertFalse(readWrite.permits(ActionKind.DESTRUCTIVE));
        assertFalse(readWrite.permits(ActionKind.MODERATION));

        assertTrue(full.permits(ActionKind.DESTRUCTIVE));
        assertTrue(full.permits(ActionKind.MODERATION));
    }
}
