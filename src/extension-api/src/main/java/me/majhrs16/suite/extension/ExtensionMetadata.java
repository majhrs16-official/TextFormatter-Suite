package me.majhrs16.suite.extension;

import java.util.List;
import java.util.Map;

/**
 * Metadata for an extension, used by web-editor and /suite extensions command.
 */
public record ExtensionMetadata(
    String id,
    String name,
    String description,
    String author,
    String website,
    String license,
    List<String> tags,
    Map<String, String> links,  // e.g. "wiki" -> "https://...", "issues" -> "https://..."
    String minSuiteVersion,      // minimum TextFormatter Suite version required
    String maxSuiteVersion,      // maximum compatible version (optional)
    boolean stable,              // true = stable release, false = beta/alpha
    boolean experimental         // uses experimental APIs that may change
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private String description = "";
        private String author = "";
        private String website = "";
        private String license = "GPL-3.0";
        private List<String> tags = List.of();
        private Map<String, String> links = Map.of();
        private String minSuiteVersion = "2.1.0";
        private String maxSuiteVersion = "";
        private boolean stable = true;
        private boolean experimental = false;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder author(String author) { this.author = author; return this; }
        public Builder website(String website) { this.website = website; return this; }
        public Builder license(String license) { this.license = license; return this; }
        public Builder tags(List<String> tags) { this.tags = tags; return this; }
        public Builder links(Map<String, String> links) { this.links = links; return this; }
        public Builder minSuiteVersion(String min) { this.minSuiteVersion = min; return this; }
        public Builder maxSuiteVersion(String max) { this.maxSuiteVersion = max; return this; }
        public Builder stable(boolean stable) { this.stable = stable; return this; }
        public Builder experimental(boolean experimental) { this.experimental = experimental; return this; }

        public ExtensionMetadata build() {
            return new ExtensionMetadata(id, name, description, author, website, license, tags, links,
                minSuiteVersion, maxSuiteVersion, stable, experimental);
        }
    }
}