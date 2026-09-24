package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of a graph resolution pass. Keeps the per-module outcome so the
 * host can warn about the exact reason a module was skipped.
 */
public final class ResolutionResult {

    private final Map<Module, Node> nodes;

    ResolutionResult(Map<Module, Node> nodes) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public List<Module> resolved() {
        return nodes.entrySet().stream()
                .filter(e -> e.getValue().status == ResolutionStatus.RESOLVED)
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<Module> rejected() {
        return nodes.entrySet().stream()
                .filter(e -> e.getValue().status != ResolutionStatus.RESOLVED)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** @return true when every candidate module was activated. */
    public boolean allResolved() {
        return nodes.values().stream().allMatch(n -> n.status == ResolutionStatus.RESOLVED);
    }

    public ResolutionStatus of(Module module) {
        Node node = nodes.get(module);
        return node == null ? null : node.status;
    }

    public String reasonOf(Module module) {
        Node node = nodes.get(module);
        return node == null ? null : node.reason;
    }

    static final class Node {
        final ResolutionStatus status;
        final String reason;

        Node(ResolutionStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }
}