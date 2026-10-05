package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Language;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Facade the rest of the engine uses to interact with translation providers.
 *
 * <p>Wraps a {@link TranslatorManager} and adds batch helpers plus automatic
 * language detection. All methods are synchronous and expected to be called
 * from an asynchronous scheduler thread.</p>
 *
 * <p>Includes caching for translations and language detection to reduce
 * external API calls and improve performance at scale.</p>
 *
 * <p>Includes in-flight request deduplication to prevent thundering herd
 * when multiple recipients request the same translation concurrently.</p>
 *
 * <p>Uses a dedicated {@link TranslationExecutor} (bounded pool, queue, timeout,
 * cancellation) instead of the unbounded ForkJoinPool.commonPool().</p>
 */
public final class TranslationService implements AutoCloseable {

    private final TranslatorManager manager;
    private final TranslationExecutor executor;
    
    // Translation cache: key = "fromCode|toCode|text" -> translated text
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();
    // Detection cache: key = "detect|text" -> detected language code
    private final Map<String, String> detectionCache = new ConcurrentHashMap<>();
    // In-flight deduplication: key = "fromCode|toCode|text" -> CompletableFuture
    private final Map<String, CompletableFuture<String>> inFlightTranslations = new ConcurrentHashMap<>();
    // In-flight deduplication for detection
    private final Map<String, CompletableFuture<Language>> inFlightDetections = new ConcurrentHashMap<>();
    
    // Cache size limits (prevent unbounded growth)
    private static final int MAX_TRANSLATION_CACHE_SIZE = 10000;
    private static final int MAX_DETECTION_CACHE_SIZE = 5000;
    private static final long TRANSLATION_TIMEOUT_MS = 30_000;

    public TranslationService(TranslatorManager manager) {
        this(manager, TranslationExecutor.createDefault());
    }

    /**
     * Creates a TranslationService with a custom executor.
     *
     * @param manager  the translator manager (nullable, defaults to empty)
     * @param executor the dedicated translation executor (nullable, defaults to {@link TranslationExecutor#createDefault()})
     */
    public TranslationService(TranslatorManager manager, TranslationExecutor executor) {
        this.manager = manager == null ? new TranslatorManager() : manager;
        this.executor = executor == null ? TranslationExecutor.createDefault() : executor;
    }

    /**
     * Translates a single fragment.
     *
     * @return the translated fragment; input text when {@code from == to},
     *         translation disabled, or the provider is unavailable.
     */
    public String translate(String text, Language from, Language to) {
        if (!shouldTranslate(from, to) || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String source = localization(from, text);
        
        // Build cache key using full text to avoid hashCode collisions
        String cacheKey = source + "|" + to.code() + "|" + text;
        
        // Check cache first
        String cached = translationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
// Deduplicate in-flight requests for the same translation
         CompletableFuture<String> future;
         try {
             future = inFlightTranslations.computeIfAbsent(cacheKey, k -> 
                 executor.submit(() -> {
                     try {
                         String translated = manager.active().translate(text, source, to.code());
                         // Store in cache with size limit
                         if (translationCache.size() < MAX_TRANSLATION_CACHE_SIZE) {
                             translationCache.put(cacheKey, translated);
                         }
                         return translated;
                     } catch (TranslationException e) {
                         return text;
                     }
                 }, TRANSLATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
             );
         } catch (RejectedExecutionException e) {
             // Executor saturated: fallback to source text to avoid message loss
             return text;
         }
         
         // Ensure in-flight entry is removed regardless of how future completes
         // (success, exception, timeout cancellation, or rejection)
         future.whenComplete((result, ex) -> inFlightTranslations.remove(cacheKey, future));
         
         try {
             return future.get();
         } catch (Exception e) {
             return text;
         }
    }

    /** Translates a list of fragments, preserving order and skipping empties. */
    public java.util.List<String> translateAll(java.util.List<String> texts, Language from, Language to) {
        java.util.List<String> result = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(translate(text, from, to));
        }
        return result;
    }

    /**
     * Detects the language of a sample text.
     *
     * @return a detected language, or {@code EN} when undetectable.
     */
    public Language detect(String text) {
        if (text == null || text.isEmpty()) {
            return Language.EN;
        }
        
        // Build cache key for detection using full text to avoid hashCode collisions
        String cacheKey = "detect|" + text;
        
        // Check cache first
        String cachedCode = detectionCache.get(cacheKey);
        if (cachedCode != null) {
            return Language.fromCode(cachedCode).orElse(Language.EN);
        }
        
// Deduplicate in-flight requests for the same detection
         CompletableFuture<Language> future;
         try {
             future = inFlightDetections.computeIfAbsent(cacheKey, k -> 
                 executor.submit(() -> {
                     try {
                         Language detected = Language.fromCode(manager.active().detect(text)).orElse(Language.EN);
                         // Store in cache with size limit
                         if (detectionCache.size() < MAX_DETECTION_CACHE_SIZE) {
                             detectionCache.put(cacheKey, detected.code());
                         }
                         return detected;
                     } finally {
                         inFlightDetections.remove(cacheKey);
                     }
                 }, TRANSLATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
             );
         } catch (RejectedExecutionException e) {
             // Executor saturated: fallback to default language
             return Language.EN;
         }
         
         // Ensure in-flight entry is removed regardless of how future completes
         future.whenComplete((result, ex) -> inFlightDetections.remove(cacheKey, future));
         
         try {
             return future.get();
         } catch (Exception e) {
             return Language.EN;
         }
    }

    /** @return whether any provider is currently usable. */
    public boolean isAvailable() {
        return manager.active().isAvailable();
    }

    /** @return the name of the active provider (e.g. {@code "google"}). */
    public String activeName() {
        return manager.active().name();
    }

    /**
     * Clears all caches. Useful for testing or when provider configuration changes.
     */
    public void clearCaches() {
        translationCache.clear();
        detectionCache.clear();
    }

    /**
     * Returns cache statistics for monitoring.
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
            translationCache.size(),
            detectionCache.size(),
            MAX_TRANSLATION_CACHE_SIZE,
            MAX_DETECTION_CACHE_SIZE
        );
    }

    public record CacheStats(
        int translationCacheSize,
        int detectionCacheSize,
        int maxTranslationCacheSize,
        int maxDetectionCacheSize
    ) {}

    /**
     * Closes the translation service, shutting down the dedicated executor.
     * Should be called when the service is no longer needed (e.g., plugin reload).
     */
    @Override
    public void close() {
        executor.close();
    }

    private boolean shouldTranslate(Language from, Language to) {
        if (from == null || to == null || to == Language.AUTO) {
            return false;
        }
        return from != to && manager.active().isAvailable();
    }

    private String localization(Language from, String text) {
        if (from == Language.AUTO) {
            return detect(text).code();
        }
        return from.code();
    }
}