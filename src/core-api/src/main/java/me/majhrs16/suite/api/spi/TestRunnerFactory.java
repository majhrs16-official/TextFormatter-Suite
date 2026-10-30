package me.majhrs16.suite.api.spi;

/**
 * Creates a {@link TestRunner} for a live platform adapter.
 *
 * <p>The {@code tester} package publishes the factory; the adapter supplies
 * the live {@link TestProbe}, actor directory, language store and logger. The
 * host resolves the factory from the runtime, so it does not import the
 * tester package.</p>
 */
public interface TestRunnerFactory {

    /**
     * @param probe          live runtime probe
     * @param actorDirectory live actor directory
     * @param languageStore  live user language store
     * @param logger         runtime logger
     * @return a test runner bound to the supplied live components
     */
    TestRunner create(TestProbe probe,
                      ActorDirectory actorDirectory,
                      UserLanguageStore languageStore,
                      PluginLogger logger);
}
