package me.majhrs16.suite.iflow;

import java.util.List;
import java.util.Objects;

/**
 * Grafo legacy de {@code rules.yml}, tal como lo edita el panel web.
 *
 * <p>Es un modelo inmutable y sin ejecución: el runtime no evalúa este grafo.
 * Los valores inválidos se descartan durante la carga y se reportan como
 * {@link FirewallIssue}; nunca se construye un grafo con invariantes rotas.</p>
 *
 * @param maxSteps    límite de pasos del grafo ({@code guard.max-steps})
 * @param dedupFanout deduplicación de fan-out ({@code filter.dedup-fanout})
 * @param priority    estrategia de prioridad ({@code filter.priority} o raíz)
 * @param nodes       nodos del grafo
 * @param edges       aristas del grafo
 */
public record RuleGraph(
        int maxSteps,
        boolean dedupFanout,
        String priority,
        List<Node> nodes,
        List<Edge> edges
) {

    public static final int DEFAULT_MAX_STEPS = 512;
    public static final boolean DEFAULT_DEDUP_FANOUT = true;
    public static final String DEFAULT_PRIORITY = "batch-first";

    /** Tipos de nodo aceptados por el editor web. */
    public static final List<String> NODE_KINDS =
        List.of("input", "cond", "transform", "loop", "sleep", "output", "redirect");

    public RuleGraph {
        if (maxSteps < 1) {
            throw new IllegalArgumentException("maxSteps must be >= 1");
        }
        priority = priority == null || priority.isBlank() ? DEFAULT_PRIORITY : priority;
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }

    /** @return grafo vacío con los defaults del editor. */
    public static RuleGraph empty() {
        return new RuleGraph(DEFAULT_MAX_STEPS, DEFAULT_DEDUP_FANOUT, DEFAULT_PRIORITY,
            List.of(), List.of());
    }

    /**
     * Nodo del grafo.
     *
     * @param id   identificador único
     * @param kind tipo, uno de {@link #NODE_KINDS}
     */
    public record Node(String id, String kind) {
    }

    /**
     * Arista dirigida entre dos nodos.
     *
     * @param from id de origen
     * @param to   id de destino
     */
    public record Edge(String from, String to) {
    }
}
