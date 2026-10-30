package me.majhrs16.suite.tester;

import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.TestProbe;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Creates {@link TestService} instances for a host adapter.
 *
 * <p>The package publishes this factory as a service; the host supplies its
 * {@link TestProbe}, actor directory, language store and logger. The factory
 * itself has no platform dependency.</p>
 */
public final class TestServiceFactory {

    public TestService create(TestProbe probe,
                              ActorDirectory actorDirectory,
                              UserLanguageStore languageStore,
                              PluginLogger logger) {
        return new TestService(probe, actorDirectory, languageStore, logger);
    }
}
