package me.majhrs16.suite.spigothost.command;

import me.majhrs16.suite.messages.MessagesCatalog;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.DispatchReport;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.config.CommandsConfig;
import me.majhrs16.suite.manager.ModuleCoordinate;
import me.majhrs16.suite.manager.ModuleDescriptor;
import me.majhrs16.suite.manager.ModuleLifecycle;
import me.majhrs16.suite.manager.Environment;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Representa un comando dinámico individual con sus subcomandos.
 */
public final class DynamicCommand extends org.bukkit.command.Command {

    private final DynamicCommandRegistrar registrar;
    private final String name;
    private final CommandsConfig.CommandNode node;

    public DynamicCommand(DynamicCommandRegistrar registrar, String name,
                          CommandsConfig.CommandNode node) {
        super(name);
        this.registrar = registrar;
        this.name = name;
        this.node = node;
    }

    public CommandsConfig.CommandNode node() { return node; }

    private me.majhrs16.suite.manager.ModuleLifecycle moduleLifecycle() {
        return registrar.moduleLifecycle();
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
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
                SuiteHost fresh = registrar.host();
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reload-ok",
                    fresh != null ? fresh.channels().paths().size() : 0,
                    fresh != null ? fresh.translation().activeName() : "?"));
                return true;
            }
            case "status" -> {
                SuiteHost h = registrar.host();
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.channels", h.channels().paths()));
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.translator", h.translation().activeName()));
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "status.knobs",
                    h.config().engineParallel(), h.config().soundEnabled(),
                    h.config().claimMode().name().toLowerCase(Locale.ROOT).replace('_', '-')));
                return true;
            }
            case "lang" -> {
                String targetName = bindings.getOrDefault("target", sender.getName());
                String value = bindings.getOrDefault("value", "auto");

                Player target = targetName.equals(sender.getName()) ? (sender instanceof Player p ? p : null)
                    : Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.player-offline", targetName));
                    return true;
                }

                if (!target.equals(sender) && !sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                    return true;
                }

                String normalized = bindings.getOrDefault("value", "auto").toLowerCase(Locale.ROOT);
                if (!List.of("auto", "off").contains(normalized) && !isValidLangCode(normalized)) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.invalid"));
                    return true;
                }

                languages.save(target.getUniqueId(), normalized);
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.updated", normalized));
                return true;
            }
            case "toggle" -> {
                String targetName = bindings.getOrDefault("target", sender.getName());
                Player target = targetName.equals(sender.getName()) ? (sender instanceof Player p ? p : null)
                    : Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.player-offline", targetName));
                    return true;
                }
                if (!target.equals(sender) && !sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                    return true;
                }

                String current = languages.languageOf(target.getUniqueId()).orElse("auto");
                String flipped = "off".equals(current) ? "auto" : "off";
                languages.save(target.getUniqueId(), flipped);
                sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "toggle.current", flipped));
                return true;
            }
            case "reset" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "lang.other-admin"));
                    return true;
                }
                try {
                    if (registrar.plugin().resetConfigs(configDir)) {
                        registrar.plugin().reloadSuite();
                        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.ok"));
                    } else {
                        sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.error", "ver log"));
                    }
                } catch (Exception e) {
                    sender.sendMessage(MessagesCatalog.getInstance().format(Locale.ENGLISH, "reset.error", e.getMessage()));
                }
                return true;
            }
            case "test" -> {
                // Delegar al handler existente
                return registrar.plugin().handleTest(registrar.host(), sender, bindings.getOrDefault("type", "full"));
            }
            case "health" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                var healthRegistry = registrar.healthChecks();
                var results = healthRegistry.getLastResults();
                if (results.isEmpty()) {
                    sender.sendMessage("§a[Health] Status: UNKNOWN (no checks run yet)");
                    sender.sendMessage("§e[Health] Ejecuta /suite health check para ejecutar los checks");
                    return true;
                }
                var overall = healthRegistry.getOverallStatus();
                sender.sendMessage("§a[Health] Status: " + overall.name());
                for (Map.Entry<String, me.majhrs16.suite.observability.health.HealthCheckRegistry.HealthCheckResult> entry : results.entrySet()) {
                    var check = entry.getValue();
                    sender.sendMessage("  §e" + entry.getKey() + ": §f" + check.status().name() + " - " + check.message());
                }
                return true;
            }
            case "metrics" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                sender.sendMessage("§a[Metrics] Endpoint disponible en: http://localhost:9090/metrics");
                sender.sendMessage("§a[Health] Endpoint disponible en: http://localhost:9090/health");
                sender.sendMessage("§a[Debug] Endpoint disponible en: http://localhost:9091/debug/simulate");
                return true;
            }
            case "module" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                ModuleLifecycle ml = moduleLifecycle();
                if (ml == null) {
                    sender.sendMessage("§c[Module] Manager no inicializado");
                    return true;
                }
                String action = bindings.getOrDefault("action", "list");
                String module = bindings.getOrDefault("module", "");
                String version = bindings.getOrDefault("version", "");

                switch (action) {
                    case "list" -> {
                        List<ModuleDescriptor> loaded = ml.getLoadedModules();
                        sender.sendMessage("§a[Module] Módulos instalados (" + loaded.size() + "):");
                        if (loaded.isEmpty()) {
                            sender.sendMessage("  §7(none)");
                        } else {
                            for (ModuleDescriptor d : loaded) {
                                sender.sendMessage("  §a✓ §f" + d.id() + " §7v" + d.version() + " - " + d.description());
                            }
                        }

                        // Show available modules from GitHub
                        sender.sendMessage("§e[Module] Módulos disponibles:");
                        List<ModuleCoordinate> available = ml.discoverAvailableModules();
                        if (available.isEmpty()) {
                            sender.sendMessage("  §7(none - check GitHub connectivity)");
                        } else {
                            for (ModuleCoordinate coord : available) {
                                String artifact = coord.artifact();
                                String coordVersion = coord.version().toString();
                                boolean installed = loaded.stream().anyMatch(d -> d.coordinate().artifact().equals(artifact));
                                String status = installed ? "§a✓" : "§c✗";
                                sender.sendMessage("  " + status + " §f" + artifact + " §7v" + coordVersion + (installed ? " §a(instalado)" : ""));
                            }
                        }

                        // Check for updates
                        List<ModuleCoordinate> updates = ml.checkUpdates();
                        if (!updates.isEmpty()) {
                            sender.sendMessage("§6[Module] Actualizaciones disponibles: " + updates.size());
                            for (ModuleCoordinate u : updates) {
                                sender.sendMessage("  §6- §f" + u);
                            }
                        }
                        return true;
                    }
                    case "install" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module install <módulo> [versión]");
                            return true;
                        }
                        sender.sendMessage("§a[Module] Resolviendo " + module + (version.isBlank() ? "" : ":" + version) + "...");
                        String coordStr = "me.majhrs16:" + module + (version.isBlank() ? "" : ":" + version);
                        ModuleCoordinate coord = ModuleCoordinate.of("me.majhrs16", module, version.isBlank() ? "latest" : version);
                        // Run async to avoid blocking main thread
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                // Use reflection to access getCurrentEnvironment from DefaultModuleLifecycle
                                java.lang.reflect.Method envMethod = ml.getClass().getMethod("getCurrentEnvironment");
                                Object env = envMethod.invoke(ml);
                                ModuleLifecycle.ResolutionResult result = ml.resolve(coord, (Environment) env, false);
                                if (result instanceof ModuleLifecycle.ResolutionResult.Failure fail) {
                                    sender.sendMessage("§c[Module] Error resolviendo: " + fail.reason());
                                    return;
                                }
                                ModuleLifecycle.ResolutionResult.Success success = (ModuleLifecycle.ResolutionResult.Success) result;
                                sender.sendMessage("§a[Module] Descargando " + success.module().descriptor().id() + "...");
                                List<Path> jars = ml.download(success.module(), success.dependencies(), ml.getCacheDir());
                                sender.sendMessage("§a[Module] Reubicando dependencias...");
                                Path relocated = ml.relocate(jars.get(0), ml.getCacheDir(), getRelocationsForModule(success.module().descriptor()));
                                sender.sendMessage("§a[Module] Cargando módulo...");
                                ClassLoader cl = ml.load(relocated, jars.subList(1, jars.size()), registrar.plugin().getClass().getClassLoader());
                                sender.sendMessage("§a[Module] Registrando módulo...");
                                if (ml.register(cl, success.module().descriptor())) {
                                    sender.sendMessage("§a[Module] ✓ " + success.module().descriptor().id() + " instalado y registrado correctamente");
                                } else {
                                    sender.sendMessage("§c[Module] Falló el registro del módulo");
                                }
                            } catch (Exception e) {
                                logger.error("Module install failed: " + module, e);
                                sender.sendMessage("§c[Module] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "update" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module update <módulo> [versión]");
                            return true;
                        }
                        sender.sendMessage("§a[Module] Buscando actualización para " + module + "...");
                        // Check if module is installed
                        List<ModuleDescriptor> loaded = ml.getLoadedModules();
                        ModuleDescriptor existing = loaded.stream()
                            .filter(d -> d.coordinate().artifact().equals(module))
                            .findFirst().orElse(null);
                        if (existing == null) {
                            sender.sendMessage("§c[Module] Módulo no instalado: " + module);
                            return true;
                        }
                        String coordStr = "me.majhrs16:" + module + (version.isBlank() ? "" : ":" + version);
                        ModuleCoordinate coord = ModuleCoordinate.of("me.majhrs16", module, version.isBlank() ? "latest" : version);
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                java.lang.reflect.Method envMethod = ml.getClass().getMethod("getCurrentEnvironment");
                                Object env = envMethod.invoke(ml);
                                ModuleLifecycle.ResolutionResult result = ml.resolve(coord, (Environment) env, false);
                                if (result instanceof ModuleLifecycle.ResolutionResult.Failure fail) {
                                    sender.sendMessage("§c[Module] Error resolviendo: " + fail.reason());
                                    return;
                                }
                                ModuleLifecycle.ResolutionResult.Success success = (ModuleLifecycle.ResolutionResult.Success) result;
                                // Check if version is actually newer
                                if (success.module().descriptor().version().compareTo(existing.version()) <= 0) {
                                    sender.sendMessage("§e[Module] Ya tienes la versión más reciente: " + existing.version());
                                    return;
                                }
                                sender.sendMessage("§a[Module] Descargando actualización " + success.module().descriptor().id() + "...");
                                List<Path> jars = ml.download(success.module(), success.dependencies(), ml.getCacheDir());
                                sender.sendMessage("§a[Module] Reubicando dependencias...");
                                Path relocated = ml.relocate(jars.get(0), ml.getCacheDir(), getRelocationsForModule(success.module().descriptor()));
                                sender.sendMessage("§a[Module] Cargando módulo actualizado...");
                                ClassLoader cl = ml.load(relocated, jars.subList(1, jars.size()), registrar.plugin().getClass().getClassLoader());
                                // Unregister old first
                                ml.unregister(existing.id());
                                ml.unload(existing.id());
                                sender.sendMessage("§a[Module] Registrando nueva versión...");
                                if (ml.register(cl, success.module().descriptor())) {
                                    sender.sendMessage("§a[Module] ✓ " + success.module().descriptor().id() + " actualizado correctamente (v" + existing.version() + " → v" + success.module().descriptor().version() + ")");
                                } else {
                                    sender.sendMessage("§c[Module] Falló el registro de la actualización");
                                }
                            } catch (Exception e) {
                                logger.error("Module update failed: " + module, e);
                                sender.sendMessage("§c[Module] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "remove" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module remove <módulo>");
                            return true;
                        }
                        // Check if module is installed
                        List<ModuleDescriptor> loaded = ml.getLoadedModules();
                        ModuleDescriptor existing = loaded.stream()
                            .filter(d -> d.coordinate().artifact().equals(module))
                            .findFirst().orElse(null);
                        if (existing == null) {
                            sender.sendMessage("§c[Module] Módulo no instalado: " + module);
                            return true;
                        }
                        sender.sendMessage("§a[Module] Eliminando " + module + "...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                ml.unregister(existing.id());
                                ml.unload(existing.id());
                                sender.sendMessage("§a[Module] ✓ " + module + " eliminado correctamente");
                            } catch (Exception e) {
                                logger.error("Module remove failed: " + module, e);
                                sender.sendMessage("§c[Module] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "info" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module info <módulo>");
                            return true;
                        }
                        // Check loaded modules first
                        List<ModuleDescriptor> loaded = ml.getLoadedModules();
                        ModuleDescriptor existing = loaded.stream()
                            .filter(d -> d.coordinate().artifact().equals(module))
                            .findFirst().orElse(null);
                        if (existing != null) {
                            sender.sendMessage("§a[Module] Información (instalado):");
                            sender.sendMessage("  §fID: §a" + existing.id());
                            sender.sendMessage("  §fVersión: §a" + existing.version());
                            sender.sendMessage("  §fDescripción: §7" + existing.description());
                            sender.sendMessage("  §fAutor: §7" + existing.author());
                            sender.sendMessage("  §fCore API requerida: §7" + existing.requiredCoreApi());
                            sender.sendMessage("  §fJava: §7" + existing.minJavaVersion() + " - " + existing.maxJavaVersion());
                            sender.sendMessage("  §fPlataformas: §7" + String.join(", ", existing.supportedPlatforms()));
                            sender.sendMessage("  §fDependencias: §7" + (existing.dependencies().isEmpty() ? "(none)" : String.join(", ", existing.dependencies())));
                            return true;
                        }
                        // Check available modules
                        List<ModuleCoordinate> available = ml.discoverAvailableModules();
                        ModuleCoordinate availableCoord = available.stream()
                            .filter(c -> c.artifact().equals(module))
                            .findFirst().orElse(null);
                        if (availableCoord != null) {
                            sender.sendMessage("§e[Module] Información (disponible, no instalado):");
                            sender.sendMessage("  §fID: §e" + availableCoord.group() + ":" + availableCoord.artifact() + ":" + availableCoord.version());
                            sender.sendMessage("  §fEstado: §cNo instalado");
                            return true;
                        }
                        sender.sendMessage("§c[Module] Módulo no encontrado: " + module);
                        return true;
                    }
                    default -> {
                        sender.sendMessage("§cSubcomando desconocido: " + action + ". Usa: install, update, list, remove, info");
                        return true;
                    }
                }
            }
            case "suite" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                boolean force = Boolean.parseBoolean(bindings.getOrDefault("force", "false"));
                sender.sendMessage("§a[Suite] Iniciando actualización completa" + (force ? " (forzada)" : "") + "...");
                ModuleLifecycle ml = moduleLifecycle();
                if (ml == null) {
                    sender.sendMessage("§c[Suite] Manager no inicializado");
                    return true;
                }
                Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                    try {
                        java.lang.reflect.Method envMethod = ml.getClass().getMethod("getCurrentEnvironment");
                        Object env = envMethod.invoke(ml);
                        List<ModuleCoordinate> updated = ml.updateSuite((Environment) env, force);
                        if (updated.isEmpty()) {
                            sender.sendMessage("§a[Suite] ✓ No hay actualizaciones disponibles");
                        } else {
                            sender.sendMessage("§a[Suite] ✓ Actualización completa: " + updated.size() + " módulos actualizados");
                            for (ModuleCoordinate u : updated) {
                                sender.sendMessage("  §a- §f" + u);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Suite update failed", e);
                        sender.sendMessage("§c[Suite] Error: " + e.getMessage());
                    }
                });
                return true;
            }
            case "edit" -> {
                sender.sendMessage("§c[Config] Edición de config no implementada (use el editor web o edite config.yml directamente)");
                return true;
            }
            case "get" -> {
                sender.sendMessage("§c[Config] Lectura de config no implementada (use el editor web o vea config.yml directamente)");
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

    private java.util.Map<String, String> getRelocationsForModule(ModuleDescriptor desc) {
        java.util.Map<String, String> relocations = new java.util.HashMap<>();
        String base = "me.majhrs16.suite." + desc.coordinate().artifact().replace("suite-", "");
        relocations.put(base, base + ".relocated");
        relocations.put("org.apache.commons", "me.majhrs16.suite.relocated.org.apache.commons");
        relocations.put("com.google", "me.majhrs16.suite.relocated.com.google");
        relocations.put("org.yaml", "me.majhrs16.suite.relocated.org.yaml");
        relocations.put("com.fasterxml.jackson", "me.majhrs16.suite.relocated.com.fasterxml.jackson");
        return relocations;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 0) {
            return new java.util.ArrayList<>(node.children().keySet());
        }
        
        String lastArg = args[args.length - 1];
        String prefix = lastArg.toLowerCase(java.util.Locale.ROOT);
        
        return node.children().keySet().stream()
            .filter(key -> key.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
            .collect(java.util.stream.Collectors.toList());
    }
}