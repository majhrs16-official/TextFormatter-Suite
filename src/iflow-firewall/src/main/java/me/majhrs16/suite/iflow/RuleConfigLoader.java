package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.TransformOp;
import me.majhrs16.suite.api.iflow.PolicyTarget;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads the legacy iFlow rules from {@code rules.yml} into {@link Rule} objects.
 *
 * <p>Este es el motor histórico (SpEL) restaurado desde {@code 933bdd3^:src/iflow}.
 * Acepta dos formas de raíz:</p>
 *
 * <ul>
 *   <li>una lista de reglas (formato histórico de ejecución);</li>
 *   <li>un mapa con la clave {@code rules} que contiene esa lista.</li>
 * </ul>
 *
 * <p>El grafo del editor web ({@code nodes}/{@code edges}) no se ejecuta: se
 * detecta y se ignora, dejando el conjunto de reglas vacío (default-accept).</p>
 */
public final class RuleConfigLoader {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private RuleConfigLoader() {
    }

    /** @return archivo canónico, exista o no. */
    public static Path canonicalRulesFile(Path dataFolder) {
        return Objects.requireNonNull(dataFolder, "dataFolder")
            .resolve("config").resolve("iflow-firewall").resolve("rules.yml");
    }

    /**
     * Resuelve el archivo efectivo: canónico si existe, si no el plano
     * heredado, y si no existe ninguno, el canónico.
     */
    public static Path rulesFile(Path dataFolder) {
        Path canonical = canonicalRulesFile(dataFolder);
        Path legacy = Objects.requireNonNull(dataFolder, "dataFolder").resolve("rules.yml");
        if (Files.exists(canonical) || !Files.exists(legacy)) {
            return canonical;
        }
        return legacy;
    }

    /**
     * Loads rules from {@code rules.yml} in the given config directory.
     * Returns an empty list if the file doesn't exist, is empty or only holds
     * the web-editor graph.
     */
    public static List<Rule> loadRules(Path dataFolder, PluginLogger logger) {
        return loadRulesFile(rulesFile(dataFolder), logger);
    }

    /**
     * Loads {@code <configDir>/rules.yml} for a module-scoped configuration
     * directory.
     */
    public static List<Rule> loadRulesFromConfigDir(Path configDir, PluginLogger logger) {
        return loadRulesFile(configDir.resolve("rules.yml"), logger);
    }

