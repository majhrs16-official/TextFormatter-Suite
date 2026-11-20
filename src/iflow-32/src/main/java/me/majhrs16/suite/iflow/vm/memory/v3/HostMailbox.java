package me.majhrs16.suite.iflow.vm.memory.v3;

import java.util.Objects;

/**
 * v3 host mailbox at {@code 0x0100_6000} (ADR-037 §5.8 and §6.7).
 *
 * <p>Publishes one opaque message/event at a time and raises IRQ 5. The kernel
 * reads the event and handle, then writes {@code ACK} to clear the pending
 * bit. The handle is opaque to the ABI.</p>
 */
public final class HostMailbox implements MmioDevice {

    public static final int BASE = 0x0100_6000;
    public static final int IRQ_LINE = 5;

    public static final int EVENT_TYPE = 0x00;
    public static final int MESSAGE_HANDLE = 0x04;
    public static final int EVENT_SEQ = 0x08;
    public static final int TARGET = 0x0C;
    public static final int ACK = 0x10;
    public static final int STATUS = 0x14;

    public static final int TYPE_MESSAGE = 1;
    public static final int TYPE_SHUTDOWN = 2;
    public static final int TYPE_ACTION_COMPLETED = 3;

    public static final int STATUS_PENDING = 1;

    private final Pic pic;
    private int eventType;
    private int handle;
    private int sequence;
    private int target;
    private boolean pending;

    public HostMailbox(Pic pic) {
        this.pic = Objects.requireNonNull(pic, "pic");
    }

    @Override
    public int base() {
        return BASE;
    }

    /**
     * Publishes an event and raises IRQ 5.
     */
    public void publish(int type, int messageHandle) {
        eventType = type;
        handle = messageHandle;
        sequence++;
        pending = true;
        pic.raise(target, IRQ_LINE);
    }

    public boolean pending() {
        return pending;
    }

    public int target() {
        return target;
    }

    public void reset() {
        eventType = 0;
        handle = 0;
        sequence = 0;
        target = 0;
        pending = false;
    }

    @Override
    public int readRegister(int offset) {
        return switch (offset) {
            case EVENT_TYPE -> eventType;
            case MESSAGE_HANDLE -> handle;
            case EVENT_SEQ -> sequence;
            case TARGET -> target;
            case ACK -> 0;
            case STATUS -> pending ? STATUS_PENDING : 0;
            default -> throw new IllegalArgumentException(
                    "Unknown mailbox register 0x" + Integer.toHexString(offset));
        };
    }

    @Override
    public void writeRegister(int offset, int value) {
        switch (offset) {
            case TARGET -> setTarget(value);
            case ACK -> {
                if (value == 1) {
                    pending = false;
                }
            }
            case EVENT_TYPE, MESSAGE_HANDLE, EVENT_SEQ, STATUS -> {
                // Read-only registers.
            }
            default -> throw new IllegalArgumentException(
                    "Unknown mailbox register 0x" + Integer.toHexString(offset));
        }
    }

    private void setTarget(int value) {
        if ((value & ~0xFF) != 0) {
            throw new IllegalArgumentException(
                    "HOST_MAILBOX.TARGET must be 0..0xFF: 0x" + Integer.toHexString(value));
        }
        int next = value & 0xFF;
        if (next >= pic.coreCount()) {
            throw new IllegalArgumentException(
                    "HOST_MAILBOX.TARGET core out of range: " + next
                            + " (core-count " + pic.coreCount() + ")");
        }
        target = next;
    }
}
