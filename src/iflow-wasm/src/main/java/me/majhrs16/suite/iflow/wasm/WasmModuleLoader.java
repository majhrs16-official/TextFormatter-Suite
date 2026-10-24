package me.majhrs16.suite.iflow.wasm;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Minimal Chicory adapter for the {@code iflow-wasm} line.
 *
 * <p>This class only covers the mechanical part of W0: parse a {@code .wasm} artifact and create an
 * isolated Chicory instance. Validation, host imports, capabilities, limits and the routing
 * contract belong to W1/W2 and are intentionally not implemented here.</p>
 */
public final class WasmModuleLoader {

    /**
     * Parses a WebAssembly module from disk.
     *
     * @param wasmFile path to a {@code .wasm} artifact
     * @return the parsed module, ready to be instantiated
     * @throws NullPointerException if {@code wasmFile} is {@code null}
     * @throws com.dylibso.chicory.wasm.ChicoryException if the artifact is not a valid module
     */
    public WasmModule load(Path wasmFile) {
        Objects.requireNonNull(wasmFile, "wasmFile");
        return Parser.parse(wasmFile);
    }

    /**
     * Parses a WebAssembly module from memory.
     *
     * @param wasmBytes raw {@code .wasm} bytes
     * @return the parsed module, ready to be instantiated
     * @throws NullPointerException if {@code wasmBytes} is {@code null}
     * @throws IllegalArgumentException if {@code wasmBytes} is empty
     * @throws com.dylibso.chicory.wasm.ChicoryException if the bytes are not a valid module
     */
    public WasmModule load(byte[] wasmBytes) {
        Objects.requireNonNull(wasmBytes, "wasmBytes");
        if (wasmBytes.length == 0) {
            throw new IllegalArgumentException("wasmBytes is empty");
        }
        return Parser.parse(wasmBytes);
    }

    /**
     * Creates a fresh Chicory instance for the given module.
     *
     * <p>Each call returns an instance with its own linear memory. Host imports and resource limits
     * are not wired yet; callers must treat this as an internal W0 building block.</p>
     *
     * @param module parsed WebAssembly module
     * @return a new isolated instance
     * @throws NullPointerException if {@code module} is {@code null}
     */
    public Instance instantiate(WasmModule module) {
        Objects.requireNonNull(module, "module");
        return Instance.builder(module).build();
    }
}
