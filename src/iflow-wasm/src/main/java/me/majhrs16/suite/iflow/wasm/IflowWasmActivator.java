package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;

/**
 * Runtime activator of the {@code iflow-wasm} package.
 *
 * <p>Publishes the Chicory-backed loader and the default policy contract. The
 * policy engine that turns the loader into a {@code Router} belongs to W3; the
 * loader itself is already usable by consumers.</p>
 */
public final class IflowWasmActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-wasm")
                .version(SemVer.of(2, 1, 0))
                .contractVersion(SemVer.of(2, 1, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("iflow-wasm", SemVer.of(2, 1, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        WasmModuleLoader loader = new WasmModuleLoader();
        WasmContract contract = WasmContract.policyV3();

        context.services().register(WasmModuleLoader.class, loader);
        context.services().register(WasmContract.class, contract);
        context.logger().info("iFlow Wasm loader ready");
    }

    @Override
    public void stop() {
        // The loader and contract hold no native or thread resources.
    }
}
