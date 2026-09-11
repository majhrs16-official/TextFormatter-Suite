package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Carga la configuración de comandos dinámicos (commands.yml v2).
 */
public final class CommandsConfigLoader {

    private static final Yaml YAML = new Yaml();

    private CommandsConfigLoader() {}

    /**
     * Carga commands.yml desde el directorio de configuración.
     * Retorna Optional.empty() si no existe (usar defaults hardcodeados).
     */
    public static Optional<CommandsConfig> load(Path configDir, PluginLogger logger) {
        Path file = configDir.resolve("commands.yml");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file);
            Object root = YAML.load(content);
            if (!(root instanceof Map)) {
                logger.warn("commands.yml: raíz debe ser un mapa; ignorando");
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;

            String baseName = str(map.get("base-name"), "suite");
            List<String> aliases = (List<String>) map.getOrDefault("aliases", List.of());

            // Parse actions
            Map<String, CommandsConfig.ActionDef> actions = new java.util.LinkedHashMap<>();
            Object actionsObj = map.get("actions");
            if (actionsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> actionsMap = (Map<String, Object>) actionsObj;
                for (Map.Entry<String, Object> entry : actionsMap.entrySet()) {
                    String name = entry.getKey();
                    Object def = entry.getValue();
                    if (def instanceof Map) {
                        actions.put(name, parseAction(name, (Map<String, Object>) def));
                    }
                }
            }

            // Parse command tree
            Map<String, CommandsConfig.CommandNode> commands = new java.util.LinkedHashMap<>();
            Object commandsObj = map.get("commands");
            if (commandsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> commandsMap = (Map<String, Object>) commandsObj;
                for (Map.Entry<String, Object> entry : commandsMap.entrySet()) {
                    String name = entry.getKey();
                    Object node = entry.getValue();
                    if (node instanceof Map) {
                        commands.put(name, parseCommandNode(name, (Map<String, Object>) node));
                    }
                }
            }

            return Optional.of(new CommandsConfig(baseName, aliases, actions, commands));
        } catch (Exception e) {
            logger.error("Error cargando commands.yml: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static CommandsConfig.ActionDef parseAction(String name, Map<String, Object> map) {
        String description = str(map.get("description"), "");
        String permission = str(map.get("permission"), "textformattersuite.user");
        String adminPermission = str(map.get("admin-permission"), "textformattersuite.admin");
        boolean confirm = bool(map.get("confirm"), false);
        String execute = str(map.get("execute"), "");

        List<CommandsConfig.ArgDef> args = List.of();
        Object argsObj = map.get("args");
        if (argsObj instanceof List) {
            List<CommandsConfig.ArgDef> parsed = new java.util.ArrayList<>();
            for (Object arg : (List<?>) argsObj) {
                if (arg instanceof Map) {
                    parsed.add(parseArg((Map<String, Object>) arg));
                }
            }
            args = List.copyOf(parsed);
        }

        return new CommandsConfig.ActionDef(description, permission, adminPermission, args, confirm, execute);
    }

    private static CommandsConfig.ArgDef parseArg(Map<String, Object> map) {
        String name = str(map.get("name"), "");
        String type = str(map.get("type"), "string");
        String description = str(map.get("description"), "");
        String defaultValue = str(map.get("default"), "");
        return new CommandsConfig.ArgDef(name, type, description, defaultValue);
    }

    private static CommandsConfig.CommandNode parseCommandNode(String name, Map<String, Object> map) {
        String description = str(map.get("description"), "");
        String permission = str(map.get("permission"), "textformattersuite.user");
        String ref = str(map.get("ref"), "");

        Map<String, String> fixedArgs = Map.of();
        Object fixedArgsObj = map.get("fixed-args");
        if (fixedArgsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> fa = (Map<String, String>) fixedArgsObj;
            fixedArgs = Map.copyOf(fa);
        }

        String argBinding = str(map.get("arg-binding"), "");

        Map<String, CommandsConfig.CommandNode> children = Map.of();
        Object childrenObj = map.get("children");
        if (childrenObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> childrenMap = (Map<String, Object>) childrenObj;
            Map<String, CommandsConfig.CommandNode> parsed = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : childrenMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    parsed.put(entry.getKey(), parseCommandNode(entry.getKey(), (Map<String, Object>) entry.getValue()));
                }
            }
            children = Map.copyOf(parsed);
        }

        return new CommandsConfig.CommandNode(
            description, permission, ref, fixedArgs, argBinding, children
        );
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }
}