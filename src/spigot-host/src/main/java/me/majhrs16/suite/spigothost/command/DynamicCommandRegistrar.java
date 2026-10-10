package me.majhrs16.suite.spigothost.command;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.config.CommandsConfig;
import me.majhrs16.suite.host.config.CommandsConfigLoader;
import me.majhrs16.suite.observability.health.HealthCheckRegistry;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import me.majhrs16.suite.spigothost.TextFormatterSuitePlugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registra comandos dinámicos basados en commands.yml.
 * Reemplaza el onCommand hardcodeado por un árbol dinámico.
 * <p>
 * Resuelve el runtime dinámicamente via {@link TextFormatterSuitePlugin#getRuntime()}
 * para evitar referencias stale después de reload.
 * </p>
 */
public final class DynamicCommandRegistrar implements CommandExecutor, TabCompleter {

    private final TextFormatterSuitePlugin plugin;
    private final Path configDir;
    private final CommandsConfig commandsConfig;
    private final Map<String, DynamicCommand> registeredCommands = new ConcurrentHashMap<>();

    public DynamicCommandRegistrar(TextFormatterSuitePlugin plugin,
                                   Path configDir) {
        this.plugin = plugin;
        this.configDir = configDir;

        // Cargar configuración de comandos
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        PluginLogger logger = rt != null ? rt.logger() : new PluginLogger() {
            @Override public void info(String m, Object... a) { plugin.getLogger().info(m); }
            @Override public void warn(String m, Object... a) { plugin.getLogger().warning(m); }
            @Override public void error(String m, Object... a) { plugin.getLogger().severe(m); }
            @Override public void error(String m, Throwable t) { plugin.getLogger().severe(m + " :: " + t); }
            @Override public void debug(String m, Object... a) { plugin.getLogger().info("[debug] " + m); }
        };

        this.commandsConfig = CommandsConfigLoader.load(configDir, logger)
            .orElseGet(() -> createDefaultConfig());

        registerAllCommands();
    }

    private SuiteHost resolveHost() {
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        return rt != null ? rt.host() : null;
    }

    private MessageDispatcher resolveDispatcher() {
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        return rt != null ? rt.dispatcher() : null;
    }

    private UserLanguageStore resolveLanguages() {
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        return rt != null ? rt.languages() : null;
    }

    private TranslationService resolveTranslation() {
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        return rt != null ? rt.host().translation() : null;
    }

    private PluginLogger resolveLogger() {
        TextFormatterSuitePlugin.Runtime rt = plugin.getRuntime();
        return rt != null ? rt.logger() : null;
    }

    private HealthCheckRegistry resolveHealthChecks() {
        return plugin.getObservability() != null ? plugin.getObservability().getHealthCheckRegistry() : null;
    }

    private ManagerFacade resolveManagerFacade() {
        return plugin.getManagerFacade();
    }

    private CommandsConfig createDefaultConfig() {
        // Configuración por defecto hardcodeada (igual a la actual)
        // Use LinkedHashMap for actions since Map.of() only supports up to 10 entries
        Map<String, CommandsConfig.ActionDef> actions = new LinkedHashMap<>();
        actions.put("reload", new CommandsConfig.ActionDef("Recarga configuración", "textformattersuite.admin", null, List.of(), false, ""));
        actions.put("status", new CommandsConfig.ActionDef("Muestra estado", "textformattersuite.user", null, List.of(), false, ""));
        actions.put("lang", new CommandsConfig.ActionDef("Configura idioma", "textformattersuite.user", "textformattersuite.admin", List.of(), false, ""));
        actions.put("toggle", new CommandsConfig.ActionDef("Alterna traducción", "textformattersuite.user", "textformattersuite.admin", List.of(), false, ""));
        actions.put("reset", new CommandsConfig.ActionDef("Restaura configs", "textformattersuite.admin", null, List.of(), true, ""));
        actions.put("test", new CommandsConfig.ActionDef("Ejecuta tests", "textformattersuite.admin", null, List.of(), false, ""));
        actions.put("health", new CommandsConfig.ActionDef("Muestra estado de salud del sistema", "textformattersuite.admin", null, List.of(), false, ""));
        actions.put("metrics", new CommandsConfig.ActionDef("Muestra métricas Prometheus", "textformattersuite.admin", null, List.of(), false, ""));
        actions.put("module", new CommandsConfig.ActionDef("Gestiona paquetes instalados (list, install, remove, info)", "textformattersuite.admin", null, List.of(
                new CommandsConfig.ArgDef("action", "enum(list,install,remove,info)", "Acción a realizar", ""),
                new CommandsConfig.ArgDef("module", "string", "ID del paquete", ""),
                new CommandsConfig.ArgDef("version", "string?", "Versión específica (opcional)", ""),
                new CommandsConfig.ArgDef("channel", "string?", "Canal (stable, beta, etc.)", "stable"),
                new CommandsConfig.ArgDef("force", "boolean?", "Forzar instalación", "false")
        ), false, ""));
        actions.put("repo", new CommandsConfig.ActionDef("Gestiona repositorios y paquetes remotos (update, install, remove, upgrade, fix-broken)", "textformattersuite.admin", null, List.of(
                new CommandsConfig.ArgDef("action", "enum(update,install,remove,upgrade,fix-broken)", "Acción a realizar", ""),
                new CommandsConfig.ArgDef("package", "string", "ID del paquete (para install/remove)", ""),
                new CommandsConfig.ArgDef("version", "string?", "Versión específica (opcional)", ""),
                new CommandsConfig.ArgDef("channel", "string?", "Canal (stable, beta, etc.)", "stable"),
                new CommandsConfig.ArgDef("force", "boolean?", "Forzar instalación/actualización", "false"),
                new CommandsConfig.ArgDef("auto-remove", "boolean?", "Eliminar dependencias huérfanas tras remove", "true")
        ), false, ""));
        actions.put("package", new CommandsConfig.ActionDef("Gestiona paquetes locales (install, list, remove, files, verify)", "textformattersuite.admin", null, List.of(
                new CommandsConfig.ArgDef("action", "enum(install,list,remove,files,verify)", "Acción a realizar", ""),
                new CommandsConfig.ArgDef("file", "string", "Ruta al archivo ZIP local (para install)", ""),
                new CommandsConfig.ArgDef("package", "string", "ID del paquete (para remove/files/verify)", ""),
                new CommandsConfig.ArgDef("version", "string?", "Versión específica (opcional)", ""),
                new CommandsConfig.ArgDef("channel", "string?", "Canal (stable, beta, etc.)", "stable"),
                new CommandsConfig.ArgDef("force", "boolean?", "Forzar instalación", "false")
        ), false, ""));
        actions.put("suite", new CommandsConfig.ActionDef("Actualiza toda la suite a las últimas versiones compatibles", "textformattersuite.admin", null, List.of(
                new CommandsConfig.ArgDef("force", "boolean?", "Forzar actualización aunque no sea compatible", "")
        ), false, ""));

        // Use LinkedHashMap for commands since Map.of() only supports up to 10 entries
        Map<String, CommandsConfig.CommandNode> commands = new LinkedHashMap<>();
        
        // Build the subcommands map for "suite" using LinkedHashMap (12 entries > 10 limit)
        Map<String, CommandsConfig.CommandNode> suiteSubcommands = new LinkedHashMap<>();
        suiteSubcommands.put("reload", new CommandsConfig.CommandNode("", "textformattersuite.admin", "reload", Map.of(), "", Map.of()));
        suiteSubcommands.put("status", new CommandsConfig.CommandNode("", "textformattersuite.user", "status", Map.of(), "", Map.of()));
        suiteSubcommands.put("lang", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of(), "value", Map.of(
                "auto", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of("value", "auto"), "", Map.of()),
                "off", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of("value", "off"), "", Map.of())
        )));
        suiteSubcommands.put("toggle", new CommandsConfig.CommandNode("", "textformattersuite.user", "toggle", Map.of(), "target", Map.of()));
        suiteSubcommands.put("reset", new CommandsConfig.CommandNode("", "textformattersuite.admin", "reset", Map.of(), "", Map.of()));
        suiteSubcommands.put("test", new CommandsConfig.CommandNode("", "textformattersuite.admin", "test", Map.of(), "type", Map.of()));
        suiteSubcommands.put("health", new CommandsConfig.CommandNode("", "textformattersuite.admin", "health", Map.of(), "", Map.of()));
        suiteSubcommands.put("metrics", new CommandsConfig.CommandNode("", "textformattersuite.admin", "metrics", Map.of(), "", Map.of()));
        suiteSubcommands.put("module", new CommandsConfig.CommandNode("Gestiona paquetes instalados", "textformattersuite.admin", "module", Map.of(), "action", Map.of(
                "list", new CommandsConfig.CommandNode("", "textformattersuite.user", "module", Map.of("action", "list"), "", Map.of()),
                "install", new CommandsConfig.CommandNode("", "textformattersuite.admin", "module", Map.of("action", "install"), "module", Map.of()),
                "remove", new CommandsConfig.CommandNode("", "textformattersuite.admin", "module", Map.of("action", "remove"), "module", Map.of()),
                "info", new CommandsConfig.CommandNode("", "textformattersuite.user", "module", Map.of("action", "info"), "module", Map.of())
        )));
        suiteSubcommands.put("repo", new CommandsConfig.CommandNode("Gestiona repositorios y paquetes remotos", "textformattersuite.admin", "repo", Map.of(), "action", Map.of(
                "update", new CommandsConfig.CommandNode("", "textformattersuite.admin", "repo", Map.of("action", "update"), "", Map.of()),
                "install", new CommandsConfig.CommandNode("", "textformattersuite.admin", "repo", Map.of("action", "install"), "package", Map.of()),
                "remove", new CommandsConfig.CommandNode("", "textformattersuite.admin", "repo", Map.of("action", "remove"), "package", Map.of()),
                "upgrade", new CommandsConfig.CommandNode("", "textformattersuite.admin", "repo", Map.of("action", "upgrade"), "", Map.of()),
                "fix-broken", new CommandsConfig.CommandNode("", "textformattersuite.admin", "repo", Map.of("action", "fix-broken"), "", Map.of())
        )));
        suiteSubcommands.put("package", new CommandsConfig.CommandNode("Gestiona paquetes locales", "textformattersuite.admin", "package", Map.of(), "action", Map.of(
                "install", new CommandsConfig.CommandNode("", "textformattersuite.admin", "package", Map.of("action", "install"), "file", Map.of()),
                "list", new CommandsConfig.CommandNode("", "textformattersuite.user", "package", Map.of("action", "list"), "", Map.of()),
                "remove", new CommandsConfig.CommandNode("", "textformattersuite.admin", "package", Map.of("action", "remove"), "package", Map.of()),
                "files", new CommandsConfig.CommandNode("", "textformattersuite.user", "package", Map.of("action", "files"), "package", Map.of()),
                "verify", new CommandsConfig.CommandNode("", "textformattersuite.user", "package", Map.of("action", "verify"), "package", Map.of())
        )));
        suiteSubcommands.put("suite", new CommandsConfig.CommandNode("Actualiza toda la suite", "textformattersuite.admin", "suite", Map.of(), "force", Map.of()));

        commands.put("suite", new CommandsConfig.CommandNode(
                "Comando principal TextFormatter Suite",
                "textformattersuite.user",
                "",
                Map.of(),
                "",
                suiteSubcommands
            )
        );

        return new CommandsConfig("suite", List.of("suite", "txf"), actions, commands);
    }

    private void registerAllCommands() {
        // Obtener CommandMap via reflexión
        CommandMap commandMap = getCommandMap();
        if (commandMap == null) {
            PluginLogger log = logger();
            if (log != null) log.error("No se pudo obtener CommandMap; comandos dinámicos no registrados");
            return;
        }

        // Registrar cada comando raíz
        for (Map.Entry<String, CommandsConfig.CommandNode> entry : commandsConfig.commands().entrySet()) {
            String name = entry.getKey();
            CommandsConfig.CommandNode node = entry.getValue();

            DynamicCommand cmd = new DynamicCommand(this, name, node);
            for (String alias : commandsConfig.aliases()) {
                if (alias.equals(name)) continue;
                commandMap.register(alias, name, cmd);
            }
            commandMap.register(plugin.getName(), name, cmd);
            registeredCommands.put(name, cmd);
            PluginLogger log = logger();
            if (log != null) log.info("Comando dinámico registrado: /" + name);
        }
    }

    private CommandMap getCommandMap() {
        try {
            Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            return (CommandMap) field.get(Bukkit.getServer());
        } catch (Exception e) {
            PluginLogger log = logger();
            if (log != null) log.error("Error obteniendo CommandMap: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        DynamicCommand cmd = registeredCommands.get(command.getName());
        if (cmd == null) return false;

        // Verificar permiso base
        if (cmd.node().permission() != null && !sender.hasPermission(cmd.node().permission())) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }

        return cmd.execute(sender, command.getName(), args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        DynamicCommand cmd = registeredCommands.get(command.getName());
        if (cmd == null) return Collections.emptyList();
        return cmd.tabComplete(sender, alias, args);
    }

    // Getters para uso interno de DynamicCommand (resuelven runtime dinámicamente)
    SuiteHost host() { return resolveHost(); }
    MessageDispatcher dispatcher() { return resolveDispatcher(); }
    UserLanguageStore languages() { return resolveLanguages(); }
    TranslationService translation() { return resolveTranslation(); }
    PluginLogger logger() { return resolveLogger(); }
    Path configDir() { return plugin.getDataFolder().toPath(); }
    CommandsConfig commandsConfig() { return commandsConfig; }
    Map<String, CommandsConfig.ActionDef> actions() { return commandsConfig.actions(); }
    HealthCheckRegistry healthChecks() { return resolveHealthChecks(); }
    TextFormatterSuitePlugin plugin() { return plugin; }
    ManagerFacade managerFacade() { return resolveManagerFacade(); }
}