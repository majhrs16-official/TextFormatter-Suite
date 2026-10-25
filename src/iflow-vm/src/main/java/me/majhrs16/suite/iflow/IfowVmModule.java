package me.majhrs16.suite.iflow;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;

/**
 * SPI provider for the iFlow VM module: provides the {@code iflow-vm} capability.
 * The iFlow VM is a 32-bit virtual machine for executing policy programs (.flow bytecode).
 */
public final class IfowVmModule implements Module {

    @Override
    public ModuleDescriptor descriptor() {
        return ModuleDescriptor.builder("iflow-vm")
            .version(SemVer.of(2, 1, 0))
            .contractVersion(SemVer.of(2, 1, 0))
            .jvmRange(17, 0)
            .provide(Capability.of("iflow-vm", SemVer.of(2, 1, 0)))
            .build();
    }
}