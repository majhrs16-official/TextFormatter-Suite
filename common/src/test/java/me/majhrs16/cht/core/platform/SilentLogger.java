package me.majhrs16.cht.core.platform;

/**
 * Test double logger that swallows everything.
 */
public final class SilentLogger implements PluginLogger {

    @Override public void info(String message, Object... args) { }
    @Override public void warn(String message, Object... args) { }
    @Override public void error(String message, Object... args) { }
    @Override public void error(String message, Throwable throwable) { }
    @Override public void debug(String message, Object... args) { }
}