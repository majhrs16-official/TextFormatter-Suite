package me.majhrs16.suite.spigothost.command;

import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.config.CommandsConfig;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Representa un comando dinámico individual con sus subcomandos.
 */
public final class DynamicCommand {

    private final DynamicCommandRegistrar registrar;
    private final String name;
    private final CommandsConfig.CommandNode node;

    public DynamicCommand(DynamicCommandRegistrar registrar, String name,
                          CommandsConfig.CommandNode node) {
        this.registrar = registrar;
        this.name = name;
        this.node = node;
    }

    public String name() { return name; }
    public CommandsConfig.CommandNode node() { return node; }

    public boolean execute(CommandSender sender, String[] args) {
        // Si no hay subcomando, ejecutar acción por defecto o mostrar ayuda
        if (args.length == 0) {
            if (node.ref().isEmpty()) {
                sendUsage(sender);
                return true;
            }
            return executeAction(sender, node.ref(), Map.of(), new String[0]);
        }

        // Buscar subcomando
        String sub = args[0];
        CommandsConfig.CommandNode child = node.children().get(sub);

        if (child != null) {
            // Subcomando explícito encontrado
            String[] subArgs = subArray(args, 1);
            Map<String, String> fixedArgs = child.fixedArgs();
            return executeAction(sender, child.ref(), fixedArgs, subArgs);
        }

        // Buscar binding de argumento dinámico (ej: <player>, <value>)
        for (Map.Entry<String, CommandsConfig.CommandNode> entry : node.children().entrySet()) {
            String pattern = entry.getKey();
            CommandsConfig.CommandNode childNode = entry.getValue();

            if (pattern.startsWith("<") && pattern.endsWith(">")) {
                String bindingName = pattern.substring(1, pattern.length() - 1);
                Map<String, String> fixedArgs = new java.util.HashMap<>(childNode.fixedArgs());
                fixedArgs.put(childNode.argBinding(), sub);
                return executeAction(sender, childNode.ref(), fixedArgs, subArray(args, 1));
            }
        }

        // No match: mostrar ayuda
        sendUsage(sender);
        return true;
    }

    private boolean executeAction(CommandSender sender, String actionRef,
                                  Map<String, String> fixedArgs, String[] remainingArgs) {
        CommandsConfig.ActionDef action = registrar.actions().get(actionRef);
        if (action == null) {
            sender.sendMessage("§cAcción no encontrada: " + actionRef);
            return true;
        }

        // Verificar permiso
        String requiredPerm = action.permission();
        if (actionRef.equals("reset") || actionRef.equals("edit") || actionRef.equals("get")) {
            requiredPerm = action.adminPermission();
        }
        if (requiredPerm != null && !sender.hasPermission(requiredPerm)) {
            sender.sendMessage("§cNo tienes permiso para esta acción.");
            return true;
        }

        // Confirmación si requerida
        if (action.confirm() && (remainingArgs.length == 0 || !remainingArgs[0].equalsIgnoreCase("confirm"))) {
            sender.sendMessage("§eEsta acción es irreversible. Ejecuta de nuevo con 'confirm' para confirmar.");
            return true;
        }

        // Preparar bindings para el script
        Map<String, String> bindings = new java.util.HashMap<>(fixedArgs);
        for (int i = 0; i < action.args().size() && i < remainingArgs.length; i++) {
            CommandsConfig.ArgDef argDef = action.args().get(i);
            bindings.put(argDef.name(), remainingArgs[i]);
        }

        // Ejecutar según acción (hardcodeado por ahora; TODO: script engine)
        return executeHardcoded(sender, actionRef, bindings);
    }

