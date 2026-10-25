package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the iFlow Wasm module: provides the {@code iflow-wasm} capability.
 * The iFlow Wasm engine executes WebAssembly policy programs via Chicory runtime.
 */
public final class IfowWasmModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-wasm")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("iflow-wasm", SemVer.of(2, 1, 0)))
            .build();
    }
}