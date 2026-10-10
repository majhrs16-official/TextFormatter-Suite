package me.majhrs16.suite.spigothost.command;

import me.majhrs16.suite.messages.MessagesCatalog;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.DispatchReport;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.config.CommandsConfig;
import me.majhrs16.suite.manager.ModuleDescriptor;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.apt.RepositoryManager;
import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.FileEntry;

import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.nio.file.Files;
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
        ManagerFacade facade = registrar.managerFacade();
        if (facade == null) return null;
        return new me.majhrs16.suite.manager.apt.ModuleLifecycleAdapter(facade, registrar.logger());
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
                ManagerFacade mf = registrar.managerFacade();
                if (mf == null) {
                    sender.sendMessage("§c[Module] Manager no inicializado");
                    return true;
                }
                String action = bindings.getOrDefault("action", "list");
                String module = bindings.getOrDefault("module", "");
                String version = bindings.getOrDefault("version", "");
                String channel = bindings.getOrDefault("channel", "stable");
                boolean force = Boolean.parseBoolean(bindings.getOrDefault("force", "false"));

                switch (action) {
                    case "list" -> {
                        // List installed packages
                        List<PackageEntry> installed = mf.packageList();
                        sender.sendMessage("§a[Package] Paquetes instalados (" + installed.size() + "):");
                        if (installed.isEmpty()) {
                            sender.sendMessage("  §7(none)");
                        } else {
                            for (PackageEntry e : installed) {
                                sender.sendMessage("  §a✓ §f" + e.name() + " §7v" + e.version() + " (" + e.channel() + ") - " + e.type());
                            }
                        }

                        // Show available packages from repositories
                        sender.sendMessage("§e[Package] Paquetes disponibles:");
                        Map<String, List<RepositoryManager.PackageCandidate>> available = mf.getAvailablePackages();
                        if (available.isEmpty()) {
                            sender.sendMessage("  §7(none - run /suite repo update)");
                        } else {
                            for (Map.Entry<String, List<RepositoryManager.PackageCandidate>> entry : available.entrySet()) {
                                RepositoryManager.PackageCandidate best = entry.getValue().get(0);
                                boolean isInstalled = installed.stream().anyMatch(p -> p.name().equals(entry.getKey()));
                                String status = isInstalled ? "§a✓" : "§c✗";
                                sender.sendMessage("  " + status + " §f" + entry.getKey() + " §7v" + best.version() + " (" + best.channel() + ") from " + best.repository() + (isInstalled ? " §a(instalado)" : ""));
                            }
                        }

                        // Check for updates
                        List<PackageEntry> updates = mf.checkUpdates();
                        if (!updates.isEmpty()) {
                            sender.sendMessage("§6[Package] Actualizaciones disponibles: " + updates.size());
                            for (PackageEntry u : updates) {
                                sender.sendMessage("  §6- §f" + u.name() + " v" + u.version() + " (" + u.channel() + ")");
                            }
                        }
                        return true;
                    }
                    case "install" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module install <paquete> [versión] [channel]");
                            return true;
                        }
                        sender.sendMessage("§a[Package] Instalando " + module + (version.isBlank() ? "" : ":" + version) + " (" + channel + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.InstallResult result = mf.repoInstall(List.of(module), version.isBlank() ? "*" : version, channel, force);
                                if (result.success()) {
                                    for (String pkg : result.packages()) {
                                        sender.sendMessage("§a[Package] ✓ " + pkg + " instalado correctamente");
                                    }
                                } else {
                                    sender.sendMessage("§c[Package] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Package install failed: " + module, e);
                                sender.sendMessage("§c[Package] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "remove" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module remove <paquete> [versión] [channel]");
                            return true;
                        }
                        sender.sendMessage("§a[Package] Eliminando " + module + (version.isBlank() ? "" : ":" + version) + " (" + channel + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.RemoveResult result = mf.repoRemove(module, version.isBlank() ? "any" : version, channel, true);
                                if (result.success()) {
                                    sender.sendMessage("§a[Package] ✓ " + result.packageName() + " eliminado correctamente");
                                } else {
                                    sender.sendMessage("§c[Package] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Package remove failed: " + module, e);
                                sender.sendMessage("§c[Package] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "info" -> {
                        if (module.isBlank()) {
                            sender.sendMessage("§cUso: /suite module info <paquete> [versión] [channel]");
                            return true;
                        }
                        // Check installed packages first
                        List<PackageEntry> installed = mf.packageList();
                        Optional<PackageEntry> existing = installed.stream()
                                .filter(p -> p.name().equals(module) && (version.isBlank() || p.version().equals(version)))
                                .findFirst();
                        if (existing.isPresent()) {
                            PackageEntry e = existing.get();
                            sender.sendMessage("§a[Package] Información (instalado):");
                            sender.sendMessage("  §fNombre: §a" + e.name());
                            sender.sendMessage("  §fVersión: §a" + e.version());
                            sender.sendMessage("  §fCanal: §a" + e.channel());
                            sender.sendMessage("  §fTipo: §a" + e.type());
                            sender.sendMessage("  §fArquitectura: §a" + e.arch());
                            sender.sendMessage("  §fPlataforma: §a" + e.platform());
                            sender.sendMessage("  §fOS: §a" + e.os());
                            sender.sendMessage("  §fSHA256: §a" + e.sha256());
                            sender.sendMessage("  §fTamaño: §a" + e.size() + " bytes");
                            sender.sendMessage("  §fDependencias: §7" + (e.dependencies().isEmpty() ? "(none)" : String.join(", ", e.dependencies())));
                            sender.sendMessage("  §fArchivos: §7" + e.files().size());
                            return true;
                        }
                        // Check available packages
                        Map<String, List<RepositoryManager.PackageCandidate>> available = mf.getAvailablePackages();
                        List<RepositoryManager.PackageCandidate> candidates = available.get(module);
                        if (candidates != null && !candidates.isEmpty()) {
                            RepositoryManager.PackageCandidate c = candidates.get(0);
                            sender.sendMessage("§e[Package] Información (disponible, no instalado):");
                            sender.sendMessage("  §fNombre: §e" + c.name());
                            sender.sendMessage("  §fVersión: §e" + c.version());
                            sender.sendMessage("  §fCanal: §e" + c.channel());
                            sender.sendMessage("  §fRepositorio: §e" + c.repository());
                            sender.sendMessage("  §fURL: §e" + c.downloadUrl());
                            sender.sendMessage("  §fTamaño: §e" + c.size() + " bytes");
                            sender.sendMessage("  §fSHA256: §e" + c.sha256());
                            return true;
                        }
                        sender.sendMessage("§c[Package] Paquete no encontrado: " + module);
                        return true;
                    }
                    default -> {
                        sender.sendMessage("§cSubcomando desconocido: " + action + ". Usa: list, install, remove, info");
                        return true;
                    }
                }
            }
            case "repo" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                ManagerFacade mf = registrar.managerFacade();
                if (mf == null) {
                    sender.sendMessage("§c[Repo] Manager no inicializado");
                    return true;
                }
                String action = bindings.getOrDefault("action", "update");
                String pkg = bindings.getOrDefault("package", "");
                String version = bindings.getOrDefault("version", "");
                String channel = bindings.getOrDefault("channel", "stable");
                boolean force = Boolean.parseBoolean(bindings.getOrDefault("force", "false"));
                boolean autoRemove = Boolean.parseBoolean(bindings.getOrDefault("auto-remove", "true"));

                switch (action) {
                    case "update" -> {
                        sender.sendMessage("§a[Repo] Actualizando índices de repositorios...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                RepositoryManager.UpdateResult result = mf.repoUpdate();
                                sender.sendMessage("§a[Repo] Índices actualizados: " + result.updatedCount());
                                if (result.hasFailures()) {
                                    for (Map.Entry<String, String> failure : result.failed.entrySet()) {
                                        sender.sendMessage("§c[Repo] Fallo en " + failure.getKey() + ": " + failure.getValue());
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Repo update failed", e);
                                sender.sendMessage("§c[Repo] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "install" -> {
                        if (pkg.isBlank()) {
                            sender.sendMessage("§cUso: /suite repo install <paquete> [versión] [channel]");
                            return true;
                        }
                        sender.sendMessage("§a[Repo] Instalando " + pkg + (version.isBlank() ? "" : ":" + version) + " (" + channel + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.InstallResult result = mf.repoInstall(List.of(pkg), version.isBlank() ? "*" : version, channel, force);
                                if (result.success()) {
                                    for (String p : result.packages()) {
                                        sender.sendMessage("§a[Repo] ✓ " + p + " instalado correctamente");
                                    }
                                } else {
                                    sender.sendMessage("§c[Repo] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Repo install failed: " + pkg, e);
                                sender.sendMessage("§c[Repo] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "remove" -> {
                        if (pkg.isBlank()) {
                            sender.sendMessage("§cUso: /suite repo remove <paquete> [versión] [channel]");
                            return true;
                        }
                        sender.sendMessage("§a[Repo] Eliminando " + pkg + (version.isBlank() ? "" : ":" + version) + " (" + channel + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.RemoveResult result = mf.repoRemove(pkg, version.isBlank() ? "any" : version, channel, autoRemove);
                                if (result.success()) {
                                    sender.sendMessage("§a[Repo] ✓ " + result.packageName() + " eliminado correctamente");
                                } else {
                                    sender.sendMessage("§c[Repo] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Repo remove failed: " + pkg, e);
                                sender.sendMessage("§c[Repo] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "upgrade" -> {
                        sender.sendMessage("§a[Repo] Actualizando todos los paquetes...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.InstallResult result = mf.repoUpgrade();
                                if (result.success()) {
                                    if (result.packages().isEmpty()) {
                                        sender.sendMessage("§a[Repo] ✓ No hay actualizaciones disponibles");
                                    } else {
                                        sender.sendMessage("§a[Repo] ✓ Actualización completa: " + result.packages().size() + " paquetes actualizados");
                                        for (String u : result.packages()) {
                                            sender.sendMessage("  §a- §f" + u);
                                        }
                                    }
                                } else {
                                    sender.sendMessage("§c[Repo] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Repo upgrade failed", e);
                                sender.sendMessage("§c[Repo] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "fix-broken" -> {
                        sender.sendMessage("§a[Repo] Reparando dependencias rotas...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                AptInstaller.InstallResult result = mf.repoFixBroken();
                                if (result.success()) {
                                    if (result.packages().isEmpty()) {
                                        sender.sendMessage("§a[Repo] ✓ No hay dependencias rotas");
                                    } else {
                                        sender.sendMessage("§a[Repo] ✓ Reparación completa: " + result.packages().size() + " paquetes instalados");
                                        for (String u : result.packages()) {
                                            sender.sendMessage("  §a- §f" + u);
                                        }
                                    }
                                } else {
                                    sender.sendMessage("§c[Repo] Error: " + result.error());
                                }
                            } catch (Exception e) {
                                logger.error("Repo fix-broken failed", e);
                                sender.sendMessage("§c[Repo] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    default -> {
                        sender.sendMessage("§cSubcomando desconocido: " + action + ". Usa: update, install, remove, upgrade, fix-broken");
                        return true;
                    }
                }
            }
            case "package" -> {
                if (!sender.hasPermission("textformattersuite.admin")) {
                    sender.sendMessage("§cPermiso de admin requerido");
                    return true;
                }
                ManagerFacade mf = registrar.managerFacade();
                if (mf == null) {
                    sender.sendMessage("§c[Package] Manager no inicializado");
                    return true;
                }
                String action = bindings.getOrDefault("action", "list");
                String pkg = bindings.getOrDefault("package", "");
                String file = bindings.getOrDefault("file", "");
                String version = bindings.getOrDefault("version", "");
                String channel = bindings.getOrDefault("channel", "stable");
                boolean force = Boolean.parseBoolean(bindings.getOrDefault("force", "false"));

                switch (action) {
                    case "install" -> {
                        if (file.isBlank()) {
                            sender.sendMessage("§cUso: /suite package install <archivo.zip>");
                            return true;
                        }
                        Path zipPath = Path.of(file).toAbsolutePath();
                        if (!Files.exists(zipPath)) {
                            sender.sendMessage("§c[Package] Archivo no encontrado: " + file);
                            return true;
                        }
                        sender.sendMessage("§a[Package] Instalando paquete local: " + file);
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                List<PackageEntry> entries = mf.packageInstall(zipPath, force);
                                for (PackageEntry e : entries) {
                                    sender.sendMessage("§a[Package] ✓ " + e.name() + " v" + e.version() + " (" + e.channel() + ") instalado correctamente");
                                }
                            } catch (Exception e) {
                                logger.error("Package local install failed: " + file, e);
                                sender.sendMessage("§c[Package] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "list" -> {
                        List<PackageEntry> installed = mf.packageList();
                        sender.sendMessage("§a[Package] Paquetes instalados (" + installed.size() + "):");
                        if (installed.isEmpty()) {
                            sender.sendMessage("  §7(none)");
                        } else {
                            for (PackageEntry e : installed) {
                                sender.sendMessage("  §a✓ §f" + e.name() + " §7v" + e.version() + " (" + e.channel() + ") - " + e.type() + " - " + e.files().size() + " archivos");
                            }
                        }
                        return true;
                    }
                    case "remove" -> {
                        if (pkg.isBlank()) {
                            sender.sendMessage("§cUso: /suite package remove <paquete> [versión] [channel]");
                            return true;
                        }
                        sender.sendMessage("§a[Package] Eliminando " + pkg + (version.isBlank() ? "" : ":" + version) + " (" + channel + ")...");
                        Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                            try {
                                mf.packageRemove(pkg, version.isBlank() ? "any" : version, channel, true);
                                sender.sendMessage("§a[Package] ✓ " + pkg + " eliminado correctamente");
                            } catch (Exception e) {
                                logger.error("Package remove failed: " + pkg, e);
                                sender.sendMessage("§c[Package] Error: " + e.getMessage());
                            }
                        });
                        return true;
                    }
                    case "files" -> {
                        if (pkg.isBlank()) {
                            sender.sendMessage("§cUso: /suite package files <paquete> [versión] [channel]");
                            return true;
                        }
                        List<me.majhrs16.suite.manager.spi.FileEntry> files = mf.packageFiles(pkg, version.isBlank() ? "any" : version, channel);
                        if (files.isEmpty()) {
                            sender.sendMessage("§c[Package] Paquete no encontrado o sin archivos: " + pkg);
                            return true;
                        }
                        sender.sendMessage("§a[Package] Archivos de " + pkg + " (" + files.size() + "):");
                        for (me.majhrs16.suite.manager.spi.FileEntry f : files) {
                            sender.sendMessage("  §7- §f" + f.path() + " §7(" + f.size() + " bytes, SHA256: " + f.sha256().substring(0, 16) + "...)");
                        }
                        return true;
                    }
                    case "verify" -> {
                        if (pkg.isBlank()) {
                            sender.sendMessage("§cUso: /suite package verify <paquete> [versión] [channel]");
                            return true;
                        }
                        LocalPackageDatabase.VerificationResult result = mf.packageVerify(pkg, version.isBlank() ? "any" : version, channel);
                        if (!result.valid()) {
                            sender.sendMessage("§c[Package] Verificación fallida para " + pkg + ":");
                            for (LocalPackageDatabase.VerificationIssue issue : result.issues()) {
                                sender.sendMessage("  §c- §f" + issue.file() + ": §c" + issue.type() + " - " + issue.message());
                            }
                        } else {
                            sender.sendMessage("§a[Package] ✓ Verificación exitosa para " + pkg);
                        }
                        return true;
                    }
                    default -> {
                        sender.sendMessage("§cSubcomando desconocido: " + action + ". Usa: install, list, remove, files, verify");
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
                ManagerFacade mf = registrar.managerFacade();
                if (mf == null) {
                    sender.sendMessage("§c[Suite] Manager no inicializado");
                    return true;
                }
                Bukkit.getScheduler().runTaskAsynchronously(registrar.plugin(), () -> {
                    try {
                        AptInstaller.InstallResult result = mf.repoUpgrade();
                        if (result.success()) {
                            if (result.packages().isEmpty()) {
                                sender.sendMessage("§a[Suite] ✓ No hay actualizaciones disponibles");
                            } else {
                                sender.sendMessage("§a[Suite] ✓ Actualización completa: " + result.packages().size() + " paquetes actualizados");
                                for (String u : result.packages()) {
                                    sender.sendMessage("  §a- §f" + u);
                                }
                            }
                        } else {
                            sender.sendMessage("§c[Suite] Error: " + result.error());
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