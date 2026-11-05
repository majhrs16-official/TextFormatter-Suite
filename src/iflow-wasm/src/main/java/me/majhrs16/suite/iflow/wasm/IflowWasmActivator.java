package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.IflowBackend;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.spi.PlaceholderResolver;
import me.majhrs16.suite.api.spi.TranslationService;

/**
 * Runtime activator of the {@code iflow-wasm} package.
 *
 * <p>Publica el loader de Chicory, el contrato v3 y un {@link Router} que carga
 * {@code policy.wasm} con SHA-256 obligatorio y recarga automática. El router
 * también se publica como {@link IflowBackend} para que el hub {@code iflow}
 * pueda seleccionarlo o aplicar fallback si el artefacto no está disponible.</p>
 */
public final class IflowWasmActivator implements ModuleActivator {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-wasm")
                .version(SemVer.of(1, 0, 0))
                .contractVersion(SemVer.of(1, 0, 0))
                .jvmRange(17, 0)
                .provide(Capability.of("iflow-wasm", SemVer.of(1, 0, 0)))
                .provide(Capability.of("router", SemVer.of(1, 0, 0)))
                .require(Requirement.atLeast("channel-api", SemVer.of(1, 0, 0)))
                .build();
    }

    @Override
    public void start(ModuleContext context) {
        ChannelRegistry channels = context.services().find(ChannelRegistry.class)
                .orElseThrow(() -> new IllegalStateException(
                        "iflow-wasm requires the channel-api capability"));
        PermissionChecker permissions = context.services().find(PermissionChecker.class)
                .orElse(PermissionChecker.ALLOW_ALL);
        PlaceholderResolver placeholders = context.services()
                .find(PlaceholderResolver.class)
                .orElse(null);
        TranslationService translation = context.services()
                .find(TranslationService.class)
                .orElse(null);

        WasmModuleLoader loader = new WasmModuleLoader();
        WasmContract contract = WasmContract.policyV3();
        WasmPolicyRouter router = new WasmPolicyRouter(
                context.configDir(),
                channels,
                permissions,
                placeholders,
                translation == null ? null : translation::detect,
                context.logger());

        context.services().register(WasmModuleLoader.class, loader);
        context.services().register(WasmContract.class, contract);
        context.services().register(WasmPolicyRouter.class, router);
        context.services().register(Router.class, router);
        context.services().register(IflowBackend.class, new IflowBackend() {
            @Override
            public String name() {
                return "iflow-wasm";
            }

            @Override
            public Router router() {
                return router;
            }

            @Override
            public boolean isAvailable() {
                return router.isAvailable();
            }
        });
        context.resources().track(router);
        if (router.isAvailable()) {
            context.logger().info("iFlow Wasm engine ready");
        } else {
            context.logger().warn("iFlow Wasm engine ready without policy: " + router.lastError());
        }
    }

    @Override
    public void stop() {
        // El ámbito del módulo cierra el router y su watcher.
    }
}
