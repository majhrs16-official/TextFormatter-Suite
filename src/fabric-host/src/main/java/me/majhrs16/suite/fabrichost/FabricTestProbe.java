package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.spi.DispatchOutcome;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.MessageRenderer;
import me.majhrs16.suite.api.spi.PipelineTraceSource;
import me.majhrs16.suite.api.spi.TestProbe;
import me.majhrs16.suite.api.spi.TranslationService;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Fabric adapter for the neutral tester probe.
 *
 * <p>Binds only neutral ports ({@link ChannelRegistry}, {@link Router},
 * {@link MessageRenderer}, {@link MessageDispatch},
 * {@link PipelineTraceSource}, {@link TranslationService}) so the adapter does
 * not import host internals or textformatter types. The Fabric bootstrap
 * supplies the live ports; this class is deliberately platform-free apart from
 * its package.</p>
 */
public final class FabricTestProbe implements TestProbe {

    private final ChannelRegistry channels;
    private final Router router;
    private final MessageRenderer renderer;
    private final MessageDispatch dispatch;
    private final PipelineTraceSource traces;
    private final TranslationService translation;
    private final IntSupplier discoveredModules;
    private final Language defaultLanguage;

    public FabricTestProbe(ChannelRegistry channels,
                           Router router,
                           MessageRenderer renderer,
                           MessageDispatch dispatch,
                           PipelineTraceSource traces,
                           TranslationService translation,
                           IntSupplier discoveredModules,
                           Language defaultLanguage) {
        this.channels = channels;
        this.router = router;
        this.renderer = renderer;
        this.dispatch = dispatch;
        this.traces = traces;
        this.translation = translation;
        this.discoveredModules = discoveredModules;
        this.defaultLanguage = defaultLanguage;
    }

    @Override
    public boolean ready() {
        return channels != null
            && router != null
            && renderer != null
            && dispatch != null
            && traces != null
            && translation != null
            && discoveredModules != null;
    }

    @Override
    public ChannelRegistry channels() {
        return channels;
    }

    @Override
    public Router router() {
        return router;
    }

    @Override
    public int discoveredModules() {
        return discoveredModules.getAsInt();
    }

    @Override
    public DispatchOutcome dispatch(Message message) {
        return dispatch.dispatchAndReport(message);
    }

    @Override
    public void format(Message message) {
        renderer.render(message, message.text(), message.sender(), defaultLanguage);
    }

    @Override
    public String detectLanguage(String text) {
        return translation.detect(text).code();
    }

    @Override
    public PipelineTrace lastTrace(String messageId) {
        return traces.lastTrace(messageId);
    }

    @Override
    public List<PipelineTrace> recentTraces() {
        return traces.recentTraces();
    }
}
