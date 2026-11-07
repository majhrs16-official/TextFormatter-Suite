package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Message;

/**
 * Neutral port between the runtime test service and the platform host.
 *
 * <p>The tester package owns the scenarios; the host adapter owns the live
 * components. This keeps the package free of host internals and platform APIs
 * while still exercising the real routing, formatting, translation and
 * delivery pipeline.</p>
 *
 * <p>Trace access is part of this port so a failing scenario can point at the
 * exact stage entry instead of guessing from delivery counters.</p>
 */
public interface TestProbe extends PipelineTraceSource {

    /** @return whether the host has a complete, usable runtime. */
    boolean ready();

    /** @return the live channel catalog. */
    ChannelRegistry channels();

    /** @return the live routing engine. */
    Router router();

    /** @return the number of modules discovered by the kernel. */
    int discoveredModules();

    /** Routes and delivers a message, returning the observed counters. */
    DispatchOutcome dispatch(Message message);

    /** Formats a message through the live formatter (hot-path probe). */
    void format(Message message);

    /** Detects the language of a text through the live translator. */
    String detectLanguage(String text);
}
