package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Language;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TranslationServiceCacheTest {

    private TranslatorManager mockManager;
    private TrackingTranslator mockTranslator;
    private TranslationService service;

    @BeforeEach
    void setup() {
        mockTranslator = new TrackingTranslator();
        
        mockManager = new TranslatorManager();
        mockManager.add(mockTranslator);

        service = new TranslationService(mockManager);
    }

    @Test
    void translationCacheHitAvoidsProviderCall() {
        String result1 = service.translate("hello world", Language.EN, Language.ES);
        assertEquals("[es]hello world", result1);
        assertEquals(1, mockTranslator.translateCallCount.get());

        String result2 = service.translate("hello world", Language.EN, Language.ES);
        assertEquals("[es]hello world", result2);
        assertEquals(1, mockTranslator.translateCallCount.get(), "Provider should not be called again for cached translation");
    }

    @Test
    void translationCacheMissCallsProvider() {
        service.translate("hello", Language.EN, Language.ES);
        assertEquals(1, mockTranslator.translateCallCount.get());

        service.translate("world", Language.EN, Language.ES);
        assertEquals(2, mockTranslator.translateCallCount.get(), "Different text should call provider");
    }

    @Test
    void translationCacheRespectsLanguagePair() {
        service.translate("hello", Language.EN, Language.ES);
        assertEquals(1, mockTranslator.translateCallCount.get());

        service.translate("hello", Language.EN, Language.FR);
        assertEquals(2, mockTranslator.translateCallCount.get(), "Different target language should call provider");
    }

    @Test
    void translationCacheRespectsSourceLanguage() {
        service.translate("hello", Language.EN, Language.ES);
        assertEquals(1, mockTranslator.translateCallCount.get());

        service.translate("hello", Language.FR, Language.ES);
        assertEquals(2, mockTranslator.translateCallCount.get(), "Different source language should call provider");
    }

    @Test
    void detectionCacheHitAvoidsProviderCall() {
        Language result1 = service.detect("hello world");
        assertEquals(Language.EN, result1);
        assertEquals(1, mockTranslator.detectCallCount.get());

        Language result2 = service.detect("hello world");
        assertEquals(Language.EN, result2);
        assertEquals(1, mockTranslator.detectCallCount.get(), "Provider should not be called again for cached detection");
    }

    @Test
    void detectionCacheMissCallsProvider() {
        service.detect("hello");
        assertEquals(1, mockTranslator.detectCallCount.get());

        service.detect("world");
        assertEquals(2, mockTranslator.detectCallCount.get(), "Different text should call provider");
    }

    @Test
    void translationCacheSizeLimit() {
        for (int i = 0; i < 10005; i++) {
            service.translate("text" + i, Language.EN, Language.ES);
        }

        TranslationService.CacheStats stats = service.getCacheStats();
        assertTrue(stats.translationCacheSize() <= stats.maxTranslationCacheSize(),
            "Cache size should not exceed max limit");
    }

    @Test
    void detectionCacheSizeLimit() {
        for (int i = 0; i < 5005; i++) {
            service.detect("text" + i);
        }

        TranslationService.CacheStats stats = service.getCacheStats();
        assertTrue(stats.detectionCacheSize() <= stats.maxDetectionCacheSize(),
            "Detection cache size should not exceed max limit");
    }

    @Test
    void cacheStatsReflectActualSizes() {
        service.translate("test1", Language.EN, Language.ES);
        service.translate("test2", Language.EN, Language.ES);
        service.detect("detect1");
        service.detect("detect2");

        TranslationService.CacheStats stats = service.getCacheStats();
        assertEquals(2, stats.translationCacheSize());
        assertEquals(2, stats.detectionCacheSize());
        assertEquals(10000, stats.maxTranslationCacheSize());
        assertEquals(5000, stats.maxDetectionCacheSize());
    }

    @Test
    void clearCachesEmptiesBothCaches() {
        service.translate("hello", Language.EN, Language.ES);
        service.detect("hello");
        
        assertEquals(1, service.getCacheStats().translationCacheSize());
        assertEquals(1, service.getCacheStats().detectionCacheSize());

        service.clearCaches();

        assertEquals(0, service.getCacheStats().translationCacheSize());
        assertEquals(0, service.getCacheStats().detectionCacheSize());
        
        service.translate("hello", Language.EN, Language.ES);
        assertEquals(2, mockTranslator.translateCallCount.get());
    }

    @Test
    void translateAllUsesCache() {
        var texts = java.util.List.of("hello", "world", "hello", "test");
        
        var results = service.translateAll(texts, Language.EN, Language.ES);
        
        assertEquals(4, results.size());
        assertEquals(3, mockTranslator.translateCallCount.get(), 
            "translateAll should use cache for duplicate entries");
    }

    @Test
    void cacheKeyIncludesHash() {
        service.translate("hello", Language.EN, Language.ES);
        service.translate("world", Language.EN, Language.ES);
        
        assertEquals(2, service.getCacheStats().translationCacheSize());
    }

    @Test
    void nullOrEmptyTextDoesNotCallProvider() {
        service.translate(null, Language.EN, Language.ES);
        service.translate("", Language.EN, Language.ES);
        
        assertEquals(0, mockTranslator.translateCallCount.get());
    }

    @Test
    void sameSourceAndTargetDoesNotCallProvider() {
        service.translate("hello", Language.EN, Language.EN);
        
        assertEquals(0, mockTranslator.translateCallCount.get());
    }

    @Test
    void autoSourceLanguageUsesDetectionCache() {
        service.translate("hello", Language.AUTO, Language.ES);
        assertEquals(1, mockTranslator.detectCallCount.get());
        
        service.translate("hello", Language.AUTO, Language.ES);
        assertEquals(1, mockTranslator.detectCallCount.get(), "AUTO source should use detection cache");
    }

    @Test
    void unavailableProviderReturnsOriginalText() {
        mockManager = new TranslatorManager();
        mockManager.add(new Translator() {
            @Override public String name() { return "unavailable"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return false; }
        });
        
        service = new TranslationService(mockManager);
        
        String result = service.translate("hello", Language.EN, Language.ES);
        assertEquals("hello", result);
    }

    // Tracking implementation
    static class TrackingTranslator implements Translator {
        final AtomicInteger translateCallCount = new AtomicInteger(0);
        final AtomicInteger detectCallCount = new AtomicInteger(0);

        @Override public String name() { return "mock"; }
        @Override public String translate(String text, String from, String to) {
            translateCallCount.incrementAndGet();
            return "[" + to + "]" + text;
        }
        @Override public String detect(String text) {
            detectCallCount.incrementAndGet();
            return "en";
        }
        @Override public boolean isAvailable() { return true; }
    }
}