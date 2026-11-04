package me.majhrs16.suite.iflow.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IflowConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileUsesFirewallAndFallback() {
        IflowConfig config = IflowConfig.load(dir, logger());

        assertEquals("iflow-firewall", config.engine());
        assertTrue(config.fallback());
    }

    @Test
    void loadsEngineAndNormalizesAliases() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: wasm
                fallback-on-unavailable: false
                """);

        IflowConfig config = IflowConfig.load(dir, logger());

        assertEquals("iflow-wasm", config.engine());
        assertFalse(config.fallback());
    }

    @Test
    void acceptsDefaultBackendAlias() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "default-backend: vm\n");

        assertEquals("iflow-vm", IflowConfig.load(dir, logger()).engine());
    }

    @Test
    void invalidYamlFallsBackToDefaults() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "engine: [unterminated\n");

        IflowConfig config = IflowConfig.load(dir, logger());

        assertEquals("iflow-firewall", config.engine());
        assertTrue(config.fallback());
    }

    @Test
    void normalizeKeepsUnknownNamesForExplicitFailure() {
        assertEquals("custom-backend", IflowConfig.normalize("custom-backend"));
        assertEquals("iflow-firewall", IflowConfig.normalize(null));
        assertEquals("iflow-firewall", IflowConfig.normalize(" "));
    }

    private static PluginLogger logger() {
        List<String> messages = new ArrayList<>();
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { messages.add(message); }
            @Override public void warn(String message, Object... args) { messages.add(message); }
            @Override public void error(String message, Object... args) { messages.add(message); }
            @Override public void error(String message, Throwable throwable) { messages.add(message); }
            @Override public void debug(String message, Object... args) { messages.add(message); }
        };
    }
}
