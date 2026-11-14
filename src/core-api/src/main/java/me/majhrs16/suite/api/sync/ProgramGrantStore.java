package me.majhrs16.suite.api.sync;

import java.util.Optional;

/**
 * Read-only view of the operator grants for third-party iFlow programs.
 *
 * <p>The store is keyed by the SHA-256 of the program image. An empty result,
 * an unreadable store or an ambiguous entry is a denial: the bus never falls
 * back to ambient authority.</p>
 */
public interface ProgramGrantStore {

    /**
     * @param sha256 lowercase SHA-256 of the program image
     * @return the grant for that image, or empty when the operator did not
     *         authorize it
     */
    Optional<ProgramGrant> find(String sha256);
}
