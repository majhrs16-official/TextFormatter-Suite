package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.Objects;

import me.majhrs16.suite.api.iflow.TlvCodec;

/**
 * One provisional property mutation of a message unit.
 *
 * <p>The journal owns these entries until the host commits the unit. A
 * committed write is applied through
 * {@link HostBridge#applyPropertyWrites(java.util.List)} so the host can make
 * the batch atomic.</p>
 */
public record PropertyWrite(int pident,
                            int mident,
                            String scope,
                            String property,
                            TlvCodec.TlvValue value) {

    public PropertyWrite {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(value, "value");
    }
}
