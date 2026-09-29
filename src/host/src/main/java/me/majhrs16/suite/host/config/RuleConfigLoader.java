package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.TransformOp;
import me.majhrs16.suite.iflow.target.PolicyTarget;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads iFlow rules from {@code rules.yml} into a list of {@link Rule} objects.
 */
public final class RuleConfigLoader {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private RuleConfigLoader() {
    }

    /**
     * Loads rules from {@code rules.yml} in the given config directory.
     * Returns an empty list if the file doesn't exist or is empty.
     */
    public static List<Rule> loadRules(Path configDir, PluginLogger logger) {
        Path rulesFile = configDir.resolve("rules.yml");
        if (!Files.exists(rulesFile)) {
            if (logger != null) {
                logger.debug("rules.yml not found at " + rulesFile + "; using empty rule set");
            }
            return List.of();
        }

        try {
            String content = Files.readString(rulesFile);
            Object root = YAML.load(content);
            if (!(root instanceof List)) {
                if (logger != null) {
                    logger.warn("rules.yml root is not a list; skipping rules");
                }
                return List.of();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) root;
            List<Rule> rules = new ArrayList<>();

            for (int i = 0; i < rawRules.size(); i++) {
                Map<String, Object> ruleMap = rawRules.get(i);
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

        } catch (IOException e) {
            if (logger != null) {
                logger.error("Failed to read rules.yml: " + e.getMessage(), e);
            }
            return List.of();
        }
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
        if (transformsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transformsList = (List<Map<String, Object>>) transformsObj;
            for (Map<String, Object> trMap : transformsList) {
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
            // Parse simple key=value,key2=value2 format
            TransformOp legacy = parseLegacyTransform(legacyTransform);
            if (legacy != null) {
                transforms.add(legacy);
            }
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

    private static TransformOp parseLegacyTransform(String transform) {
        // Parse simple "key=value,key2=value2" format
        // This is a fallback for old config style
        return new TransformOp.Rewrite(transform);
    }

    private static List<String> parseStringList(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String) {
                    result.add((String) item);
                }
            }
            return result;
        }
        if (obj instanceof String) {
            return List.of(((String) obj).split("\\s*,\\s*"));
        }
        return List.of();
    }

    private static String getString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static long getLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return fallback;
    }
}