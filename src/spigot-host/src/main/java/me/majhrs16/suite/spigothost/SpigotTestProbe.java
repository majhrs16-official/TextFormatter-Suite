package me.majhrs16.suite.spigothost;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.host.DispatchReport;
import me.majhrs16.suite.host.MessageDispatcher;
import me.majhrs16.suite.host.SuiteHost;
import me.majhrs16.suite.kernel.ModuleLoader;
import me.majhrs16.suite.tester.TestProbe;
import me.majhrs16.suite.textformatter.template.TemplateContext;

/**
 * Spigot adapter for the neutral tester probe.
 *
 * <p>Binds the live {@link SuiteHost} and {@link MessageDispatcher} without
 * leaking them into the {@code tester} package.</p>
 */
public final class SpigotTestProbe implements TestProbe {

    private final SuiteHost host;
    private final MessageDispatcher dispatcher;

    public SpigotTestProbe(SuiteHost host, MessageDispatcher dispatcher) {
        this.host = host;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean ready() {
        return host != null
            && host.config() != null
            && host.channels() != null
            && host.translation() != null
            && host.formatter() != null
            && host.router() != null;
    }

    @Override
    public ChannelRegistry channels() {
        return host.channels();
    }

    @Override
    public Router router() {
        return host.router();
    }

    @Override
    public int discoveredModules() {
        return ModuleLoader.discover(host.getClass().getClassLoader()).size();
    }

    @Override
    public DispatchOutcome dispatch(Message message) {
        DispatchReport report = dispatcher.dispatch(message);
        return new DispatchOutcome(report.delivered(), report.silenced(), report.redirected());
    }

    @Override
    public void format(Message message) {
        host.formatter().format(message, TemplateContext.builder(
                message.sender(), message.langSource(), message.langTarget())
            .content(message.text())
            .translate(message.shouldTranslate())
            .build());
    }

    @Override
    public String detectLanguage(String text) {
        return host.translation().detect(text).code();
    }
}
