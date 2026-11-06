package me.majhrs16.suite.host;

import me.majhrs16.suite.api.pipeline.PipelineTrace;
import me.majhrs16.suite.api.pipeline.StageTrace;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe, bounded trace for one dispatch.
 *
 * <p>The bound keeps a pathological plan from retaining unbounded memory; the
 * oldest entries are evicted first. Traces never store message content.</p>
 */
public final class BoundedPipelineTrace implements PipelineTrace {

    private final int capacity;
    private final Deque<StageTrace> entries;

    public BoundedPipelineTrace(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void record(StageTrace entry) {
        Objects.requireNonNull(entry, "entry");
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    @Override
    public synchronized List<StageTrace> entries() {
        return List.copyOf(entries);
    }

    /** @return the most recent {@code limit} entries, oldest first */
    public synchronized List<StageTrace> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        int skip = Math.max(0, entries.size() - limit);
        return entries.stream().skip(skip).toList();
    }
}
