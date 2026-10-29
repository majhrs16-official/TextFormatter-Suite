package me.majhrs16.suite.syncvelocity.plugin;

import me.majhrs16.suite.api.spi.PluginLogger;

import org.slf4j.Logger;

/** Adapts the suite {@link PluginLogger} to the Velocity/slf4j logger. */
final class Slf4jPluginLogger implements PluginLogger {

    private final Logger delegate;

    Slf4jPluginLogger(Logger delegate) {
        this.delegate = delegate;
    }

    @Override
    public void info(String message, Object... args) {
        delegate.info(message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        delegate.warn(message, args);
    }

    @Override
    public void error(String message, Object... args) {
        delegate.error(message, args);
    }

    @Override
    public void error(String message, Throwable throwable) {
        delegate.error(message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        delegate.debug(message, args);
    }
}
