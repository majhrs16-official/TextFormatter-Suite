package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Synchronous {@code HOST} hypercall handler.
 */
@FunctionalInterface
public interface HostHandler {

    HostResult call(HostCall call);
}
