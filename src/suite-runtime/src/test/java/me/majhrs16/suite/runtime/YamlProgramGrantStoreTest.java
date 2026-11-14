package me.majhrs16.suite.runtime;

import me.majhrs16.suite.api.sync.ProgramGrant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlProgramGrantStoreTest {

    private static final String SHA = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    @TempDir
    Path dir;

    private Path grantsFile() {
        return dir.resolve("config/iflow/grants.yml");
    }

    private YamlProgramGrantStore load(String yaml) throws Exception {
        Files.createDirectories(grantsFile().getParent());
        Files.writeString(grantsFile(), yaml);
        return YamlProgramGrantStore.load(grantsFile(), FixtureModules.logger());
    }

    @Test
    void missingFileIsAValidEmptyStore() {
        YamlProgramGrantStore store =
                YamlProgramGrantStore.load(grantsFile(), FixtureModules.logger());

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void loadsCapabilitiesAndPerGrantQuotas() throws Exception {
        YamlProgramGrantStore store = load("""
                defaults:
                  calls-per-minute: 10
                  max-payload-bytes: 2048
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                      - module.textformatter.*
                    allow-destructive: true
                    calls-per-minute: 30
                  "%s":
                    capabilities:
                      - sync.discord.*
                config-version: 1.0
                """.formatted(SHA, OTHER));

        ProgramGrant first = store.find(SHA).orElseThrow();
        assertEquals(30, first.callsPerMinute());
        assertEquals(2048, first.maxPayloadBytes());
        assertTrue(first.allowDestructive());
        assertTrue(first.allows("sync.discord.channel.create"));
        assertTrue(first.allows("module.textformatter.channel.set"));

        ProgramGrant second = store.find(OTHER).orElseThrow();
        assertEquals(10, second.callsPerMinute(),
                "a grant without quotas inherits the defaults");
        assertFalse(second.allowDestructive());
        assertTrue(second.allows("sync.discord.channel.delete"));
    }

    @Test
    void unknownKeysDisableTheWholeStore() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                    unknown: true
                config-version: 1.0
                """.formatted(SHA));

        assertTrue(store.find(SHA).isEmpty(),
                "an ambiguous grant file must not partially apply");
    }

    @Test
    void duplicateGrantKeysAreRejected() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                  "%s":
                    capabilities:
                      - sync.discord.channel.delete
                config-version: 1.0
                """.formatted(SHA, SHA));

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void invalidCapabilityPatternDisablesTheStore() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.*.create
                config-version: 1.0
                """.formatted(SHA));

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void uppercaseOrMalformedHashIsRejected() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                config-version: 1.0
                """.formatted(SHA.toUpperCase()));

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void wrongConfigVersionDisablesTheStore() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                config-version: 9.9
                """.formatted(SHA));

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void malformedYamlDisablesTheStore() throws Exception {
        YamlProgramGrantStore store = load("grants: [unclosed\n");

        assertTrue(store.find(SHA).isEmpty());
    }

    @Test
    void reloadReplacesTheSnapshotAndAnAbsentFileClearsIt() throws Exception {
        YamlProgramGrantStore store = load("""
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.create
                config-version: 1.0
                """.formatted(SHA));
        assertTrue(store.find(SHA).isPresent());

        Files.writeString(grantsFile(), """
                grants:
                  "%s":
                    capabilities:
                      - sync.discord.channel.delete
                config-version: 1.0
                """.formatted(OTHER));
        store.reload();

        Optional<ProgramGrant> previous = store.find(SHA);
        assertTrue(previous.isEmpty(), "the old grant must disappear on reload");
        assertTrue(store.find(OTHER).isPresent());

        Files.delete(grantsFile());
        store.reload();
        assertTrue(store.find(OTHER).isEmpty(),
                "a deleted file clears the store instead of keeping stale grants");
    }
}
