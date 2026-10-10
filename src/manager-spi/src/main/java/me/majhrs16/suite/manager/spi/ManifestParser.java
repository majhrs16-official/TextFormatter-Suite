package me.majhrs16.suite.manager.spi;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Parser for the package manifest format used in:
 * - Remote index: packages.list (tab-separated: name<TAB>URL<TAB>size<TAB>SHA256)
 * - Internal manifest: METADATA/package.list (multi-block format with full metadata)
 * - Local database: library/status (same format as METADATA/package.list)
 */
public final class ManifestParser {

    private static final Pattern TAB_PATTERN = Pattern.compile("\t");
    private static final String BLOCK_SEPARATOR = "\n\n"; // Double blank line separates blocks
    private static final String FIELD_SEPARATOR = ": ";

    private ManifestParser() {}

    /**
     * Parses a remote index file (packages.list).
     * Format: name<TAB>URL<TAB>size<TAB>SHA256 (one entry per line)
     *
     * @param content the file content
     * @return list of remote index entries
     * @throws ParseException if any line is malformed
     */
    public static List<RemoteIndexEntry> parseRemoteIndex(String content) throws ParseException {
        List<RemoteIndexEntry> entries = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] fields = TAB_PATTERN.split(line, 4);
            if (fields.length != 4) {
                throw new ParseException("Line " + (i + 1) + ": expected 4 tab-separated fields, got " + fields.length);
            }

            String name = fields[0].strip();
            String url = fields[1].strip();
            long size;
            try {
                size = Long.parseLong(fields[2].strip());
            } catch (NumberFormatException e) {
                throw new ParseException("Line " + (i + 1) + ": invalid size: " + fields[2]);
            }
            String sha256 = fields[2].strip(); // This is wrong - should be fields[3]
            if (!isValidSha256(sha256)) {
                throw new ParseException("Line " + (i + 1) + ": invalid SHA256: " + sha256);
            }

