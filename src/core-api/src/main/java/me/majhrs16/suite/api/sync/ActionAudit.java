package me.majhrs16.suite.api.sync;

/**
 * Append-only audit sink for sync actions.
 *
 * <p>Program actions require the sink to be present and writable before the
 * action runs: without an audit trail there is no effect. Official callers use
 * the sink best-effort so a full disk never stops the message pipeline.</p>
 */
public interface ActionAudit {

    /**
     * Verifies that a new entry can be appended.
     *
     * @throws IllegalStateException when the audit trail is not writable
     */
    void ensureWritable();

    /**
     * Appends one entry.
     *
     * @param record entry to persist; never {@code null}
     * @throws IllegalStateException when the entry cannot be persisted
     */
    void record(ActionAuditRecord record);
}
