package me.majhrs16.suite.iflow.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import me.majhrs16.suite.api.config.ConfigVersionException;
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
    void loadsCanonicalEngine() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: iflow-wasm
                fallback-on-unavailable: false
                config-version: 1.0
                """);

        IflowConfig config = IflowConfig.load(dir, logger());

        assertEquals("iflow-wasm", config.engine());
        assertFalse(config.fallback());
    }

    @Test
    void ignoresDefaultBackendAlias() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "default-backend: vm\nconfig-version: 1.0\n");

        assertEquals("iflow-firewall", IflowConfig.load(dir, logger()).engine());
    }

    @Test
    void ignoresFallbackAlias() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                engine: iflow-vm
                fallback: false
                config-version: 1.0
                """);

        assertTrue(IflowConfig.load(dir, logger()).fallback());
    }

    @Test
    void missingConfigVersionFailsExplicitly() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "engine: wasm\n");

        assertThrows(ConfigVersionException.class, () -> IflowConfig.load(dir, logger()));
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
        assertEquals("wasm", IflowConfig.normalize("wasm"));
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
