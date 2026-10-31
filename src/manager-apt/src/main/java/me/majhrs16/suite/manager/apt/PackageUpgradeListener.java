package me.majhrs16.suite.manager.apt;

import java.util.List;

import me.majhrs16.suite.manager.spi.PackageEntry;

/**
 * Runtime hook invoked by the manager after a new package version is installed
 * and before the previous version is removed.
 *
 * <p>The manager owns installation facts; the runtime owns live modules. The
 * listener is the only place where both meet, so a hot upgrade can roll back
 * the new files when the new module does not start.</p>
 */
@FunctionalInterface
public interface PackageUpgradeListener {

    /**
     * @param oldEntries versions currently installed that the upgrade replaces
     * @param newEntries versions just installed by the manager
     * @return whether the manager may remove the old versions
     */
    Decision beforeRemovingOld(List<PackageEntry> oldEntries, List<PackageEntry> newEntries);

    /**
     * @param commit   {@code true} when the runtime accepted the new versions
     * @param keepBoth {@code true} when the manager must keep both the old and
     *                 the new files because runtime compensation failed; the
     *                 runtime is in {@code RESTART_REQUIRED}
     * @param message  failure explanation when {@code commit} is false
     * @param cause    underlying failure, if any
     */
    record Decision(boolean commit, boolean keepBoth, String message, Throwable cause) {

        public static Decision allow() {
            return new Decision(true, false, null, null);
        }

        public static Decision rollback(String message, Throwable cause) {
            return new Decision(false, false, message, cause);
        }

        /**
         * The runtime could not restore every previous version. The manager
         * must not delete either side: the old files may be needed for the
         * restart and the new files may still be in use.
         */
        public static Decision keepBoth(String message, Throwable cause) {
            return new Decision(false, true, message, cause);
        }
    }
}