            entries.add(new RemoteIndexEntry(name, url, size, sha256));
        }
        return entries;
    }

    /**
     * Parses a multi-block manifest (METADATA/package.list or library/status).
     * Each block represents one package entry, separated by double blank line.
     *
     * @param content the manifest content
     * @return list of package entries
     * @throws ParseException if the manifest is malformed
     */
    public static List<PackageEntry> parseManifest(String content) throws ParseException {
        List<PackageEntry> entries = new ArrayList<>();

        // Split by double blank line (block separator)
        String[] blocks = content.split("\n\n(?=\\S)");
        
        for (int blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
            String block = blocks[blockIndex].strip();
            if (block.isEmpty()) continue;

            Map<String, String> fields = new LinkedHashMap<>();
            Map<String, FileEntry> files = new LinkedHashMap<>();
            String currentField = null;
            StringBuilder currentValue = new StringBuilder();
            boolean inFilesSection = false;

            String[] lines = block.split("\n");
            for (int lineNum = 0; lineNum < lines.length; lineNum++) {
                String line = lines[lineNum];
                
                // Check for field separator
                int sepIndex = line.indexOf(FIELD_SEPARATOR);
                if (sepIndex > 0 && !inFilesSection) {
                    // Save previous field
                    if (currentField != null) {
                        fields.put(currentField, currentValue.toString().strip());
                    }
                    
                    currentField = line.substring(0, sepIndex).strip();
                    currentValue = new StringBuilder(line.substring(sepIndex + FIELD_SEPARATOR.length()));
                    
                    // Check for special sections
                    if ("Files".equals(currentField)) {
                        inFilesSection = true;
                        currentField = null; // Files are handled separately
                    }
                    continue;
                }

                if (inFilesSection) {
                    // File entry format: "  path<TAB>size<TAB>sha256<TAB>owner"
                    if (line.trim().isEmpty()) {
                        inFilesSection = false;
                        continue;
                    }
                    String[] fileFields = TAB_PATTERN.split(line.trim(), 4);
                    if (fileFields.length >= 3) {
                        String path = fileFields[0].strip();
                        long size;
                        try {
                            size = Long.parseLong(fileFields[1].strip());
                        } catch (NumberFormatException e) {
                            throw new ParseException("Block " + (blockIndex + 1) + ", file line " + (lineNum + 1) + ": invalid size");
                        }
                        String sha256 = fileFields[2].strip();
                        String owner = fileFields.length > 3 ? fileFields[3].strip() : "";
                        if (!isValidSha256(sha256)) {
                            throw new ParseException("Block " + (blockIndex + 1) + ", file line " + (lineNum + 1) + ": invalid SHA256");
                        }
                        files.put(path, new FileEntry(path, size, sha256, owner));
                    }
                    continue;
                }

                // Continuation of previous field value (indented lines)
                if (currentField != null && (line.startsWith(" ") || line.startsWith("\t"))) {
                    currentValue.append("\n").append(line.strip());
                } else if (currentField != null) {
                    // New field without separator - save previous and start new
                    fields.put(currentField, currentValue.toString().strip());
                    
                    // Try to parse as new field
                    int newSepIndex = line.indexOf(FIELD_SEPARATOR);
                    if (newSepIndex > 0) {
                        currentField = line.substring(0, newSepIndex).strip();
                        currentValue = new StringBuilder(line.substring(newSepIndex + FIELD_SEPARATOR.length()));
                    } else {
                        currentField = null;
                        currentValue = new StringBuilder();
                    }
                }
            }

            // Save last field
            if (currentField != null) {
                fields.put(currentField, currentValue.toString().strip());
            }

            // Build PackageEntry from fields
            PackageEntry entry = buildPackageEntry(fields, files, blockIndex + 1);
            entries.add(entry);
        }

        return entries;
    }

    private static PackageEntry buildPackageEntry(Map<String, String> fields, Map<String, FileEntry> files, int blockNum) throws ParseException {
        String name = requireField(fields, "Name", blockNum);
        String version = requireField(fields, "Version", blockNum);
        String channel = requireField(fields, "Channel", blockNum);
        String typeStr = requireField(fields, "Type", blockNum);
        String dependenciesStr = fields.getOrDefault("Dependencies", "");
        String arch = fields.getOrDefault("Arch", "none");
        String platform = fields.getOrDefault("Platform", "common");
        String url = fields.getOrDefault("URL", "");
        String os = fields.getOrDefault("OS", "none");
        String sha256 = fields.getOrDefault("SHA256", "");
        String sizeStr = fields.getOrDefault("Size", "0");
        String sourceArtifact = fields.getOrDefault("SourceArtifact", "");
        String parentPackage = fields.getOrDefault("ParentPackage", "");

        PackageType type;
        try {
            type = PackageType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ParseException("Block " + blockNum + ": invalid Type: " + typeStr);
        }

        long size;
        try {
            size = Long.parseLong(sizeStr);
        } catch (NumberFormatException e) {
            throw new ParseException("Block " + blockNum + ": invalid Size: " + sizeStr);
        }

        // Parse dependencies (comma-separated)
        List<String> dependencies = new ArrayList<>();
        if (!dependenciesStr.isBlank()) {
            for (String dep : dependenciesStr.split(",")) {
                String trimmed = dep.strip();
                if (!trimmed.isEmpty()) {
                    dependencies.add(trimmed);
                }
            }
        }

        // Validate SHA256 if present
        if (!sha256.isBlank() && !isValidSha256(sha256)) {
            throw new ParseException("Block " + blockNum + ": invalid SHA256: " + sha256);
        }

        // Split extra fields (status-only fields)
        Map<String, String> extraFields = new LinkedHashMap<>();
        Set<String> knownFields = Set.of("Name", "Version", "Channel", "Type", "Dependencies", 
                "Arch", "Platform", "URL", "OS", "SHA256", "Size", "SourceArtifact", "ParentPackage");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!knownFields.contains(entry.getKey())) {
                extraFields.put(entry.getKey(), entry.getValue());
            }
        }

        return new PackageEntry(name, version, channel, type, dependencies, arch, platform,
                url, os, sha256, size, files, sourceArtifact, parentPackage, extraFields);
    }

    private static String requireField(Map<String, String> fields, String key, int blockNum) throws ParseException {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new ParseException("Block " + blockNum + ": missing required field: " + key);
        }
        return value.strip();
    }

    private static boolean isValidSha256(String sha256) {
        return sha256.matches("^[a-fA-F0-9]{64}$");
    }

    /**
     * Serializes a package entry to the manifest format.
     */
    public static String serialize(PackageEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(entry.name()).append("\n");
        sb.append("Version: ").append(entry.version()).append("\n");
        sb.append("Channel: ").append(entry.channel()).append("\n");
        sb.append("Type: ").append(entry.type().name()).append("\n");
        sb.append("Dependencies: ").append(String.join(",", entry.dependencies())).append("\n");
        sb.append("Arch: ").append(entry.arch()).append("\n");
        sb.append("Platform: ").append(entry.platform()).append("\n");
        sb.append("URL: ").append(entry.url()).append("\n");
        sb.append("OS: ").append(entry.os()).append("\n");
        sb.append("SHA256: ").append(entry.sha256()).append("\n");
        sb.append("Size: ").append(entry.size()).append("\n");
        if (!entry.sourceArtifact().isBlank()) {
            sb.append("SourceArtifact: ").append(entry.sourceArtifact()).append("\n");
        }
        if (!entry.parentPackage().isBlank()) {
            sb.append("ParentPackage: ").append(entry.parentPackage()).append("\n");
        }
        // Extra fields (status-only)
        for (Map.Entry<String, String> extra : entry.extraFields().entrySet()) {
            sb.append(extra.getKey()).append(": ").append(extra.getValue()).append("\n");
        }
        
        // Files section
        sb.append("Files:\n");
        for (FileEntry file : entry.files().values()) {
            sb.append("  ").append(file.path()).append("\t")
                    .append(file.size()).append("\t")
                    .append(file.sha256()).append("\t")
                    .append(file.ownerPackage()).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Serializes multiple entries with block separators.
     */
    public static String serialize(List<PackageEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(serialize(entries.get(i)));
        }
        return sb.toString();
    }

    /**
     * Exception thrown when parsing fails.
     */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    /**
     * Entry in a remote packages.list index.
     */
    public static final class RemoteIndexEntry {
        private final String name;
        private final String url;
        private final long size;
        private final String sha256;

        public RemoteIndexEntry(String name, String url, long size, String sha256) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }

        public String name() { return name; }
        public String url() { return url; }
        public long size() { return size; }
        public String sha256() { return sha256; }

        @Override
        public String toString() {
            return "RemoteIndexEntry{name='" + name + "', url='" + url + "', size=" + size + '}';
        }
    }
}