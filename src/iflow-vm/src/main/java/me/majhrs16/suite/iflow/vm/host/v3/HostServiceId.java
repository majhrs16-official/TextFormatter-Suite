package me.majhrs16.suite.iflow.vm.host.v3;

/**
 * Service identifiers of the v3 {@code HOST} ABI (ADR-037 §7.5).
 *
 * <p>Only these nine services exist in v3.0. Any other identifier is
 * {@code UNSUPPORTED}; v2 identifiers are not accepted.</p>
 */
public final class HostServiceId {

    public static final int PROPERTY_READ = 0x0100;
    public static final int PROPERTY_WRITE = 0x0101;
    public static final int PROPERTY_EXISTS = 0x0102;
    public static final int PERMISSION_CHECK = 0x0200;
    public static final int ROUTE_DECISION = 0x0300;
    public static final int TRANSFORM_APPLY = 0x0400;
    public static final int MESSAGE_ID = 0x0701;
    public static final int SYNC_SURFACE = 0x0800;
    public static final int SYNC_INVOKE = 0x0801;

    private HostServiceId() {
    }
}
