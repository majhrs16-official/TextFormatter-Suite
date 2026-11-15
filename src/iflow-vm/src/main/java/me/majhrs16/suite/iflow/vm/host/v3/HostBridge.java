package me.majhrs16.suite.iflow.vm.host.v3;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import me.majhrs16.suite.api.iflow.TlvCodec;
import me.majhrs16.suite.api.sync.SyncResult;
import me.majhrs16.suite.api.sync.SyncSurface;

/**
 * Host-owned side of the v3 {@code HOST} ABI.
 *
 * <p>{@link HostAbi} owns the wire contract: register envelope, TLV decoding,
 * identity and capability checks, status codes and buffer bounds. This port
 * owns the policy data and actions. Implementations must return explicit
 * statuses or throw {@link HostServiceException}; they must not return a
 * default success for an unsupported operation.</p>
 */
public interface HostBridge {

    /**
     * Verifies that {@code pident} names a program whose SHA-256 and declared
     * capabilities were validated by the host.
     */
    boolean verifyProgram(int pident);

    /**
     * Verifies that {@code mident} is the handle of the message currently being
     * processed.
     */
    boolean verifyMessage(int mident);

    /**
     * Capabilities granted to a verified program, already intersected with the
     * capabilities declared in its ROM image.
     */
    Set<String> capabilities(int pident);

    Optional<TlvCodec.TlvValue> propertyRead(int pident, int mident,
                                             String scope, String property,
                                             Map<String, TlvCodec.TlvValue> arguments)
            throws HostServiceException;

    void propertyWrite(int pident, int mident, String scope, String property,
                       TlvCodec.TlvValue value) throws HostServiceException;

    boolean propertyExists(int pident, int mident, String scope, String property)
            throws HostServiceException;

    boolean permissionCheck(int pident, int mident, String actor,
                            String channel, String permission)
            throws HostServiceException;

    void routeDecision(int pident, int mident, int target,
                       String reason, String error) throws HostServiceException;

    TlvCodec.TlvValue transformApply(int pident, int mident, String kind,
                                     TlvCodec.TlvValue input)
            throws HostServiceException;

    String messageId(int mident) throws HostServiceException;

    Optional<List<SyncSurface>> syncSurfaces(int pident) throws HostServiceException;

    SyncResult syncInvoke(int pident, int mident, String backend, String action,
                          TlvCodec.TlvValue args, String idempotencyKey,
                          String reason) throws HostServiceException;
}
