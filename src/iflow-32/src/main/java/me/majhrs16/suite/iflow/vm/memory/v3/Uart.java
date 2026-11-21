package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * v3 UART at {@code 0x0100_0000} (ADR-037 §5.8).
 *
 * <p>The transmit register is write-only and feeds the host sink. Received
 * bytes are queued by the host; a non-empty queue raises IRQ 1 when enabled.
 * The queue is bounded and overflow is reported in {@code STATUS} instead of
 * blocking the VM.</p>
 */
public final class Uart implements MmioDevice {

    public static final int BASE = 0x0100_0000;
    public static final int IRQ_RX = 1;
    public static final int IRQ_TX = 2;

    public static final int TX = 0x00;
    public static final int RX = 0x04;
    public static final int STATUS = 0x08;
    public static final int CTRL = 0x0C;

    public static final int STATUS_TX_READY = 1;
    public static final int STATUS_RX_AVAILABLE = 1 << 1;
    public static final int STATUS_OVERFLOW = 1 << 2;

    public static final int CTRL_IRQ_RX = 1;
    public static final int CTRL_IRQ_TX = 1 << 1;

    private static final int QUEUE_CAPACITY = 64;

    private final Pic pic;
    private final ArrayDeque<Integer> receiveQueue = new ArrayDeque<>();
    private Consumer<Integer> transmitSink = value -> { };
    private int control;
    private boolean overflow;

    public Uart(Pic pic) {
        this.pic = Objects.requireNonNull(pic, "pic");
    }

    @Override
    public int base() {
        return BASE;
    }

    public synchronized void reset() {
        receiveQueue.clear();
        control = 0;
        overflow = false;
    }

    public synchronized void transmitSink(Consumer<Integer> sink) {
        transmitSink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Queues one received byte. The host calls this when input arrives.
     *
     * @return {@code false} if the bounded queue is full
     */
    public synchronized boolean receive(int value) {
        if (receiveQueue.size() >= QUEUE_CAPACITY) {
            overflow = true;
            return false;
        }
        receiveQueue.add(value & 0xFF);
        if ((control & CTRL_IRQ_RX) != 0) {
            pic.raise(IRQ_RX);
        }
        return true;
    }

    @Override
    public synchronized int readRegister(int offset) {
        return switch (offset) {
            case TX -> 0;
            case RX -> {
                Integer value = receiveQueue.poll();
                yield value == null ? 0 : value;
            }
            case STATUS -> STATUS_TX_READY
                    | (receiveQueue.isEmpty() ? 0 : STATUS_RX_AVAILABLE)
                    | (overflow ? STATUS_OVERFLOW : 0);
            case CTRL -> control;
            default -> throw new IllegalArgumentException(
                    "Unknown UART register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public synchronized void writeRegister(int offset, int value) {
        switch (offset) {
            case TX -> {
                transmitSink.accept(value & 0xFF);
                if ((control & CTRL_IRQ_TX) != 0) {
                    pic.raise(IRQ_TX);
                }
            }
            case RX -> {
                // Read-only: an aligned store is ignored by the device contract.
            }
            case STATUS -> {
                // Read-only: a store does not alter the flags.
            }
            case CTRL -> control = value & (CTRL_IRQ_RX | CTRL_IRQ_TX);
            default -> throw new IllegalArgumentException(
                    "Unknown UART register 0x" + Integer.toHexString(offset));
        }
    }
}
