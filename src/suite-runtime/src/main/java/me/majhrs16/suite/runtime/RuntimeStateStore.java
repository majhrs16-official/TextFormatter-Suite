package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Transient store for runtime module states.
 *
 * <p>This is deliberately separate from {@code library/status}. The package
 * database records what is installed; this file records what happened at
 * runtime (active, failed, restart required). It lives under the manager's
 * runtime directory, not under the package database, so a stale runtime state
 * can never be mistaken for an installation fact.</p>
 */
public final class RuntimeStateStore {

    private final Path statusFile;
    private final PluginLogger logger;

    public RuntimeStateStore(Path runtimeDir, PluginLogger logger) {
        this.statusFile = Objects.requireNonNull(runtimeDir, "runtimeDir")
                .resolve("modules.status");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the file backing this store. */
    public Path file() {
        return statusFile;
    }

    /**
     * Writes the current runtime states atomically.
     *
     * <p>Persistence is diagnostic, not authoritative. A write failure is
     * reported and ignored so it cannot turn a successful activation into a
     * failure.</p>
     *
     * @param states module id to state
     */
    public void save(Map<String, ModuleState> states) {
        Objects.requireNonNull(states, "states");
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, ModuleState> entry : states.entrySet()) {
            content.append(entry.getKey()).append(' ').append(entry.getValue().name()).append('\n');
        }

        try {
            Files.createDirectories(statusFile.getParent());
            Path tmp = statusFile.resolveSibling(statusFile.getFileName() + ".tmp");
            Files.writeString(tmp, content.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, statusFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            logger.warn("Cannot persist runtime module state to " + statusFile + ": " + failure.getMessage());
        }
    }

    /**
     * Reads the last persisted runtime states.
     *
     * @return module id to state; empty when the file is missing or malformed
     */
    public Map<String, ModuleState> load() {
        if (!Files.isRegularFile(statusFile)) {
            return Map.of();
        }
        try {
            Map<String, ModuleState> states = new LinkedHashMap<>();
            for (String line : Files.readAllLines(statusFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) {
                    continue;
                }
                try {
                    states.put(parts[0], ModuleState.valueOf(parts[1]));
                } catch (IllegalArgumentException ignored) {
                    // Unknown state: keep the file readable instead of failing startup.
                }
            }
            return states;
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + statusFile, failure);
        }
    }
}
