package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.spi.UserLanguageStore;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Ejecutor de acciones atómicas definidas en commands.yml.
 * Proporciona un contexto de ejecución con acceso al estado del plugin.
 */
public final class CommandExecutor {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;
    private final UserLanguageStore languages;
    private final TranslationService translation;
    private final ChannelRegistry channels;
    private final PluginLogger logger;
    private final Function<Actor, Boolean> permissionChecker;
    private final Path configDir;

    public CommandExecutor(SuiteHost host, MessageDispatcher dispatcher,
                           UserLanguageStore languages, TranslationService translation,
                           ChannelRegistry channels, PluginLogger logger,
                           Function<Actor, Boolean> permissionChecker,
                           Path configDir) {
        this.host = host;
        this.dispatcher = dispatcher;
        this.languages = languages;
        this.translation = translation;
        this.channels = channels;
        this.logger = logger;
        this.permissionChecker = permissionChecker;
        this.configDir = configDir;
    }

    /**
     * Contexto de ejecución pasado a los scripts de acción.
     */
    public static final class Context {
        public final SuiteHost host;
        public final UserLanguageStore languages;
        public final TranslationService translation;
        public final ChannelRegistry channels;
        public final PluginLogger logger;
        public final Actor sender;
        public final Actor target;
        public final String[] args;
        public final Map<String, String> bindings;

        public Context(SuiteHost host, UserLanguageStore languages,
                       TranslationService translation, ChannelRegistry channels,
                       PluginLogger logger, Actor sender, Actor target,
                       String[] args, Map<String, String> bindings) {
            this.host = host;
            this.languages = languages;
            this.translation = translation;
            this.channels = channels;
            this.logger = logger;
            this.sender = sender;
            this.target = target;
            this.args = args;
            this.bindings = bindings;
        }

        /** Recarga configuración completa. */
        public void reloadSuite() {
            // Se implementa en el host (spigot-host/fabric-host)
        }

        /** Resetea configs a defaults. Retorna true si OK. */
        public boolean resetConfigs() {
            return false; // Se implementa en el host
        }

        /** Guarda y recarga config. */
        public void saveAndReload() {
            // Se implementa en el host
        }

        /** Obtiene valor de config por path YAML. */
        public String getConfig(String path) {
            return null; // Se implementa con ConfigLoader
        }

        /** Establece valor en config por path YAML. */
        public void setConfig(String path, String value) {
            // Se implementa con ConfigLoader
        }

        /** Envía mensaje al sender. */
        public void sendMessage(String message) {
            // Se implementa en el host
        }
    }

    /**
     * Ejecuta una acción por nombre con el contexto dado.
     * Retorna true si la acción se ejecutó correctamente.
     */
    public boolean execute(String actionName, Context ctx) {
        // TODO: Implementar evaluación de scripts (SpEL o simple template)
        // Por ahora, delega a implementaciones hardcodeadas en hosts
        return false;
    }
}