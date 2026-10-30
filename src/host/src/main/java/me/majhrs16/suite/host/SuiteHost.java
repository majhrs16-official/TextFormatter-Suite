package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.api.render.MessageComponentRenderer;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.RouteOutcome;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.textformatter.TextFormatter;
import me.majhrs16.suite.textformatter.TextFormatterMessageRenderer;
import me.majhrs16.suite.textformatter.TextFormatters;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.ChannelConfig;
import me.majhrs16.suite.textformatter.ChannelConfig.LoadResult;
import me.majhrs16.suite.chattranslator.BackendConfig;
import me.majhrs16.suite.api.spi.TranslatorManager;

import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Platform-neutral facade that assembles the suite from its file layout and
 * exposes the one-call pipeline used by Spigot/Fabric hosts:
 *
 * <pre>{@code
 *   RoutingResult r = host.deliver(message, recipient);
 *   if (r.delivered())   host.recipient(recipient).showMessage(r.rendered());
 *   else if (r.redirect) console.sendMessage(r.rendered());
 * }</pre>
 *
 * <p>This is the glue the module jars alone must not contain: it wires
 * {@link TranslationService} + {@link Router} + {@link MessageComponentRenderer}
 * and resolves the recipient's language for rendering.</p>
 */
public final class SuiteHost {

    private final HostConfig config;
    private final ChannelRegistry channels;
    private final TranslationService translation;
    private final Router router;
    private final MessageComponentRenderer componentRenderer;
    private final MessageRenderer renderer;
    private final PluginLogger logger;
    private final ChatDelivery chatDelivery;

    /**
     * Creates the pipeline from already-resolved neutral services.
     *
     * <p>This is the constructor the runtime uses after activating packages;
     * no feature type is required to build the pipeline.</p>
     *
     * @param config            resolved host configuration
     * @param channels          channel registry supplied by the formatter package
     * @param translation       translation service supplied by the translator package
     * @param router            iFlow router supplied by the selected engine package
     * @param componentRenderer rich renderer supplied by the formatter package
     * @param renderer          plain renderer supplied by the formatter package
     * @param logger            runtime logger
     * @param chatDelivery      platform output port
     */
    public SuiteHost(HostConfig config,
                     ChannelRegistry channels,
                     TranslationService translation,
                     Router router,
                     MessageComponentRenderer componentRenderer,
                     MessageRenderer renderer,
                     PluginLogger logger,
                     ChatDelivery chatDelivery) {
        this.config = Objects.requireNonNull(config, "config");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.translation = Objects.requireNonNull(translation, "translation");
        this.router = Objects.requireNonNull(router, "router");
        this.componentRenderer = Objects.requireNonNull(componentRenderer, "componentRenderer");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = logger;
        this.chatDelivery = chatDelivery;
    }

