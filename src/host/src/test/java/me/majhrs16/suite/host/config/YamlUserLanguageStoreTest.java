package me.majhrs16.suite.host.config;

import me.majhrs16.suite.api.spi.UserLanguageStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlUserLanguageStoreTest {

    @TempDir
    Path dir;

    @Test
    void emptyFolderYieldsEmptyLookups() {
        YamlUserLanguageStore store = new YamlUserLanguageStore(dir);

        assertTrue(store.languageOf(UUID.randomUUID()).isEmpty());
    }

    @Test
    void savePersistsImmediatelyAndSurvivesRestart() throws Exception {
        UUID uuid = UUID.randomUUID();
        new YamlUserLanguageStore(dir).save(uuid, UserLanguageStore.OFF);

        String written = Files.readString(dir.resolve("storage.yml"));
        assertTrue(written.contains(uuid.toString()));
        assertTrue(written.contains(UserLanguageStore.OFF));

        // "Reinicio": instancia nueva sobre el mismo archivo.
        UserLanguageStore reloaded = new YamlUserLanguageStore(dir);
        assertEquals(UserLanguageStore.OFF, reloaded.languageOf(uuid).orElseThrow());
    }

    @Test
    void externalEditIsPickedUpViaTimestampHotReload() throws Exception {
        UUID uuid = UUID.randomUUID();
        YamlUserLanguageStore store = new YamlUserLanguageStore(dir);
        store.save(uuid, "es");

        // Edición externa ("Guardar → Aplicar") con timestamp distinto.
        Thread.sleep(10); // asegura lastModified distinto en sistemas groseros
        Files.writeString(dir.resolve("storage.yml"),
            uuid + ": \"pt-BR\"\n");

        assertEquals("pt-BR", store.languageOf(uuid).orElseThrow());
    }

    @Test
    void blankValueRemovesTheEntryAndCorruptUuidsAreIgnored() throws Exception {
        UUID uuid = UUID.randomUUID();
        YamlUserLanguageStore store = new YamlUserLanguageStore(dir);
        store.save(uuid, "es");

        store.save(uuid, "");
        assertTrue(store.languageOf(uuid).isEmpty());

        // Archivo con clave no-uuid: se carga el resto sin morir.
        Thread.sleep(10);
        Files.writeString(dir.resolve("storage.yml"), "no-es-uuid: es\n");
        assertTrue(store.languageOf(uuid).isEmpty());
    }
}
