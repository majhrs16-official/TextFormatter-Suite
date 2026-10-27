package me.majhrs16.suite.iflow.wasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.WasmModule;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WasmModuleLoaderTest {

    private static final byte[] EMPTY_MODULE = {
        0x00, 0x61, 0x73, 0x6d, // \0asm
        0x01, 0x00, 0x00, 0x00  // version 1
    };

    /** {@code (module (func (export "decide") (result i32) i32.const 42))} */
    private static final byte[] DECIDE_42 = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x0a, 0x01, 0x06, 'd', 'e', 'c', 'i', 'd', 'e', 0x00, 0x00,
        0x0a, 0x06, 0x01, 0x04, 0x00, 0x41, 0x2a, 0x0b
    );

    /** {@code (module (func (export "other")))} */
    private static final byte[] EXPORTS_OTHER = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x09, 0x01, 0x05, 'o', 't', 'h', 'e', 'r', 0x00, 0x00,
        0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b
    );

    /** {@code (module (import "env" "log" (func)))} */
    private static final byte[] IMPORTS_ENV_LOG = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x02, 0x0b, 0x01, 0x03, 'e', 'n', 'v', 0x03, 'l', 'o', 'g', 0x00, 0x00
    );

    /** {@code (module (func $f) (start $f))} */
    private static final byte[] WITH_START = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x08, 0x01, 0x00,
        0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b
    );

    /** {@code (module (memory 300))} */
    private static final byte[] MEMORY_300_PAGES = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x05, 0x04, 0x01, 0x00, 0xac, 0x02
    );

    /** {@code (module (memory 1))} */
    private static final byte[] MEMORY_1_PAGE = bytes(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01
    );

    private final WasmModuleLoader loader = new WasmModuleLoader();

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static WasmContract contract(Set<String> exports, Set<String> imports,
                                         int maxPages, boolean allowStart) {
        return new WasmContract(exports, imports, maxPages, allowStart);
    }

    @Test
    void parsesAnEmptyModule() {
        assertNotNull(loader.load(EMPTY_MODULE));
    }

    @Test
    void validPolicyModulePassesContractAndInvokes() {
        WasmModule module = loader.load(DECIDE_42);

        ValidationResult result = loader.validate(module, WasmContract.policyV3());

        assertTrue(result.valid(), result.errors().toString());
        Instance instance = loader.instantiate(module, WasmContract.policyV3());
        assertArrayEquals(new long[]{42}, loader.invoke(instance, "decide"));
    }

    @Test
    void missingRequiredExportIsRejected() {
        WasmModule module = loader.load(EXPORTS_OTHER);

        ValidationResult result = loader.validate(module, WasmContract.policyV3());

        assertFalse(result.valid());
        assertTrue(result.errors().contains("export requerido ausente: decide"), result.errors().toString());
    }

    @Test
    void importsAreRejectedByDefault() {
        WasmModule module = loader.load(IMPORTS_ENV_LOG);

        ValidationResult result = loader.validate(module, WasmContract.policyV3());

        assertFalse(result.valid());
        assertTrue(result.errors().contains("import no permitido: env.log"), result.errors().toString());
    }

    @Test
    void allowedImportModulePermitsImport() {
        WasmModule module = loader.load(IMPORTS_ENV_LOG);
        WasmContract contract = contract(Set.of(), Set.of("env"), 256, false);

        assertTrue(loader.validate(module, contract).valid());
    }

    @Test
    void startSectionIsRejectedUnlessAllowed() {
        WasmModule module = loader.load(WITH_START);

        ValidationResult forbidden = loader.validate(module, contract(Set.of(), Set.of(), 256, false));
        assertFalse(forbidden.valid());
        assertTrue(forbidden.errors().stream().anyMatch(e -> e.contains("start")), forbidden.errors().toString());

        assertTrue(loader.validate(module, contract(Set.of(), Set.of(), 256, true)).valid());
    }

    @Test
    void declaredMemoryAboveLimitIsRejected() {
        WasmModule module = loader.load(MEMORY_300_PAGES);

        ValidationResult result = loader.validate(module, contract(Set.of(), Set.of(), 256, false));

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("memoria inicial 300")), result.errors().toString());
    }

    @Test
    void memoryWithinLimitPassesAndInstanceIsBounded() {
        WasmModule module = loader.load(MEMORY_1_PAGE);
        WasmContract contract = contract(Set.of(), Set.of(), 256, false);

        assertTrue(loader.validate(module, contract).valid());
        Instance instance = loader.instantiate(module, contract);
        assertNotNull(instance.memory());
    }

    @Test
    void invalidModuleIsNotInstantiated() {
        WasmModule module = loader.load(EXPORTS_OTHER);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> loader.instantiate(module, WasmContract.policyV3()));

        assertTrue(error.getMessage().contains("artefacto inválido"), error.getMessage());
    }

    @Test
    void rejectsEmptyBytes() {
        assertThrows(IllegalArgumentException.class, () -> loader.load(new byte[0]));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(NullPointerException.class, () -> loader.load((Path) null));
    }

    @Test
    void rejectsOversizedArtifact() {
        byte[] tooBig = new byte[WasmContract.MAX_ARTIFACT_BYTES + 1];

        assertThrows(IllegalArgumentException.class, () -> loader.load(tooBig));
    }

    @Test
    void invokingUnknownExportFails() {
        Instance instance = loader.instantiate(loader.load(DECIDE_42));

        assertThrows(IllegalArgumentException.class, () -> loader.invoke(instance, "missing"));
    }

    @Test
    void contractRejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class,
            () -> new WasmContract(Set.of(), Set.of(), 0, false));
        assertEquals(256, WasmContract.policyV3().maxMemoryPages());
    }
}
