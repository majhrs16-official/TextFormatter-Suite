package me.majhrs16.suite.manager.spi;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parser for repo.yml configuration file.
 */
public final class RepoConfigParser {

    private RepoConfigParser() {}

    /**
     * Parses repo.yml from the given path.
     */
    public static List<RepositoryConfig> parse(Path repoYml) throws IOException, ParseException {
        if (!Files.exists(repoYml)) {
            return List.of();
        }

        String content = Files.readString(repoYml);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) yaml.load(content);
        
        if (root == null || !root.containsKey("repositories")) {
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repos = (List<Map<String, Object>>) root.get("repositories");
        
        List<RepositoryConfig> configs = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            Map<String, Object> repo = repos.get(i);
            configs.add(parseRepository(repo, i));
        }

        // Sort by priority (lower = higher priority)
        configs.sort(Comparator.comparingInt(RepositoryConfig::priority));
        return configs;
    }

    private static RepositoryConfig parseRepository(Map<String, Object> repo, int index) throws ParseException {
        String name = getString(repo, "name", "repo-" + index);
        String url = getString(repo, "url", "");
        if (url.isBlank()) {
            throw new ParseException("Repository " + name + ": missing required 'url' field");
        }

        String typeStr = getString(repo, "type", "github").toLowerCase();
        RepositoryConfig.RepositoryType type;
        try {
            type = RepositoryConfig.RepositoryType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("Repository " + name + ": invalid type '" + typeStr + "' (expected: github, local, http)");
        }

        boolean enabled = getBoolean(repo, "enabled", true);
        int priority = getInt(repo, "priority", 100);

        @SuppressWarnings("unchecked")
        Map<String, String> options = (Map<String, String>) repo.getOrDefault("options", Map.of());

        return new RepositoryConfig(name, url, type, enabled, priority, options);
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return defaultValue;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /**
     * Serializes repository configs to repo.yml format.
     */
    public static String serialize(List<RepositoryConfig> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TextFormatter Suite Repository Configuration\n");
        sb.append("# Generated automatically - do not edit manually\n\n");
        sb.append("repositories:\n");
        for (RepositoryConfig config : configs) {
            sb.append("  - name: \"").append(config.name()).append("\"\n");
            sb.append("    url: \"").append(config.url()).append("\"\n");
            sb.append("    type: ").append(config.type().name().toLowerCase()).append("\n");
            sb.append("    enabled: ").append(config.enabled()).append("\n");
            sb.append("    priority: ").append(config.priority()).append("\n");
            if (!config.options().isEmpty()) {
                sb.append("    options:\n");
                for (Map.Entry<String, String> opt : config.options().entrySet()) {
                    sb.append("      ").append(opt.getKey()).append(": \"").append(opt.getValue()).append("\"\n");
                }
            }
        }
        return sb.toString();
    }

    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}