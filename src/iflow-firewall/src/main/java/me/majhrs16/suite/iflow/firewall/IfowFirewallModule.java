package me.majhrs16.suite.iflow.firewall;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider del módulo {@code iflow-firewall}.
 *
 * <p>Es el dueño del grafo legacy {@code rules.yml} que consume el editor web.
 * No ejecuta reglas ni provee el capability {@code router}: el runtime de
 * políticas es {@code iflow-vm} (bytecode {@code .flow}) y {@code iflow-wasm}
 * (WebAssembly). Este módulo solo modela y valida el grafo.</p>
 */
public final class IfowFirewallModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-firewall")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("firewall", SemVer.of(2, 1, 0)))
            .build();
    }
}
