package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.majhrs16.suite.api.config.ConfigVersion;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.sync.ProgramGrant;
import me.majhrs16.suite.api.sync.ProgramGrantStore;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Operator grants for third-party iFlow programs, loaded from
 * {@code config/iflow/grants.yml}.
 *
 * <p>The file is indexed by the lowercase SHA-256 of the program image. A
 * missing file is a valid empty store (every program is denied). A malformed
 * file, an unknown key, a duplicate hash or an invalid capability pattern
 * disables the whole store until the operator fixes it: a partially applied
 * grant file would make the effective authority ambiguous, and ambiguity is a
 * denial.</p>
 *
 * <pre>
 * defaults:
 *   calls-per-minute: 60
 *   max-payload-bytes: 65536
 * grants:
 *   "&lt;sha256&gt;":
 *     capabilities:
 *       - sync.discord.channel.create
 *       - module.textformatter.*
 *     allow-destructive: false
 *     calls-per-minute: 30
 *     max-payload-bytes: 4096
 * config-version: 1.0
 * </pre>
 */
final class YamlProgramGrantStore implements ProgramGrantStore {

    private static final String GRANTS = "grants";
    private static final String DEFAULTS = "defaults";
    private static final String CAPABILITIES = "capabilities";
    private static final String ALLOW_DESTRUCTIVE = "allow-destructive";
    private static final String CALLS_PER_MINUTE = "calls-per-minute";
    private static final String MAX_PAYLOAD_BYTES = "max-payload-bytes";

    private final Path file;
    private final PluginLogger logger;
    private volatile Map<String, ProgramGrant> grants = Map.of();

