package me.majhrs16.suite.manager.spi;

import java.util.List;
import java.util.Map;

/**
 * Repository configuration loaded from repo.yml.
 */
public final class RepositoryConfig {

    private final String name;
    private final String url;
    private final RepositoryType type;
    private final boolean enabled;
    private final int priority; // Lower = higher priority
    private final Map<String, String> options; // Additional options (auth, headers, etc.)

    public RepositoryConfig(String name, String url, RepositoryType type, boolean enabled, int priority, Map<String, String> options) {
        this.name = name;
        this.url = url;
        this.type = type;
        this.enabled = enabled;
        this.priority = priority;
        this.options = options;
    }

    public String name() { return name; }
    public String url() { return url; }
    public RepositoryType type() { return type; }
    public boolean enabled() { return enabled; }
    public int priority() { return priority; }
    public Map<String, String> options() { return options; }

    public enum RepositoryType {
        GITHUB,
        LOCAL,
        HTTP
    }
}