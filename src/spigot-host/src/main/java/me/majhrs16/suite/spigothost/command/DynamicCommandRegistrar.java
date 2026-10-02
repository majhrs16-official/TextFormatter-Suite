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

    private me.majhrs16.suite.manager.ModuleLifecycle resolveModuleLifecycle() {
        return plugin.getModuleLifecycle();
    }

    private CommandsConfig createDefaultConfig() {
        // Configuración por defecto hardcodeada (igual a la actual)
        Map<String, CommandsConfig.ActionDef> actions = Map.of(
            "reload", new CommandsConfig.ActionDef("Recarga configuración", "textformattersuite.admin", null, List.of(), false, ""),
            "status", new CommandsConfig.ActionDef("Muestra estado", "textformattersuite.user", null, List.of(), false, ""),
            "lang", new CommandsConfig.ActionDef("Configura idioma", "textformattersuite.user", "textformattersuite.admin", List.of(), false, ""),
            "toggle", new CommandsConfig.ActionDef("Alterna traducción", "textformattersuite.user", "textformattersuite.admin", List.of(), false, ""),
            "reset", new CommandsConfig.ActionDef("Restaura configs", "textformattersuite.admin", null, List.of(), true, ""),
            "test", new CommandsConfig.ActionDef("Ejecuta tests", "textformattersuite.admin", null, List.of(), false, ""),
            "health", new CommandsConfig.ActionDef("Muestra estado de salud del sistema", "textformattersuite.admin", null, List.of(), false, ""),
            "metrics", new CommandsConfig.ActionDef("Muestra métricas Prometheus", "textformattersuite.admin", null, List.of(), false, ""),
            "module", new CommandsConfig.ActionDef("Gestiona módulos (install, update, list, remove)", "textformattersuite.admin", null, List.of(
new CommandsConfig.ArgDef("action", "enum(install,update,list,remove,info)", "Acción a realizar", ""),
                        new CommandsConfig.ArgDef("module", "string", "ID del módulo (ej. suite-textformatter)", ""),
                        new CommandsConfig.ArgDef("version", "string?", "Versión específica (opcional)", "")
            ), false, ""),
            "suite", new CommandsConfig.ActionDef("Actualiza toda la suite a las últimas versiones compatibles", "textformattersuite.admin", null, List.of(
                new CommandsConfig.ArgDef("force", "boolean?", "Forzar actualización aunque no sea compatible", "")
            ), false, "")
        );

        Map<String, CommandsConfig.CommandNode> commands = Map.of(
            "suite", new CommandsConfig.CommandNode(
                "Comando principal TextFormatter Suite",
                "textformattersuite.user",
                "",
                Map.of(),
                "",
                Map.of(
                    "reload", new CommandsConfig.CommandNode("", "textformattersuite.admin", "reload", Map.of(), "", Map.of()),
                    "status", new CommandsConfig.CommandNode("", "textformattersuite.user", "status", Map.of(), "", Map.of()),
                    "lang", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of(), "value", Map.of(
                        "auto", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of("value", "auto"), "", Map.of()),
                        "off", new CommandsConfig.CommandNode("", "textformattersuite.user", "lang", Map.of("value", "off"), "", Map.of())
                    )),
                    "toggle", new CommandsConfig.CommandNode("", "textformattersuite.user", "toggle", Map.of(), "target", Map.of()),
                    "reset", new CommandsConfig.CommandNode("", "textformattersuite.admin", "reset", Map.of(), "", Map.of()),
                    "test", new CommandsConfig.CommandNode("", "textformattersuite.admin", "test", Map.of(), "type", Map.of()),
                    "health", new CommandsConfig.CommandNode("", "textformattersuite.admin", "health", Map.of(), "", Map.of()),
                    "metrics", new CommandsConfig.CommandNode("", "textformattersuite.admin", "metrics", Map.of(), "", Map.of()),
                    "module", new CommandsConfig.CommandNode("Gestiona módulos", "textformattersuite.admin", "module", Map.of(), "action", Map.of(
                        "install", new CommandsConfig.CommandNode("", "textformattersuite.admin", "module", Map.of("action", "install"), "module", Map.of()),
                        "update", new CommandsConfig.CommandNode("", "textformattersuite.admin", "module", Map.of("action", "update"), "module", Map.of()),
                        "list", new CommandsConfig.CommandNode("", "textformattersuite.user", "module", Map.of("action", "list"), "", Map.of()),
                        "remove", new CommandsConfig.CommandNode("", "textformattersuite.admin", "module", Map.of("action", "remove"), "module", Map.of()),
                        "info", new CommandsConfig.CommandNode("", "textformattersuite.user", "module", Map.of("action", "info"), "module", Map.of())
                    )),
                    "suite", new CommandsConfig.CommandNode("Actualiza toda la suite", "textformattersuite.admin", "suite", Map.of(), "force", Map.of())
                )
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
    me.majhrs16.suite.manager.ModuleLifecycle moduleLifecycle() { return resolveModuleLifecycle(); }
}