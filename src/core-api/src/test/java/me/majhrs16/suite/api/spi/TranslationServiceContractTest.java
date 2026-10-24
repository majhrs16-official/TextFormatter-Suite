package me.majhrs16.suite.api.spi;

import me.majhrs16.suite.api.message.Language;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests de contrato para {@link TranslationService}.
 * Validan que la API cumple su contrato sin depender de implementaciones concretas.
 */
@DisplayName("TranslationService — contrato SPI")
class TranslationServiceContractTest {

    @Test
    @DisplayName("translate(): Devuelve texto original cuando from == to")
    void translate_returnsOriginalWhenFromEqualsTo() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return "translated-" + text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        String result = service.translate("hola", Language.ES, Language.ES);

        Assertions.assertThat(result).isEqualTo("hola");
    }

    @Test
    @DisplayName("translate(): Devuelve vacío cuando texto es null")
    void translate_returnsEmptyWhenTextIsNull() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return "translated"; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThat(service.translate(null, Language.ES, Language.EN)).isEqualTo("");
    }

    @Test
    @DisplayName("translate(): Devuelve vacío cuando texto está vacío")
    void translate_returnsEmptyWhenTextIsEmpty() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return "translated"; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThat(service.translate("", Language.ES, Language.EN)).isEqualTo("");
    }

    @Test
    @DisplayName("translate(): Devuelve texto original cuando provider no disponible")
    void translate_returnsOriginalWhenProviderUnavailable() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return "translated"; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return false; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThat(service.translate("hola", Language.ES, Language.EN)).isEqualTo("hola");
    }

    @Test
    @DisplayName("translateAll(): preserva orden y omite vacíos")
    void translateAll_preservesOrder() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text + "-t"; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        var result = service.translateAll(java.util.List.of("a", "b", "", "c"), Language.ES, Language.EN);

        Assertions.assertThat(result).containsExactly("a-t", "b-t", "", "c-t");
    }

    @Test
    @DisplayName("detect(): Devuelve EN cuando texto es null")
    void detect_returnsENWhenTextIsNull() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "fr"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThat(service.detect(null)).isEqualTo(Language.EN);
    }

    @Test
    @DisplayName("detect(): Devuelve EN cuando texto está vacío")
    void detect_returnsENWhenTextIsEmpty() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "fr"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThat(service.detect("")).isEqualTo(Language.EN);
    }

    @Test
    @DisplayName("cache: clearCaches vacía ambos caches")
    void cache_clearCachesEmptiesBoth() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text + "-t"; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        service.translate("hola", Language.ES, Language.EN);
        service.detect("hello");

        Assertions.assertThat(service.getCacheStats().translationCacheSize()).isGreaterThan(0);
        Assertions.assertThat(service.getCacheStats().detectionCacheSize()).isGreaterThan(0);

        service.clearCaches();

        Assertions.assertThat(service.getCacheStats().translationCacheSize()).isZero();
        Assertions.assertThat(service.getCacheStats().detectionCacheSize()).isZero();
    }

    @Test
    @DisplayName("cache: getCacheStats reporta límites configurados")
    void cache_getCacheStatsReportsLimits() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        var stats = service.getCacheStats();

        Assertions.assertThat(stats.maxTranslationCacheSize()).isEqualTo(10000);
        Assertions.assertThat(stats.maxDetectionCacheSize()).isEqualTo(5000);
    }

    @Test
    @DisplayName("close(): no lanza excepción")
    void close_doesNotThrow() {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) { return text; }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        Assertions.assertThatCode(service::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ServiceLoader: encuentra TranslatorProvider si hay implementaciones en classpath")
    void serviceLoader_findsProviders() {
        var providers = ServiceLoader.load(TranslatorProvider.class).stream().toList();

        Assertions.assertThat(providers).isNotNull();
    }

    @Test
    @DisplayName("deduplication: solicitudes concurrentes idénticas usan el mismo future")
    void deduplication_concurrentIdenticalRequestsUseSameFuture() throws Exception {
        TranslatorManager manager = new TranslatorManager();
        manager.add(new Translator() {
            int calls = 0;
            @Override public String name() { return "test"; }
            @Override public String translate(String text, String from, String to) {
                calls++;
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return text + "-" + calls;
            }
            @Override public String detect(String text) { return "en"; }
            @Override public boolean isAvailable() { return true; }
        });

        TranslationService service = new TranslationService(manager);

        var f1 = CompletableFuture.supplyAsync(() -> service.translate("hola", Language.ES, Language.EN));
        var f2 = CompletableFuture.supplyAsync(() -> service.translate("hola", Language.ES, Language.EN));
        var f3 = CompletableFuture.supplyAsync(() -> service.translate("hola", Language.ES, Language.EN));

        CompletableFuture.allOf(f1, f2, f3).get(5, TimeUnit.SECONDS);

        String r1 = f1.join();
        String r2 = f2.join();
        String r3 = f3.join();

        Assertions.assertThat(r1).isEqualTo(r2).isEqualTo(r3);
        Assertions.assertThat(r1).endsWith("-1");
    }
}