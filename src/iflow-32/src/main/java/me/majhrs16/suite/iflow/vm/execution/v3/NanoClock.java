package me.majhrs16.suite.iflow.vm.execution.v3;

/**
 * Wall-clock source used only by the host watchdog.
 */
@FunctionalInterface
public interface NanoClock {

    long nanoTime();
}
