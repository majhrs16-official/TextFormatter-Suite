package me.majhrs16.suite.iflow.vm.isa.v3;

/**
 * Source of asynchronous interrupt lines checked by the v3 CPU before each
 * instruction fetch.
 *
 * <p>A pending line is consumed when it is delivered: implementations must
 * clear the pending bit and mark the line in service before returning it. The
 * CPU turns line {@code n} into architectural vector {@code 16 + n}.</p>
 */
public interface InterruptController {

    int NO_INTERRUPT = -1;

    /**
     * Samples time-driven interrupt sources. The CPU calls this before every
     * instruction, even when interrupts are disabled, so {@code RDTIME}-based
     * devices keep making progress.
     */
    void poll();

    /**
     * Consumes the highest-priority pending, unmasked, non-serviced line.
     *
     * @return a line in the range 0..31, or {@link #NO_INTERRUPT}
     */
    int takePending();
}
