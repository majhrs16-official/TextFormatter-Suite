package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WasmModuleLoaderTest {

    private static final byte[] EMPTY_MODULE = {
        0x00, 0x61, 0x73, 0x6d, // \0asm
        0x01, 0x00, 0x00, 0x00  // version 1
    };

    private final WasmModuleLoader loader = new WasmModuleLoader();

    @Test
    void parsesAnEmptyModule() {
        assertNotNull(loader.load(EMPTY_MODULE));
    }

    @Test
    void rejectsEmptyBytes() {
        assertThrows(IllegalArgumentException.class, () -> loader.load(new byte[0]));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(NullPointerException.class, () -> loader.load((Path) null));
    }
}
