package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.PluginLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration validator for TextFormatter Suite.
 * <p>
 * Validates the configuration files against the schema v2.2.
 * Produces issues with shape matching the web-editor validation format:
 * {@code [{nivel, grupo, ruta, mensaje}]}.
 * </p>
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    /**
     * Validates the entire configuration layout.
     *
     * @param config the parsed HostConfig
     * @param logger logger for warnings/errors
     * @param configDir the directory containing config files
     * @return list of validation issues; empty if valid
     */
    public static List<ValidationIssue> validate(HostConfig config, PluginLogger logger, Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();

        // Validate config.yml
        issues.addAll(validateConfig(config, configDir));

        // Validate channels
        issues.addAll(validateChannels(configDir));

        // Validate rules.yml
        issues.addAll(validateRules(configDir));

        // Validate translators
        issues.addAll(validateTranslators(configDir));

        // Validate sync configs
        issues.addAll(validateSync(configDir));

        // Log issues
        for (ValidationIssue issue : issues) {
            switch (issue.level()) {
                case ERROR -> logger.error("[config] " + issue.group() + " " + issue.path() + ": " + issue.message());
                case WARNING -> logger.warn("[config] " + issue.group() + " " + issue.path() + ": " + issue.message());
                case INFO -> logger.info("[config] " + issue.group() + " " + issue.path() + ": " + issue.message());
            }
        }

        return issues;
    }

    /**
     * Validates config.yml structure.
     */
    private static List<ValidationIssue> validateConfig(HostConfig config, Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();
        Path file = configDir.resolve("config.yml");

        if (!file.toFile().exists()) {
            issues.add(new ValidationIssue(
                ValidationLevel.WARNING,
                "config",
                "config.yml",
                "config.yml no existe; se usarán defaults"
            ));
        }

        // Validate claim-mode
        if (config.claimMode() != null) {
            try {
                HostConfig.ClaimMode.valueOf(config.claimMode().name());
            } catch (IllegalArgumentException e) {
                issues.add(new ValidationIssue(
                    ValidationLevel.ERROR,
                    "config",
                    "chat.claim-mode",
                    "claim-mode inválido: " + config.claimMode() + " (esperado: cancel-event o clear-recipients)"
                ));
            }
        }

        // Validate language
        if (config.defaultLanguage() != null) {
            // Language validation is done in Language.of()
        }

        return issues;
    }

    /**
     * Validates channels/*.yml files.
     */
    private static List<ValidationIssue> validateChannels(Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();
        Path channelsDir = configDir.resolve("channels");

        if (!channelsDir.toFile().exists()) {
            issues.add(new ValidationIssue(
                ValidationLevel.WARNING,
                "channels",
                "channels/",
                "directorio channels/ no existe; no hay canales configurados"
            ));
            return issues;
        }

        Set<String> channelNames = new java.util.HashSet<>();

        try (var stream = java.nio.file.Files.list(channelsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> {
                    String name = p.getFileName().toString().replace(".yml", "");
                    if (!channelNames.add(name)) {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "channels",
                            "channels/" + name + ".yml",
                            "canal duplicado: " + name
                        ));
                    }
                    issues.addAll(validateChannel(p, name));
                });
        } catch (Exception e) {
            issues.add(new ValidationIssue(
                ValidationLevel.ERROR,
                "channels",
                "channels/",
                "error leyendo canales: " + e.getMessage()
            ));
        }

        // Check for required default channels
        String[] defaults = {"chat.global", "join", "quit", "death", "advancement"};
        for (String def : defaults) {
            if (!channelNames.contains(def)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.WARNING,
                    "channels",
                    "channels/" + def + ".yml",
                    "canal por defecto '" + def + "' no encontrado; se creará al iniciar"
                ));
            }
        }

        return issues;
    }

    /**
     * Validates a single channel YAML file.
     */
    private static List<ValidationIssue> validateChannel(Path file, String channelName) {
        List<ValidationIssue> issues = new ArrayList<>();
        String basePath = "channels/" + channelName;

        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            String content = java.nio.file.Files.readString(file);
            Object root = yaml.load(content);

            if (!(root instanceof Map)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.ERROR,
                    "channels",
                    basePath + ".yml",
                    "YAML inválido: raíz debe ser un mapa"
                ));
                return issues;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            // Validate type
            Object typeObj = map.get("type");
            if (typeObj != null) {
                String type = String.valueOf(typeObj).trim().toUpperCase();
                if (!type.equals("CHAT") && !type.equals("EVENT")) {
                    issues.add(new ValidationIssue(
                        ValidationLevel.ERROR,
                        "channels",
                        basePath + ".type",
                        "type inválido: " + typeObj + " (esperado: CHAT o EVENT)"
                    ));
                }
            }

            // Validate rate-limit
            Object rlObj = map.get("rate-limit-per-second");
            if (rlObj != null) {
                if (!(rlObj instanceof Number)) {
                    issues.add(new ValidationIssue(
                        ValidationLevel.ERROR,
                        "channels",
                        basePath + ".rate-limit-per-second",
                        "debe ser un número entero >= 0"
                    ));
                } else {
                    int rl = ((Number) rlObj).intValue();
                    if (rl < 0) {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "channels",
                            basePath + ".rate-limit-per-second",
                            "debe ser >= 0"
                        ));
                    }
                }
            }

            // Validate messages
            Object msgsObj = map.get("messages");
            if (msgsObj != null && !(msgsObj instanceof List)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.ERROR,
                    "channels",
                    basePath + ".messages",
                    "debe ser una lista de strings"
                ));
            }

            // Validate tooltips
            Object tipsObj = map.get("tooltips");
            if (tipsObj != null && !(tipsObj instanceof List)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.ERROR,
                    "channels",
                    basePath + ".tooltips",
                    "debe ser una lista de strings"
                ));
            }

            // Validate sounds
            Object soundsObj = map.get("sounds");
            if (soundsObj != null) {
                if (!(soundsObj instanceof List)) {
                    issues.add(new ValidationIssue(
                        ValidationLevel.ERROR,
                        "channels",
                        basePath + ".sounds",
                        "debe ser una lista de objetos {name, volume, pitch}"
                    ));
                } else {
                    @SuppressWarnings("unchecked")
                    List<Object> sounds = (List<Object>) soundsObj;
                    for (int i = 0; i < sounds.size(); i++) {
                        Object s = sounds.get(i);
                        if (s instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> sm = (Map<String, Object>) s;
                            if (!sm.containsKey("name")) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.ERROR,
                                    "channels",
                                    basePath + ".sounds[" + i + "]",
                                    "falta campo obligatorio 'name'"
                                ));
                            }
                            Object vol = sm.get("volume");
                            if (vol != null && !(vol instanceof Number)) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.WARNING,
                                    "channels",
                                    basePath + ".sounds[" + i + "].volume",
                                    "debe ser un número"
                                ));
                            }
                            Object pitch = sm.get("pitch");
                            if (pitch != null && !(pitch instanceof Number)) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.WARNING,
                                    "channels",
                                    basePath + ".sounds[" + i + "].pitch",
                                    "debe ser un número"
                                ));
                            }
                        } else {
                            issues.add(new ValidationIssue(
                                ValidationLevel.ERROR,
                                "channels",
                                basePath + ".sounds[" + i + "]",
                                "debe ser un objeto {name, volume, pitch}"
                            ));
                        }
                    }
                }
            }

            // Validate lang-source/lang-target
            Object ls = map.get("lang-source");
            if (ls != null && !(ls instanceof String)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.WARNING,
                    "channels",
                    basePath + ".lang-source",
                    "debe ser un string"
                ));
            }
            Object lt = map.get("lang-target");
            if (lt != null && !(lt instanceof String)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.WARNING,
                    "channels",
                    basePath + ".lang-target",
                    "debe ser un string"
                ));
            }

        } catch (Exception e) {
            issues.add(new ValidationIssue(
                ValidationLevel.ERROR,
                "channels",
                basePath + ".yml",
                "error parseando YAML: " + e.getMessage()
            ));
        }

        return issues;
    }

    /**
     * Validates rules.yml (iFlow graph).
     */
    private static List<ValidationIssue> validateRules(Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();
        Path file = configDir.resolve("rules.yml");

        if (!file.toFile().exists()) {
            issues.add(new ValidationIssue(
                ValidationLevel.INFO,
                "rules",
                "rules.yml",
                "rules.yml no existe; iFlow usará default-accept"
            ));
            return issues;
        }

        try {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            String content = java.nio.file.Files.readString(file);
            Object root = yaml.load(content);

            if (!(root instanceof Map)) {
                issues.add(new ValidationIssue(
                    ValidationLevel.ERROR,
                    "rules",
                    "rules.yml",
                    "YAML inválido: raíz debe ser un mapa"
                ));
                return issues;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            // Validate guard
            Object guardObj = map.get("guard");
            if (guardObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> guard = (Map<String, Object>) guardObj;
                Object ms = guard.get("max-steps");
                if (ms != null) {
                    if (!(ms instanceof Number) || ((Number) ms).intValue() < 1) {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "rules",
                            "guard.max-steps",
                            "debe ser un entero >= 1"
                        ));
                    }
                }
            }

            // Validate filter
            Object filterObj = map.get("filter");
            if (filterObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> filter = (Map<String, Object>) filterObj;
                Object df = filter.get("dedup-fanout");
                if (df != null && !(df instanceof Boolean)) {
                    issues.add(new ValidationIssue(
                        ValidationLevel.WARNING,
                        "rules",
                        "filter.dedup-fanout",
                        "debe ser true/false"
                    ));
                }
            }

            // Validate nodes
            Object nodesObj = map.get("nodes");
            if (nodesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> nodes = (List<Object>) nodesObj;
                Set<String> nodeIds = new java.util.HashSet<>();
                for (int i = 0; i < nodes.size(); i++) {
                    Object n = nodes.get(i);
                    if (n instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> node = (Map<String, Object>) n;
                        Object id = node.get("id");
                        if (id == null || !(id instanceof String)) {
                            issues.add(new ValidationIssue(
                                ValidationLevel.ERROR,
                                "rules",
                                "nodes[" + i + "].id",
                                "id obligatorio y debe ser string"
                            ));
                        } else {
                            String idStr = (String) id;
                            if (!nodeIds.add(idStr)) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.ERROR,
                                    "rules",
                                    "nodes[" + i + "].id",
                                    "id duplicado: " + idStr
                                ));
                            }
                        }
                        Object kind = node.get("kind");
                        if (kind == null || !(kind instanceof String)) {
                            issues.add(new ValidationIssue(
                                ValidationLevel.ERROR,
                                "rules",
                                "nodes[" + i + "].kind",
                                "kind obligatorio: input, cond, transform, loop, sleep, output, redirect"
                            ));
                        } else {
                            String kindStr = (String) kind;
                            Set<String> validKinds = Set.of("input", "cond", "transform", "loop", "sleep", "output", "redirect");
                            if (!validKinds.contains(kindStr)) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.ERROR,
                                    "rules",
                                    "nodes[" + i + "].kind",
                                    "kind inválido: " + kindStr + " (esperado: " + validKinds + ")"
                                ));
                            }
                        }
                    } else {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "rules",
                            "nodes[" + i + "]",
                            "cada nodo debe ser un mapa"
                        ));
                    }
                }
            }

            // Validate edges
            Object edgesObj = map.get("edges");
            if (edgesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> edges = (List<Object>) edgesObj;
                for (int i = 0; i < edges.size(); i++) {
                    Object e = edges.get(i);
                    if (e instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> edge = (Map<String, Object>) e;
                        if (edge.get("from") == null || !(edge.get("from") instanceof String)) {
                            issues.add(new ValidationIssue(
                                ValidationLevel.ERROR,
                                "rules",
                                "edges[" + i + "].from",
                                "from obligatorio (string)"
                            ));
                        }
                        if (edge.get("to") == null || !(edge.get("to") instanceof String)) {
                            issues.add(new ValidationIssue(
                                ValidationLevel.ERROR,
                                "rules",
                                "edges[" + i + "].to",
                                "to obligatorio (string)"
                            ));
                        }
                    } else {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "rules",
                            "edges[" + i + "]",
                            "cada edge debe ser un mapa con from y to"
                        ));
                    }
                }
            }

        } catch (Exception e) {
            issues.add(new ValidationIssue(
                ValidationLevel.ERROR,
                "rules",
                "rules.yml",
                "error parseando YAML: " + e.getMessage()
            ));
        }

        return issues;
    }

    /**
     * Validates translators/*.yml files.
     */
    private static List<ValidationIssue> validateTranslators(Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();
        Path translatorsDir = configDir.resolve("translators");

        if (!translatorsDir.toFile().exists()) {
            issues.add(new ValidationIssue(
                ValidationLevel.WARNING,
                "translators",
                "translators/",
                "directorio translators/ no existe; sin proveedores de traducción"
            ));
            return issues;
        }

        int activeCount = 0;
        try (var stream = java.nio.file.Files.list(translatorsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> {
                    try {
                        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                        String content = java.nio.file.Files.readString(p);
                        Object root = yaml.load(content);
                        if (root instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = (Map<String, Object>) root;
                            Object provider = map.get("provider");
                            if (provider == null) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.ERROR,
                                    "translators",
                                    p.getFileName().toString(),
                                    "falta campo obligatorio 'provider' (google|libre)"
                                ));
                            } else if (!"google".equals(provider) && !"libre".equals(provider)) {
                                issues.add(new ValidationIssue(
                                    ValidationLevel.WARNING,
                                    "translators",
                                    p.getFileName().toString(),
                                    "provider desconocido: " + provider + " (esperado: google|libre)"
                                ));
                            }
                            Object active = map.get("active");
                            if (active instanceof Boolean && (Boolean) active) {
                                activeCount++;
                            }
                        }
                    } catch (Exception e) {
                        issues.add(new ValidationIssue(
                            ValidationLevel.ERROR,
                            "translators",
                            p.getFileName().toString(),
                            "error leyendo: " + e.getMessage()
                        ));
                    }
                });
        } catch (Exception e) {
            issues.add(new ValidationIssue(
                ValidationLevel.ERROR,
                "translators",
                "translators/",
                "error listando: " + e.getMessage()
            ));
        }

        if (activeCount == 0) {
            issues.add(new ValidationIssue(
                ValidationLevel.WARNING,
                "translators",
                "translators/",
                "ningún proveedor marcado como active; traducción deshabilitada"
            ));
        }

        return issues;
    }

    /**
     * Validates sync/*.yml files.
     */
    private static List<ValidationIssue> validateSync(Path configDir) {
        List<ValidationIssue> issues = new ArrayList<>();
        Path syncDir = configDir.resolve("sync");

        if (!syncDir.toFile().exists()) {
            return issues;
        }

        String[] required = {"discord.yml", "telegram.yml", "http.yml", "tcp-udp.yml"};
        for (String req : required) {
            Path f = syncDir.resolve(req);
            if (f.toFile().exists()) {
                try {
                    org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                    String content = java.nio.file.Files.readString(f);
                    Object root = yaml.load(content);
                    if (root instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) root;
                        Object enabled = map.get("enabled");
                        if (enabled instanceof Boolean && (Boolean) enabled) {
                            // Validate required fields per type
                            if (req.equals("discord.yml")) {
                                Object token = map.get("token");
                                if (token == null || !(token instanceof String) || ((String) token).isBlank()) {
                                    issues.add(new ValidationIssue(
                                        ValidationLevel.ERROR,
                                        "sync",
                                        req,
                                        "discord enabled=true pero falta 'token'"
                                    ));
                                }
                                Object channel = map.get("channel");
                                if (channel == null || !(channel instanceof String) || ((String) channel).isBlank()) {
                                    issues.add(new ValidationIssue(
                                        ValidationLevel.ERROR,
                                        "sync",
                                        req,
                                        "discord enabled=true pero falta 'channel'"
                                    ));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    issues.add(new ValidationIssue(
                        ValidationLevel.ERROR,
                        "sync",
                        req,
                        "error leyendo: " + e.getMessage()
                    ));
                }
            }
        }

        return issues;
    }

    /**
     * Validation issue record matching web-editor format.
     */
    public record ValidationIssue(
        ValidationLevel level,
        String group,
        String path,
        String message
    ) {}

    /**
     * Validation severity levels.
     */
    public enum ValidationLevel {
        INFO,
        WARNING,
        ERROR
    }
}