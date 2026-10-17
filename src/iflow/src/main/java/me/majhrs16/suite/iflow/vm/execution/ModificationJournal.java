package me.majhrs16.suite.iflow.vm.execution;

import me.majhrs16.suite.iflow.vm.memory.MemoryRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Journal of message modifications for deferred/atomic commit.
 * <p>
 * All message modifications during execution are recorded here.
 * On successful completion, the runtime validates and applies the journal.
 * On failure, the journal is discarded.
 * </p>
 */
public final class ModificationJournal {

    public enum OpType {
        SET_TEXT,
        SET_CHANNEL,
        SET_LANG_SOURCE,
        SET_LANG_TARGET,
        SET_COLOR_MODE,
        SET_FORMAT_PAPI,
        SET_SLEEP_MILLIS,
        ADD_SOUNDS,
        REMOVE_SOUNDS,
        CANCEL_MESSAGE,
        SET_PROCESSED,
        REDIRECT_CHANNEL,
        REDIRECT_TARGET,    // Redirect to specific actor (value = actor name/UUID)
        RATE_LIMIT_BACKOFF  // Rate limit with backoff (value = millis)
    }

    public static final class Entry {
        public final OpType type;
        public final Object value; // String, Language, ColorMode, Boolean, Long, List<String>
        public final String detail; // Human-readable description

        public Entry(OpType type, Object value, String detail) {
            this.type = Objects.requireNonNull(type);
            this.value = value;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return type + (detail != null ? "(" + detail + ")" : "") + "=" + value;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private boolean committed = false;
    private boolean discarded = false;

    public void record(Entry entry) {
        if (committed || discarded) {
            throw new IllegalStateException("Journal already " + (committed ? "committed" : "discarded"));
        }
        entries.add(entry);
    }

    public void setText(String text) {
        record(new Entry(OpType.SET_TEXT, text, "text"));
    }

    public void setChannel(String channel) {
        record(new Entry(OpType.SET_CHANNEL, channel, "channel"));
    }

    public void setLangSource(String langCode) {
        record(new Entry(OpType.SET_LANG_SOURCE, langCode, "langSource"));
    }

    public void setLangTarget(String langCode) {
        record(new Entry(OpType.SET_LANG_TARGET, langCode, "langTarget"));
    }

    public void setColorMode(String mode) {
        record(new Entry(OpType.SET_COLOR_MODE, mode, "colorMode"));
    }

    public void setFormatPapi(boolean enabled) {
        record(new Entry(OpType.SET_FORMAT_PAPI, enabled, "formatPapi"));
    }

    public void setSleepMillis(long millis) {
        record(new Entry(OpType.SET_SLEEP_MILLIS, millis, "sleepMillis"));
    }

    public void addSounds(List<String> sounds) {
        record(new Entry(OpType.ADD_SOUNDS, sounds, "addSounds"));
    }

    public void removeSounds(List<String> sounds) {
        record(new Entry(OpType.REMOVE_SOUNDS, sounds, "removeSounds"));
    }

    public void cancelMessage() {
        record(new Entry(OpType.CANCEL_MESSAGE, true, "cancel"));
    }

    public void setProcessed() {
        record(new Entry(OpType.SET_PROCESSED, true, "processed"));
    }

    public void redirectChannel(String channel) {
        record(new Entry(OpType.REDIRECT_CHANNEL, channel, "redirect"));
    }

    public void redirectTarget(String targetActor) {
        record(new Entry(OpType.REDIRECT_TARGET, targetActor, "redirect-target"));
    }

    public void rateLimitBackoff(long millis) {
        record(new Entry(OpType.RATE_LIMIT_BACKOFF, millis, "rate-limit"));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() { return entries.isEmpty(); }
    public int size() { return entries.size(); }

    /** Marks journal as committed (modifications applied). */
    public void commit() {
        if (committed) throw new IllegalStateException("Journal already committed");
        if (discarded) throw new IllegalStateException("Journal already discarded");
        committed = true;
    }

    /** Marks journal as discarded (modifications abandoned). */
    public void discard() {
        if (discarded) throw new IllegalStateException("Journal already discarded");
        if (committed) throw new IllegalStateException("Journal already committed");
        discarded = true;
        entries.clear();
    }

    public boolean isCommitted() { return committed; }
    public boolean isDiscarded() { return discarded; }

    /**
     * Resets the journal so its owning context can be reused.
     * <p>
     * Unlike {@link #clear()}, this also lifts the committed/discarded flags: a
     * pooled {@link ExecutionContext} is reset <em>after</em> the validator has
     * committed or discarded the journal, and {@code clear()} would throw
     * {@link IllegalStateException}, silently discarding the context (the pool
     * then never reuses anything).
     * </p>
     */
    public void reset() {
        entries.clear();
        committed = false;
        discarded = false;
    }

    /**
     * Clears the entries of a pristine (not yet committed/discarded) journal.
     * Keeps the guard on purpose; use {@link #reset()} to recycle a journal.
     */
    public void clear() {
        if (committed || discarded) {
            throw new IllegalStateException("Journal already " + (committed ? "committed" : "discarded"));
        }
        entries.clear();
    }

    @Override
    public String toString() {
        return "Journal{entries=" + entries.size() + ", committed=" + committed + ", discarded=" + discarded + "}";
    }
}