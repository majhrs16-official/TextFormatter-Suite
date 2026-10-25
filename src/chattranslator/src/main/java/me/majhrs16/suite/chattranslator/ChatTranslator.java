package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.TranslationException;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade unificada de traducción para toda la suite.
 * <p>
 * Centraliza la resolución de backends de traducción y expone una API simple
 * para que el resto de la suite traduzca texto sin conocer los detalles
 * de implementación de cada proveedor.
 * </p>
 * <p>
 * La selección del backend sigue esta prioridad:
 * <ol>
 *   <li><strong>Flag de contexto (runtime):</strong> Si la petición incluye un
 *       contexto con clave indicando un nombre de proveedor (ej. {@code "google"}, {@code "libre"}),
 *       se usa ese backend (si {@code contextOverrideEnabled} es true).</li>
 *   <li><strong>Configuración estática (chattranslator/config.yml):</strong> En ausencia de flag,
 *       se usa el backend configurado como predeterminado en {@code default-backend}.</li>
 *   <li><strong>Fallback automático:</strong> Si el backend indicado no está
 *       disponible, se recurre al primer proveedor disponible en el
 *       {@link TranslatorManager}.</li>
 * </ol>
 * </p>
 * <p>
 * Los backends (gtranslate, ltranslate, etc.) se registran automáticamente
 * vía {@link java.util.ServiceLoader} en el {@link TranslatorManager}
 * durante la inicialización del host. Este módulo solo expone la fachada.
 * </p>
 *
 * @see TranslationService
 * @see TranslatorManager
 * @see Translator
 * @see ChatTranslatorConfig
 */
public final class ChatTranslator implements AutoCloseable {

    private final TranslatorManager manager;
    private final TranslationService translationService;
    private final ReadWriteLock configLock = new ReentrantReadWriteLock();
    private volatile String defaultBackend;
    private volatile boolean contextOverrideEnabled;

    // Cache de contextos activos por hilo/petición (clave -> nombre backend)
    private final Map<String, String> contextBackendOverride = new ConcurrentHashMap<>();

    /**
     * Crea una instancia de ChatTranslator con el manager, servicio de traducción y configuración.
     *
     * @param manager           gestor de proveedores (inyectado desde el host)
     * @param translationService servicio de traducción (inyectado desde el host)
     * @param config            configuración del módulo (desde chattranslator/config.yml)
     */
    public ChatTranslator(TranslatorManager manager, TranslationService translationService, ChatTranslatorConfig.Config config) {
        this.manager = manager;
        this.translationService = translationService;
        this.defaultBackend = config.defaultBackend();
        this.contextOverrideEnabled = config.contextOverrideEnabled();
    }

    /**
     * Constructor de compatibilidad sin config explícita (usa defaults).
     */
    public ChatTranslator(TranslatorManager manager, TranslationService translationService) {
        this(manager, translationService, ChatTranslatorConfig.Config.defaults());
    }

    /**
     * Establece el backend predeterminado desde configuración estática (chattranslator/config.yml).
     * Se invoca durante el arranque del host al cargar la configuración.
     *
     * @param backendName nombre del backend (ej. {@code "google"}, {@code "libre"}),
     *                    o {@code null} para usar selección automática
     */
    public void setDefaultBackend(String backendName) {
        configLock.writeLock().lock();
        try {
            this.defaultBackend = (backendName != null && backendName.isBlank()) ? null : backendName;
        } finally {
            configLock.writeLock().unlock();
        }
    }

    /**
     * Obtiene el backend predeterminado configurado.
     *
     * @return nombre del backend predeterminado, o {@code null} si no hay configuración
     */
    public String getDefaultBackend() {
        configLock.readLock().lock();
        try {
            return defaultBackend;
        } finally {
            configLock.readLock().unlock();
        }
    }

    /**
     * Habilita o deshabilita los overrides de contexto en runtime.
     *
     * @param enabled {@code true} para permitir {@code setContextBackend}
     */
    public void setContextOverrideEnabled(boolean enabled) {
        this.contextOverrideEnabled = enabled;
    }

    /**
     * Verifica si los overrides de contexto están habilitados.
     *
     * @return {@code true} si se permiten overrides por contexto
     */
    public boolean isContextOverrideEnabled() {
        return contextOverrideEnabled;
    }

