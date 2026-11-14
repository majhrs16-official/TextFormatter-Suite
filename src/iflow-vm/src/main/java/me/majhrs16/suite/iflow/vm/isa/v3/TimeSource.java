package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Source of the virtual nanoseconds read by {@code RDTIME}.
 */
@FunctionalInterface
public interface TimeSource {

    long timeNanos();
}
