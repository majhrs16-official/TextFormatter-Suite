package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Language;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 */
public final class TranslationService {

    private final TranslatorManager manager;
    
    // Translation cache: key = "fromCode|toCode|textHash" -> translated text
    private final Map<String, String> translationCache = new ConcurrentHashMap<>();
    // Detection cache: key = textHash -> detected language code
    private final Map<String, String> detectionCache = new ConcurrentHashMap<>();
    
    // Cache size limits (prevent unbounded growth)
    private static final int MAX_TRANSLATION_CACHE_SIZE = 10000;
    private static final int MAX_DETECTION_CACHE_SIZE = 5000;

    public TranslationService(TranslatorManager manager) {
        this.manager = manager == null ? new TranslatorManager() : manager;
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
        
        // Build cache key
        String cacheKey = source + "|" + to.code() + "|" + text.hashCode();
        
        // Check cache first
        String cached = translationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
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
        
        // Build cache key for detection
        String cacheKey = "detect|" + text.hashCode();
        
        // Check cache first
        String cachedCode = detectionCache.get(cacheKey);
        if (cachedCode != null) {
            return Language.fromCode(cachedCode).orElse(Language.EN);
        }
        
        Language detected = Language.fromCode(manager.active().detect(text)).orElse(Language.EN);
        
        // Store in cache with size limit
        if (detectionCache.size() < MAX_DETECTION_CACHE_SIZE) {
            detectionCache.put(cacheKey, detected.code());
        }
        return detected;
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