    /**
     * Establece un override de backend para un contexto de ejecución específico.
     * <p>
     * Este método permite forzar un backend concreto para una petición concreta
     * (ej. comando de usuario, evento específico) sin afectar la configuración global.
     * Solo funciona si {@code contextOverrideEnabled} es {@code true}.
     * </p>
     *
     * @param contextKey clave única del contexto (ej. ID de petición, UUID de comando)
     * @param backendName nombre del backend a forzar, o {@code null} para limpiar el override
     */
    public void setContextBackend(String contextKey, String backendName) {
        if (!contextOverrideEnabled) {
            return;
        }
        if (contextKey == null || contextKey.isBlank()) {
            return;
        }
        if (backendName == null || backendName.isBlank()) {
            contextBackendOverride.remove(contextKey);
        } else {
            contextBackendOverride.put(contextKey, backendName);
        }
    }

    /**
     * Limpia el override de backend para un contexto.
     *
     * @param contextKey clave del contexto
     */
    public void clearContextBackend(String contextKey) {
        contextBackendOverride.remove(contextKey);
    }

    /**
     * Traduce un fragmento de texto usando la resolución de backend estándar.
     *
     * @param text       texto a traducir (no null, puede ser vacío)
     * @param from       idioma origen ({@code Language.AUTO} para detección automática)
     * @param to         idioma destino
     * @param contextKey clave opcional de contexto para forzar backend (puede ser {@code null})
     * @return texto traducido, o el original si no se pudo traducir
     */
    public String translate(String text, Language from, Language to, String contextKey) {
        if (text == null || text.isEmpty() || to == Language.AUTO) {
            return text == null ? "" : text;
        }

        // Resolver backend según prioridad: contexto > config > automático
        String backendToUse = resolveBackend(contextKey);
        
        if (backendToUse != null) {
            Translator specificProvider = findProviderByName(backendToUse);
            if (specificProvider != null && specificProvider.isAvailable()) {
                try {
                    String sourceLang = (from == Language.AUTO) 
                        ? translationService.detect(text).code() 
                        : from.code();
                    return specificProvider.translate(text, sourceLang, to.code());
                } catch (TranslationException e) {
                    // Caer a fallback si el backend forzado falla
                }
            }
        }

        // Fallback: usar TranslationService normal (selección automática)
        return translationService.translate(text, from, to);
    }

    /**
     * Traduce un fragmento sin contexto específico (usa config/auto).
     *
     * @param text texto a traducir
     * @param from idioma origen
     * @param to   idioma destino
     * @return texto traducido
     */
    public String translate(String text, Language from, Language to) {
        return translate(text, from, to, null);
    }

    /**
     * Detecta el idioma de un texto.
     *
     * @param text       texto a analizar
     * @param contextKey clave opcional de contexto
     * @return idioma detectado
     */
    public Language detect(String text, String contextKey) {
        return translationService.detect(text);
    }

    /**
     * Detecta el idioma sin contexto específico.
     *
     * @param text texto a analizar
     * @return idioma detectado
     */
    public Language detect(String text) {
        return detect(text, null);
    }

    /**
     * Verifica si hay algún backend de traducción disponible.
     *
     * @return {@code true} si al menos un proveedor está disponible
     */
    public boolean isAvailable() {
        return translationService.isAvailable();
    }

    /**
     * Obtiene el nombre del backend activo actualmente (según resolución automática).
     *
     * @return nombre del proveedor activo (ej. {@code "google"}), o {@code "none"}
     */
    public String getActiveBackendName() {
        return translationService.activeName();
    }

    /**
     * Obtiene los nombres de todos los backends registrados.
     *
     * @return array con nombres de proveedores registrados
     */
    public String[] getRegisteredBackends() {
        return manager.providers().stream()
            .map(Translator::name)
            .toArray(String[]::new);
    }

    /**
     * Obtiene estadísticas de caché del servicio de traducción subyacente.
     *
     * @return estadísticas de caché
     */
    public TranslationService.CacheStats getCacheStats() {
        return translationService.getCacheStats();
    }

    /**
     * Limpia todas las cachés de traducción y detección.
     */
    public void clearCaches() {
        translationService.clearCaches();
    }

    /**
     * Resuelve el backend a usar según prioridad: contexto > config > null (auto).
     */
    private String resolveBackend(String contextKey) {
        if (contextKey != null && contextOverrideEnabled) {
            String ctxBackend = contextBackendOverride.get(contextKey);
            if (ctxBackend != null) {
                return ctxBackend;
            }
        }
        return defaultBackend;
    }

    /**
     * Busca un proveedor por nombre en el manager.
     */
    private Translator findProviderByName(String name) {
        return manager.providers().stream()
            .filter(p -> p.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    @Override
    public void close() {
        translationService.close();
        contextBackendOverride.clear();
    }
}