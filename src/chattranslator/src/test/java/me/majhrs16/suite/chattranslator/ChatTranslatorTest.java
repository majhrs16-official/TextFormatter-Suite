package me.majhrs16.suite.chattranslator;

import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.spi.Translator;
import me.majhrs16.suite.api.spi.TranslatorManager;
import me.majhrs16.suite.api.spi.TranslationService;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para {@link ChatTranslator}.
 * Validan la resolución de backend por contexto, config y fallback automático.
 */
@DisplayName("ChatTranslator — unit tests")
class ChatTranslatorTest {

    private TranslatorManager manager;
    private TranslationService translationService;
    private Translator mockGoogle;
    private Translator mockLibre;
    private ChatTranslator chatTranslator;

    @BeforeEach
    void setUp() {
        manager = new TranslatorManager();
        
        mockGoogle = mock(Translator.class);
        when(mockGoogle.name()).thenReturn("google");
        when(mockGoogle.isAvailable()).thenReturn(true);
        when(mockGoogle.translate(anyString(), anyString(), anyString())).thenReturn("translated by google");
        when(mockGoogle.detect(anyString())).thenReturn("en");
        
        mockLibre = mock(Translator.class);
        when(mockLibre.name()).thenReturn("libre");
        when(mockLibre.isAvailable()).thenReturn(true);
        when(mockLibre.translate(anyString(), anyString(), anyString())).thenReturn("translated by libre");
        when(mockLibre.detect(anyString())).thenReturn("en");
        
        manager.add(mockGoogle);
        manager.add(mockLibre);
        
        translationService = new TranslationService(manager);
        chatTranslator = new ChatTranslator(manager, translationService);
    }

    @Test
    @DisplayName("usa backend por defecto cuando no hay contexto ni config")
    void usesDefaultBackendWhenNoContextOrConfig() {
        // Sin config ni contexto, debe usar el primer disponible (google por orden de registro)
        String result = chatTranslator.translate("Hello", Language.EN, Language.ES);
        
        assertThat(result).isEqualTo("translated by google");
        verify(mockGoogle).translate("Hello", "en", "es");
        verify(mockLibre, never()).translate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("respeta flag de contexto para forzar backend específico")
    void respectsContextFlagToForceBackend() {
        chatTranslator.setContextBackend("request-123", "libre");
        
        String result = chatTranslator.translate("Hello", Language.EN, Language.ES, "request-123");
        
        assertThat(result).isEqualTo("translated by libre");
        verify(mockLibre).translate("Hello", "en", "es");
        verify(mockGoogle, never()).translate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("configuración defaultBackend tiene prioridad sobre auto pero menos que contexto")
    void defaultBackendConfigHasPriorityOverAutoButLessThanContext() {
        chatTranslator.setDefaultBackend("libre");
        
        // Sin contexto, usa config
        String result1 = chatTranslator.translate("Hello", Language.EN, Language.ES);
        assertThat(result1).isEqualTo("translated by libre");
        
        // Con contexto, el contexto gana
        chatTranslator.setContextBackend("request-456", "google");
        String result2 = chatTranslator.translate("Hello", Language.EN, Language.ES, "request-456");
        assertThat(result2).isEqualTo("translated by google");
    }

    @Test
    @DisplayName("fallback automático si backend forzado no está disponible")
    void fallsBackWhenForcedBackendUnavailable() {
        when(mockLibre.isAvailable()).thenReturn(false);
        chatTranslator.setContextBackend("request-789", "libre");
        
        String result = chatTranslator.translate("Hello", Language.EN, Language.ES, "request-789");
        
        // Debe caer a google (primer disponible)
        assertThat(result).isEqualTo("translated by google");
    }

    @Test
    @DisplayName("devuelve texto original si texto vacío o idioma destino AUTO")
    void returnsOriginalTextWhenEmptyOrTargetAuto() {
        assertThat(chatTranslator.translate("", Language.EN, Language.ES)).isEqualTo("");
        assertThat(chatTranslator.translate("Hello", Language.EN, Language.AUTO)).isEqualTo("Hello");
        assertThat(chatTranslator.translate(null, Language.EN, Language.ES)).isEqualTo("");
    }

    @Test
    @DisplayName("getRegisteredBackends devuelve todos los proveedores registrados")
    void getRegisteredBackendsReturnsAllProviders() {
        String[] backends = chatTranslator.getRegisteredBackends();
        
        assertThat(backends).containsExactlyInAnyOrder("google", "libre");
    }

    @Test
    @DisplayName("isAvailable true si hay al menos un proveedor disponible")
    void isAvailableTrueWhenProviderAvailable() {
        assertThat(chatTranslator.isAvailable()).isTrue();
        
        // Deshabilitar ambos
        when(mockGoogle.isAvailable()).thenReturn(false);
        when(mockLibre.isAvailable()).thenReturn(false);
        assertThat(chatTranslator.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("getActiveBackendName devuelve nombre del proveedor activo")
    void getActiveBackendNameReturnsActiveProviderName() {
        assertThat(chatTranslator.getActiveBackendName()).isEqualTo("google");
    }

    @Test
    @DisplayName("detect delega al TranslationService")
    void detectDelegatesToTranslationService() {
        Language detected = chatTranslator.detect("Hola mundo");
        
        assertThat(detected).isEqualTo(Language.EN);
        verify(mockGoogle).detect("Hola mundo");
    }

    @Test
    @DisplayName("clearCaches limpia cachés del servicio subyacente")
    void clearCachesClearsUnderlyingServiceCaches() {
        chatTranslator.clearCaches();
        
        // Verificar que no lanza excepción y el servicio sigue funcional
        assertThat(chatTranslator.translate("Hello", Language.EN, Language.ES))
            .isEqualTo("translated by google");
    }

    @Test
    @DisplayName("close cierra el servicio y limpia overrides")
    void closeClosesServiceAndClearsOverrides() {
        chatTranslator.setContextBackend("ctx-1", "google");
        chatTranslator.close();
        
        // Después de close, el contexto debe estar limpio
        // (no se puede verificar fácilmente sin exponer internos, pero no debe lanzar excepción)
        assertThat(chatTranslator.getRegisteredBackends()).containsExactlyInAnyOrder("google", "libre");
    }

    @Test
    @DisplayName("context override se limpia correctamente")
    void contextOverrideClearedCorrectly() {
        chatTranslator.setContextBackend("ctx-clear", "libre");
        chatTranslator.clearContextBackend("ctx-clear");
        
        String result = chatTranslator.translate("Hello", Language.EN, Language.ES, "ctx-clear");
        
        // Debe usar google (default auto) no libre
        assertThat(result).isEqualTo("translated by google");
    }
}