    private static List<Rule> loadRulesFile(Path rulesFile, PluginLogger logger) {
        if (!Files.isRegularFile(rulesFile)) {
            if (logger != null) {
                logger.debug("rules.yml not found at " + rulesFile + "; using empty rule set");
            }
            return List.of();
        }

        try {
            String content = Files.readString(rulesFile);
            Object root = YAML.load(content);
            List<?> rawRules = extractRawRules(root);
            if (rawRules == null) {
                if (logger != null) {
                    logger.info("rules.yml at " + rulesFile
                        + " has no executable rule list (web-editor graph?); using empty rule set");
                }
                return List.of();
            }

            List<Rule> rules = new ArrayList<>();
            for (int i = 0; i < rawRules.size(); i++) {
                Object raw = rawRules.get(i);
                if (!(raw instanceof Map<?, ?>)) {
                    if (logger != null) {
                        logger.warn("Skipping rule at index " + i + ": not a map");
                    }
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> ruleMap = (Map<String, Object>) raw;
                try {
                    Rule rule = parseRule(ruleMap, i);
                    if (rule != null) {
                        rules.add(rule);
                    }
                } catch (Exception e) {
                    if (logger != null) {
                        logger.warn("Failed to parse rule at index " + i + ": " + e.getMessage());
                    }
                }
            }

            if (logger != null) {
                logger.info("Loaded " + rules.size() + " iFlow rules from " + rulesFile);
            }
            return rules;

        } catch (IOException | RuntimeException e) {
            if (logger != null) {
                logger.error("Failed to read rules.yml: " + e.getMessage(), e);
            }
            return List.of();
        }
    }

    /**
     * @return la lista cruda de reglas, o {@code null} si el contenido es un
     *         grafo del editor o una raíz inválida.
     */
    private static List<?> extractRawRules(Object root) {
        if (root instanceof List<?> list) {
            return list;
        }
        if (root instanceof Map<?, ?> map && map.get("rules") instanceof List<?> list) {
            return list;
        }
        return null;
    }

    private static Rule parseRule(Map<String, Object> map, int index) {
        String id = getString(map, "id", "rule-" + index);
        int priority = getInt(map, "priority", Integer.MAX_VALUE);
        String reason = getString(map, "reason", "matched rule");

        // Parse target (required)
        String targetStr = getString(map, "target", "LOG");
        PolicyTarget target;
        try {
            target = PolicyTarget.valueOf(targetStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid target: " + targetStr);
        }

        // Parse optional matchers
        String channelPath = getString(map, "channel", null);
        String emitterPattern = getString(map, "emitter", null);
        String receiverPattern = getString(map, "receiver", null);

        Direction.Kind kind = null;
        String directionStr = getString(map, "direction", null);
        if (directionStr != null) {
            try {
                kind = Direction.Kind.valueOf(directionStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid direction: " + directionStr);
            }
        }

        // Parse SpEL condition and action
        String condition = getString(map, "condition", null);
        String action = getString(map, "action", null);

        // Parse transform operations
        List<TransformOp> transforms = parseTransforms(map);

        // Parse redirect channel for CHANNEL_REDIRECT
        String redirectChannel = getString(map, "redirect-channel", null);
        if (target == PolicyTarget.CHANNEL_REDIRECT && (redirectChannel == null || redirectChannel.isBlank())) {
            throw new IllegalArgumentException("CHANNEL_REDIRECT target requires 'redirect-channel' field");
        }

        return Rule.builder(target)
            .id(id)
            .priority(priority)
            .reason(reason)
            .channelPath(channelPath)
            .emitterPattern(emitterPattern)
            .receiverPattern(receiverPattern)
            .kind(kind)
            .transforms(transforms)
            .redirectChannel(redirectChannel)
            .condition(condition)
            .action(action)
            .build();
    }

    private static List<TransformOp> parseTransforms(Map<String, Object> map) {
        List<TransformOp> transforms = new ArrayList<>();

        Object transformsObj = map.get("transforms");
        if (transformsObj instanceof List<?> transformsList) {
            for (Object value : transformsList) {
                if (!(value instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> trMap = (Map<String, Object>) value;
                String op = getString(trMap, "op", null);
                if (op == null) continue;

                TransformOp transform = parseTransformOp(op, trMap);
                if (transform != null) {
                    transforms.add(transform);
                }
            }
        }

        // Also support legacy single "transform" string
        String legacyTransform = getString(map, "transform", null);
        if (legacyTransform != null && transforms.isEmpty()) {
            transforms.add(new TransformOp.Rewrite(legacyTransform));
        }

        return transforms;
    }

    private static TransformOp parseTransformOp(String op, Map<String, Object> trMap) {
        return switch (op.toLowerCase()) {
            case "rewrite" -> new TransformOp.Rewrite(getString(trMap, "template", ""));
            case "sounds" -> new TransformOp.Sounds(
                parseStringList(trMap, "add"),
                parseStringList(trMap, "remove"));
            case "sleep" -> new TransformOp.Sleep(getLong(trMap, "millis", 0L));
            case "setlangsource" -> new TransformOp.SetLangSource(getString(trMap, "lang", "auto"));
            case "setlangtarget" -> new TransformOp.SetLangTarget(getString(trMap, "lang", "auto"));
            case "setcolormode" -> new TransformOp.SetColorMode(getString(trMap, "mode", "GRADIENT"));
            case "setformatpapi" -> new TransformOp.SetFormatPapi(getBoolean(trMap, "enabled", true));
            case "setchannel" -> new TransformOp.SetChannel(getString(trMap, "channel", ""));
            default -> null;
        };
    }

    private static List<String> parseStringList(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String value) {
                    result.add(value);
                }
            }
            return result;
        }
        if (obj instanceof String value) {
            return List.of(value.split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static String getString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long getLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }
}