    private boolean executeHardcoded(CommandSender sender, String actionRef, Map<String, String> bindings) {
        SuiteHost host = registrar.host();
        UserLanguageStore languages = registrar.languages();
        PluginLogger logger = registrar.logger();
        MessageDispatcher dispatcher = registrar.dispatcher();
        TranslationService translation = registrar.translation();
        Path configDir = registrar.configDir();

        switch (actionRef) {
            case "reload" -> {
                registrar.plugin().reloadSuite();
                SuiteHost fresh = registrar.plugin().getRuntime() != null ? registrar.plugin().getRuntime().host : null;
                sender.sendMessage("§a[suite] Recargado: " +
                    (fresh != null ? fresh.channels().paths().size() : 0) + " canales, traductor '" +
                    (fresh != null ? fresh.translation().activeName() : "?") + "'");
                return true;
            }
            case "status" -> {
                SuiteHost h = registrar.host();
                sender.sendMessage("§a[suite] Canales: " + h.channels().paths());
                sender.sendMessage("§a[suite] Traductor: " + h.translation().activeName());
                sender.sendMessage("§a[suite] Engine: parallel=" + h.config().engineParallel() +
                    " sonido=" + h.config().soundEnabled() +
                    " claim=" + h.config().claimMode().name().toLowerCase(Locale.ROOT).replace('_', '-'));
                return true;
            }
            case "lang" -> {
                String targetName = bindings.getOrDefault("target", sender.getName());
                String value = bindings.getOrDefault("value", "auto");

                Player target = targetName.equals(sender.getName()) ? (sender instanceof Player p ? p : null)
                    : Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage("§cJugador no conectado: " + targetName);
                    return true;
                }

                if (!target.equals(sender) && !sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }

                String normalized = bindings.getOrDefault("value", "auto").toLowerCase(Locale.ROOT);
                if (!List.of("auto", "off").contains(normalized) && !isValidLangCode(normalized)) {
                    sender.sendMessage("§cValor inválido: usa auto | off | <código> (ej. es, en, zh-CN)");
                    return true;
                }

                languages.save(target.getUniqueId(), normalized);
                sender.sendMessage("§a[suite] Idioma de " + target.getName() + " actualizado a " + normalized);
                return true;
            }
            case "toggle" -> {
                String targetName = bindings.getOrDefault("target", sender.getName());
                Player target = targetName.equals(sender.getName()) ? (sender instanceof Player p ? p : null)
                    : Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage("§cJugador no conectado: " + targetName);
                    return true;
                }
                if (!target.equals(sender) && !sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }

                String current = languages.languageOf(target.getUniqueId()).orElse("auto");
                String flipped = "off".equals(current) ? "auto" : "off";
                languages.save(target.getUniqueId(), flipped);
                sender.sendMessage("§a[suite] Traducción de " + target.getName() + ": " + flipped);
                return true;
            }
            case "reset" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                try {
                    if (registrar.plugin().resetConfigs(configDir())) {
                        registrar.plugin().reloadSuite();
                        sender.sendMessage("§a[suite] Configs restauradas (backup en backup/)");
                    } else {
                        sender.sendMessage("§c[suite] Error: ver log");
                    }
                } catch (Exception e) {
                    sender.sendMessage("§c[suite] Error: " + e.getMessage());
                }
                return true;
            }
            case "test" -> {
                // Delegar al handler existente
                return registrar.plugin().handleTest(registrar.plugin().getRuntime(),
                    sender, bindings.getOrDefault("type", "full"));
            }
            case "edit" -> {
                String path = bindings.get("path");
                String value = bindings.get("value");
                if (path == null || value == null) {
                    sender.sendMessage("§cUso: /suite edit <path> <value>");
                    return true;
                }
                // TODO: implementar edición de config.yml
                sender.sendMessage("§c[edit] No implementado aún");
                return true;
            }
            case "get" -> {
                String path = bindings.get("path");
                if (path == null) {
                    sender.sendMessage("§cUso: /suite get <path>");
                    return true;
                }
                // TODO: implementar lectura de config.yml
                sender.sendMessage("§c[get] No implementado aún");
                return true;
            }
            default -> {
                sender.sendMessage("§cAcción no implementada: " + actionRef);
                return true;
            }
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§eUso: /" + name + " <" + String.join("|", node.children().keySet()) + ">");
    }

    private String[] subArray(String[] arr, int start) {
        if (start >= arr.length) return new String[0];
        String[] result = new String[arr.length - start];
        System.arraycopy(arr, start, result, 0, result.length);
        return result;
    }

    private boolean isValidLangCode(String code) {
        try {
            java.util.Locale.forLanguageTag(code);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}