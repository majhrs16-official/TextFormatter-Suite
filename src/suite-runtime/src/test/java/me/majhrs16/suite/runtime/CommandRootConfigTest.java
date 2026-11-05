package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.host.config.ConfigLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F4e/F8 gate: commands.yml root and aliases; base-name is retired. */
class CommandRootConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileUsesTheDefaultRootAndAlias() {
        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertTrue(loaded.isValid());
        assertEquals(CommandRoot.suite(), loaded.config());
    }

    @Test
    void loadsMultiSegmentRootsAndStringOrListAliases() throws Exception {
        write("""
                root: [suite, module]
                aliases:
                  - txf
                  - [sm, module]
                """);

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertTrue(loaded.isValid(), () -> String.join("; ", loaded.errors()));
        assertEquals(List.of("suite", "module"), loaded.config().segments());
        assertEquals(List.of(List.of("txf"), List.of("sm", "module")),
                loaded.config().aliases());
    }

    @Test
    void retiredBaseNameDoesNotSetTheRoot() throws Exception {
        write("""
                base-name: txf
                aliases: [tf]
                """);

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertTrue(loaded.isValid(), () -> String.join("; ", loaded.errors()));
        assertEquals(List.of("suite"), loaded.config().segments());
        assertEquals(List.of(List.of("tf")), loaded.config().aliases());
    }

    @Test
    void missingConfigVersionIsReported() throws Exception {
        Path file = dir.resolve("config/suite/commands.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "root: [suite]\n");

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertFalse(loaded.isValid());
        assertEquals(CommandRoot.suite(), loaded.config());
        assertTrue(loaded.errors().stream()
                .anyMatch(error -> error.contains("version de configuracion no identificada")));
    }

    @Test
    void invalidRootIsReportedAndFallsBackToTheDefault() throws Exception {
        write("root: [Suite]\n");

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertFalse(loaded.isValid());
        assertEquals(CommandRoot.suite(), loaded.config());
        assertFalse(loaded.errors().isEmpty());
    }

    @Test
    void aliasEqualToRootIsRejectedWithoutLegacyMigration() throws Exception {
        write("""
                root: [suite]
                aliases: [suite]
                """);

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertFalse(loaded.isValid());
        assertEquals(CommandRoot.suite(), loaded.config());
    }

    @Test
    void aliasFirstSegmentCollisionIsRejected() throws Exception {
        write("""
                root: [suite]
                aliases: [suite module]
                """);

        ConfigLoader.LoadResult<CommandRoot> loaded =
                CommandRootConfig.load(dir, FixtureModules.logger());

        assertFalse(loaded.isValid());
        assertEquals(CommandRoot.suite(), loaded.config());
    }

    private void write(String yaml) throws Exception {
        Path file = dir.resolve("config/suite/commands.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml + "\nconfig-version: 1.0\n");
    }
}