    /**
     * Bootstraps from a config directory following the suite layout.
     * Loads ChatTranslator configuration and backends from chattranslator/ directory.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      PluginLogger logger) {
        return bootstrap(configDir, permissions, null, logger);
    }

    /**
     * Bootstraps with an external placeholder resolver (e.g. PlaceholderAPI
     * on Spigot); {@code null} keeps built-in variables only.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                      PluginLogger logger) {
        return bootstrap(configDir, permissions, placeholders, logger, ServiceLoader.load(ChatDelivery.class).findFirst().orElse(null));
    }

    /**
     * Bootstraps with an explicitly provided {@link ChatDelivery} implementation.
     * Loads chattranslator config and backends, creates ChatTranslator facade.
     */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      me.majhrs16.suite.api.spi.PlaceholderResolver placeholders,
                                      PluginLogger logger,
                                      ChatDelivery chatDelivery) {
        ConfigLoader.LoadResult<HostConfig> configResult = ConfigLoader.loadConfig(configDir, logger);
        if (!configResult.isValid() && logger != null) {
            for (String err : configResult.errors()) {
                logger.error("Config error: " + err);
            }
        }
        LoadResult<ChannelRegistry> channelsResult = ChannelConfig.load(configDir);
        if (!channelsResult.isValid() && logger != null) {
            for (String err : channelsResult.errors()) {
                logger.error("Channel config error: " + err);
            }
        }
        HostConfig config = configResult.config();
        ChannelRegistry channels = channelsResult.config();

        // Load translation backends from the translator package configuration.
        TranslatorManager translatorManager = BackendConfig.load(configDir);
        TranslationService translationService = new TranslationService(translatorManager);

        // Select the iFlow runtime (vm by default, firewall via iflow.engine.kind)
        Router router = RouterFactory.create(config, configDir, channels, permissions,
            placeholders, translationService, logger);

        TextFormatter formatter = TextFormatters.create(channels, translationService, placeholders, logger);
        TextFormatterMessageRenderer renderer = new TextFormatterMessageRenderer(formatter);
        return new SuiteHost(config, channels, translationService, router,
                renderer, renderer, logger, chatDelivery);
    }

    public HostConfig config() {
        return config;
    }

    public ChannelRegistry channels() {
        return channels;
    }

    public TranslationService translation() {
        return translation;
    }

    public Router router() {
        return router;
    }

    /** @return neutral rich renderer backed by this host's formatter. */
    public MessageComponentRenderer componentRenderer() {
        return componentRenderer;
    }

    /** @return neutral plain-text renderer backed by this host's formatter. */
    public MessageRenderer renderer() {
        return renderer;
    }

    /**
     * Binds a concrete dispatcher to the neutral {@link MessageDispatch} port.
     *
     * <p>Adapters use this instead of importing {@link MessageDispatcher} and
     * {@link DispatchReport}.</p>
     */
    public MessageDispatch messageDispatch(MessageDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        return new MessageDispatch() {
            @Override
            public void dispatch(Message message) {
                dispatcher.dispatch(message);
            }

            @Override
            public DispatchOutcome dispatchAndReport(Message message) {
                DispatchReport report = dispatcher.dispatch(message);
                return new DispatchOutcome(
                    report.delivered(), report.silenced(), report.redirected());
            }
        };
    }

    /**
     * Resolves the source language for a message once and returns a new Message
     * with the resolved source language cached. This avoids repeated detection
     * when delivering to multiple recipients.
     */
    public Message resolveSourceLanguage(Message message) {
        if (message.resolvedSourceLanguage() != null) {
            return message;
        }
        Language resolved = effectiveSource(message);
        return message.withResolvedSourceLanguage(resolved);
    }

    /**
     * Full pipeline for one recipient: resolve their language, route through
     * iFlow and render with TextFormatter when delivery is allowed.
     */
    public RoutingResult deliver(Message message, Actor recipient) {
        Language lang = effectiveLanguage(recipient);
        RouteOutcome outcome = router.route(message, recipient);
        RouteDecision decision = outcome.decision();
        Message transformedMessage = outcome.message();

        if (decision.target() == PolicyTarget.REDIRECT) {
            return new RoutingResult(decision, renderFor(transformedMessage, recipient, lang), transformedMessage, true);
        }
        if (!decision.delivered()) {
            return new RoutingResult(decision, Component.empty(), transformedMessage, false);
        }
        return new RoutingResult(decision, renderFor(transformedMessage, recipient, lang), transformedMessage, false);
    }

    private Component renderFor(Message message, Actor recipient, Language lang) {
        return componentRenderer.render(message, recipient, effectiveSource(message), lang);
    }

    private Language effectiveSource(Message message) {
        // Use already-resolved source language if available
        if (message.resolvedSourceLanguage() != null) {
            return message.resolvedSourceLanguage();
        }
        if (message.langSource() != null && message.langSource() != Language.AUTO) {
            return message.langSource();
        }
        if (translation.isAvailable()) {
            Language detected = translation.detect(message.text());
            return detected == Language.AUTO ? config.defaultLanguage() : detected;
        }
        return config.defaultLanguage();
    }

    private Language effectiveLanguage(Actor recipient) {
        if (recipient.language() != null) {
            return recipient.language();
        }
        return config.defaultLanguage();
    }

    /**
     * Checks if an actor has a permission.
     * Delegates to the PermissionChecker provided at bootstrap.
     */
    public boolean hasPermission(Actor actor, String permission) {
        return router.hasPermission(actor, permission);
    }
}