    private YamlProgramGrantStore(Path file, PluginLogger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Loads the store. A missing file is an empty store; a malformed file is
     * reported and leaves the store empty (fail-closed).
     *
     * @param file   canonical {@code config/iflow/grants.yml}
     * @param logger diagnostic sink
     * @return a store with the last valid snapshot
     */
    static YamlProgramGrantStore load(Path file, PluginLogger logger) {
        YamlProgramGrantStore store = new YamlProgramGrantStore(file, logger);
        store.reload();
        return store;
    }

    /** Re-reads the file, replacing the snapshot atomically. */
    void reload() {
        if (!Files.isRegularFile(file)) {
            grants = Map.of();
            return;
        }
        List<String> errors = new ArrayList<>();
        Map<String, ProgramGrant> parsed = parse(errors);
        for (String error : errors) {
            logger.error("iflow grants: " + error);
        }
        if (!errors.isEmpty()) {
            grants = Map.of();
            return;
        }
        grants = Map.copyOf(parsed);
        logger.debug("iflow grants: " + grants.size() + " program grant(s) loaded");
    }

    @Override
    public Optional<ProgramGrant> find(String sha256) {
        return sha256 == null ? Optional.empty()
                : Optional.ofNullable(grants.get(sha256));
    }

    private Map<String, ProgramGrant> parse(List<String> errors) {
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options))
                    .load(Files.readString(file));
        } catch (IOException | RuntimeException failure) {
            errors.add("cannot read " + file + ": " + failure.getMessage());
            return Map.of();
        }
        if (!(root instanceof Map<?, ?> map)) {
            errors.add("root of " + file + " must be a map");
            return Map.of();
        }
        try {
            ConfigVersion.require(map, file);
        } catch (RuntimeException invalidVersion) {
            errors.add(invalidVersion.getMessage());
            return Map.of();
        }

        for (Object key : map.keySet()) {
            String name = String.valueOf(key);
            if (!name.equals(ConfigVersion.KEY) && !name.equals(DEFAULTS)
                    && !name.equals(GRANTS)) {
                errors.add("unknown key '" + name + "'");
            }
        }

        int defaultCalls = ProgramGrant.DEFAULT_CALLS_PER_MINUTE;
        int defaultPayload = ProgramGrant.DEFAULT_MAX_PAYLOAD_BYTES;
        Object defaults = map.get(DEFAULTS);
        if (defaults != null) {
            if (!(defaults instanceof Map<?, ?> values)) {
                errors.add("'" + DEFAULTS + "' must be a map");
            } else {
                for (Object key : values.keySet()) {
                    String name = String.valueOf(key);
                    if (!name.equals(CALLS_PER_MINUTE) && !name.equals(MAX_PAYLOAD_BYTES)) {
                        errors.add("unknown key '" + DEFAULTS + "." + name + "'");
                    }
                }
                defaultCalls = positiveInt(values, CALLS_PER_MINUTE, defaultCalls, errors,
                        DEFAULTS + ".");
                defaultPayload = positiveInt(values, MAX_PAYLOAD_BYTES, defaultPayload,
                        errors, DEFAULTS + ".");
            }
        }

        Object rawGrants = map.get(GRANTS);
        if (rawGrants == null) {
            return Map.of();
        }
        if (!(rawGrants instanceof Map<?, ?> entries)) {
            errors.add("'" + GRANTS + "' must be a map indexed by SHA-256");
            return Map.of();
        }

        Map<String, ProgramGrant> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String sha256 = String.valueOf(entry.getKey()).trim();
            if (!sha256.matches("[0-9a-f]{64}")) {
                errors.add("grant key '" + sha256
                        + "' is not a lowercase SHA-256");
                continue;
            }
            if (parsed.containsKey(sha256)) {
                errors.add("duplicate grant for '" + sha256 + "'");
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> grant)) {
                errors.add("grant '" + sha256 + "' must be a map");
                continue;
            }
            ProgramGrant value = parseGrant(sha256, grant, defaultCalls, defaultPayload, errors);
            if (value != null) {
                parsed.put(sha256, value);
            }
        }
        return parsed;
    }

    private ProgramGrant parseGrant(String sha256, Map<?, ?> grant, int defaultCalls,
                                    int defaultPayload, List<String> errors) {
        for (Object key : grant.keySet()) {
            String name = String.valueOf(key);
            if (!name.equals(CAPABILITIES) && !name.equals(ALLOW_DESTRUCTIVE)
                    && !name.equals(CALLS_PER_MINUTE) && !name.equals(MAX_PAYLOAD_BYTES)) {
                errors.add("unknown key '" + name + "' in grant '" + sha256 + "'");
            }
        }
        Object rawCapabilities = grant.get(CAPABILITIES);
        if (!(rawCapabilities instanceof List<?> list)) {
            errors.add("grant '" + sha256 + "' must declare a 'capabilities' list");
            return null;
        }
        java.util.Set<String> capabilities = new java.util.LinkedHashSet<>();
        for (Object item : list) {
            if (item == null || String.valueOf(item).isBlank()) {
                errors.add("grant '" + sha256 + "' declares a blank capability");
                continue;
            }
            capabilities.add(String.valueOf(item).trim());
        }
        Object rawDestructive = grant.get(ALLOW_DESTRUCTIVE);
        if (rawDestructive != null && !(rawDestructive instanceof Boolean)) {
            errors.add("grant '" + sha256 + "' has a non-boolean '"
                    + ALLOW_DESTRUCTIVE + "'");
            return null;
        }
        boolean allowDestructive = Boolean.TRUE.equals(rawDestructive);
        int calls = positiveInt(grant, CALLS_PER_MINUTE, defaultCalls, errors,
                "grant '" + sha256 + "': ");
        int payload = positiveInt(grant, MAX_PAYLOAD_BYTES, defaultPayload, errors,
                "grant '" + sha256 + "': ");
        try {
            return new ProgramGrant(sha256, capabilities, allowDestructive, calls, payload);
        } catch (RuntimeException invalid) {
            errors.add("grant '" + sha256 + "' is invalid: " + invalid.getMessage());
            return null;
        }
    }

    private static int positiveInt(Map<?, ?> map, String key, int fallback,
                                   List<String> errors, String prefix) {
        Object raw = map.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number number) || number.longValue() != number.intValue()) {
            errors.add(prefix + "'" + key + "' must be an integer");
            return fallback;
        }
        int value = number.intValue();
        if (value <= 0) {
            errors.add(prefix + "'" + key + "' must be positive");
            return fallback;
        }
        return value;
    }
}
