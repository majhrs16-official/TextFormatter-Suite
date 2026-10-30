package me.majhrs16.suite.api.spi;

import java.util.function.Consumer;

/**
 * Neutral entry point for running the production test scenarios.
 *
 * <p>The {@code tester} package implements this contract; platform commands
 * consume it without importing the tester implementation. Scenarios use the
 * live runtime through a {@link TestProbe}.</p>
 */
public interface TestRunner {

    /** Runs the complete automated suite and reports progress line by line. */
    void runFullTestSuite(Consumer<String> reporter);

    /** Runs a concurrent stress scenario. */
    void runStressTest(int numPlayers, int msgsPerPlayer);

    /** Runs a concurrency scenario. */
    void runConcurrencyTest(int threads, int msgsPerThread);
}
