package me.majhrs16.suite.logger;

/**
 * Compile-time debug configuration for the Suite Logger.
 * <p>
 * The {@link #DEBUG} constant is set at compile time by Gradle based on the
 * {@code logger.debug} project property. When {@code false}, the JIT compiler
 * eliminates all code inside {@code if (DebugConfig.DEBUG) { ... }} blocks,
 * resulting in zero overhead.
 * </p>
 * <p>
 * Enable at compile time via Gradle: {@code -Plogger.debug=true}
 * </p>
 * <p>
 * This class is generated/modified by the build process. Do not edit manually.
 * </p>
 */
public final class DebugConfig {

    /**
     * Debug flag. Set at compile time by Gradle (default: false).
     * When false, all debug logging calls are eliminated by JIT dead-code elimination.
     */
    public static final boolean DEBUG = /*[[DEBUG_FLAG]]*/ false;

    private DebugConfig() {}
}