package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.message.Message;

/**
 * Neutral port between the runtime test service and the platform host.
 *
 * <p>The tester package owns the scenarios; the host adapter owns the live
 * components. This keeps the package free of {@code SuiteHost},
 * {@code MessageDispatcher} and platform APIs while still exercising the real
 * routing, formatting, translation and delivery pipeline.</p>
 */
public interface TestProbe {

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

    /** Counters reported by the host after one dispatch pass. */
    record DispatchOutcome(int delivered, int silenced, int redirected) {

        public static DispatchOutcome none() {
            return new DispatchOutcome(0, 0, 0);
        }
    }
}
