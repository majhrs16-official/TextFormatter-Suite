package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TranslationService;
import me.majhrs16.suite.host.config.ConfigLoader;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.DefaultRouter;
import me.majhrs16.suite.iflow.RouteDecision;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.textformatter.TextFormatter;
import me.majhrs16.suite.textformatter.TextFormatters;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.textformatter.template.TemplateContext;

import net.kyori.adventure.text.Component;

import java.nio.file.Path;

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
 * {@link TranslationService} + {@link Router} + {@link TextFormatter} and
 * resolves the recipient's language for rendering.</p>
 */
public final class SuiteHost {

    private final HostConfig config;
    private final ChannelRegistry channels;
    private final TranslationService translation;
    private final Router router;
    private final TextFormatter formatter;
    private final PluginLogger logger;

    SuiteHost(HostConfig config,
              ChannelRegistry channels,
              TranslationService translation,
              Router router,
              TextFormatter formatter,
              PluginLogger logger) {
        this.config = config;
        this.channels = channels;
        this.translation = translation;
        this.router = router;
        this.formatter = formatter;
        this.logger = logger;
    }

    /** Bootstraps from a config directory following the suite layout. */
    public static SuiteHost bootstrap(Path configDir, PermissionChecker permissions,
                                      TranslationService translation, PluginLogger logger) {
        HostConfig config = ConfigLoader.loadConfig(configDir);
        ChannelRegistry channels = ConfigLoader.loadChannels(configDir);
        Router router = new DefaultRouter(channels, permissions);
        TextFormatter formatter = TextFormatters.create(channels, translation, null, logger);
        return new SuiteHost(config, channels, translation, router, formatter, logger);
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

    public TextFormatter formatter() {
        return formatter;
    }

    /**
     * Full pipeline for one recipient: resolve their language, route through
     * iFlow and render with TextFormatter when delivery is allowed.
     */
    public RoutingResult deliver(Message message, Actor recipient) {
        Language lang = effectiveLanguage(recipient);
        RouteDecision decision = router.route(message, recipient);

        if (decision.target() == PolicyTarget.REDIRECT) {
            return new RoutingResult(decision, renderFor(message, recipient, lang), true);
        }
        if (!decision.delivered()) {
            return new RoutingResult(decision, Component.empty(), false);
        }
        return new RoutingResult(decision, renderFor(message, recipient, lang), false);
    }

    private Component renderFor(Message message, Actor recipient, Language lang) {
        TemplateContext context = TemplateContext.builder(
                message.sender(), effectiveSource(message), lang)
            .content(message.text())
            .translate(message.shouldTranslate())
            .build();
        return formatter.format(message, context);
    }

    private Language effectiveLanguage(Actor recipient) {
        if (recipient.language() != null) {
            return recipient.language();
        }
        return config.defaultLanguage();
    }

    private Language effectiveSource(Message message) {
        if (message.langSource() != null && message.langSource() != Language.AUTO) {
            return message.langSource();
        }
        if (translation.isAvailable()) {
            Language detected = translation.detect(message.text());
            return detected == Language.AUTO ? config.defaultLanguage() : detected;
        }
        return config.defaultLanguage();
    }
}