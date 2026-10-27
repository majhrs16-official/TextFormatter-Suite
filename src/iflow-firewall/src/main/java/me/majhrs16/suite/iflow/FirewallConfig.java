package me.majhrs16.suite.iflow;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Carga y valida {@code config/iflow-firewall/rules.yml} (con fallback a
 * {@code rules.yml}).
 *
 * <p>El módulo es el dueño del archivo: el host delega aquí la validación y el
 * editor web consume el mismo grafo. La carga es tolerante: nunca lanza por
 * contenido inválido, acumula incidencias y descarta los elementos que no
 * puede modelar.</p>
 */
public final class FirewallConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private final Path source;
    private final RuleGraph graph;
    private final List<FirewallIssue> issues;

    private FirewallConfig(Path source, RuleGraph graph, List<FirewallIssue> issues) {
        this.source = source;
        this.graph = graph;
        this.issues = List.copyOf(issues);
    }

    /** @return archivo canónico, exista o no. */
    public static Path canonicalRulesFile(Path dataFolder) {
        return Objects.requireNonNull(dataFolder, "dataFolder")
            .resolve("config").resolve("iflow-firewall").resolve("rules.yml");
    }

    /**
     * Resuelve el archivo efectivo: canónico si existe, si no el plano
     * heredado, y si no existe ninguno, el canónico (destino de escritura).
     *
     * @param dataFolder carpeta de datos de la suite
     * @return ruta a leer o crear
     */
    public static Path rulesFile(Path dataFolder) {
        Path canonical = canonicalRulesFile(dataFolder);
        Path legacy = Objects.requireNonNull(dataFolder, "dataFolder").resolve("rules.yml");
        if (Files.exists(canonical) || !Files.exists(legacy)) {
            return canonical;
        }
        return legacy;
    }

    /**
     * Carga el grafo y sus incidencias.
     *
     * @param dataFolder carpeta de datos de la suite
     * @return configuración con defaults si el archivo no existe o es inválido
     */
    public static FirewallConfig load(Path dataFolder) {
        Path file = rulesFile(dataFolder);
        if (!Files.isRegularFile(file)) {
            return new FirewallConfig(file, RuleGraph.empty(), List.of(new FirewallIssue(
                FirewallIssue.Level.INFO,
                "rules.yml",
                "rules.yml no existe; iFlow usará default-accept"
            )));
        }

        Object root;
        try {
            root = YAML.load(Files.readString(file));
        } catch (IOException | RuntimeException exception) {
            return error(file, "error parseando YAML: " + exception.getMessage());
        }

        if (root instanceof List<?>) {
            return legacyRules(file);
        }

        if (!(root instanceof Map<?, ?> raw)) {
            return new FirewallConfig(file, RuleGraph.empty(), List.of(new FirewallIssue(
                FirewallIssue.Level.ERROR,
                "rules.yml",
                "YAML inválido: raíz debe ser un mapa"
            )));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;
        if (map.get("rules") instanceof List<?>) {
            return legacyRules(file);
        }
        List<FirewallIssue> issues = new ArrayList<>();

        int maxSteps = parseGuard(map, issues);
        boolean dedupFanout = parseFilter(map, issues);
        String priority = parsePriority(map);
        List<RuleGraph.Node> nodes = parseNodes(map, issues);
        List<RuleGraph.Edge> edges = parseEdges(map, issues);

        return new FirewallConfig(file, new RuleGraph(maxSteps, dedupFanout, priority, nodes, edges), issues);
    }

    private static FirewallConfig error(Path file, String message) {
        return new FirewallConfig(file, RuleGraph.empty(), List.of(new FirewallIssue(
            FirewallIssue.Level.ERROR,
            "rules.yml",
            message
        )));
    }

    /**
     * El formato histórico de ejecución es una lista de reglas (o un mapa con
     * {@code rules:}). No es un grafo del editor: se acepta sin modelarlo.
     */
    private static FirewallConfig legacyRules(Path file) {
        return new FirewallConfig(file, RuleGraph.empty(), List.of(new FirewallIssue(
            FirewallIssue.Level.INFO,
            "rules.yml",
            "rules.yml legacy (lista de reglas); lo valida iflow-firewall al cargar"
        )));
    }

    private static int parseGuard(Map<String, Object> map, List<FirewallIssue> issues) {
        Object guardObj = map.get("guard");
        if (!(guardObj instanceof Map<?, ?> rawGuard)) {
            return RuleGraph.DEFAULT_MAX_STEPS;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> guard = (Map<String, Object>) rawGuard;
        Object value = guard.get("max-steps");
        if (value == null) {
            return RuleGraph.DEFAULT_MAX_STEPS;
        }
        if (!(value instanceof Number number) || number.intValue() < 1) {
            issues.add(new FirewallIssue(
                FirewallIssue.Level.ERROR,
                "guard.max-steps",
                "debe ser un entero >= 1"
            ));
            return RuleGraph.DEFAULT_MAX_STEPS;
        }
        return number.intValue();
    }

    private static boolean parseFilter(Map<String, Object> map, List<FirewallIssue> issues) {
        Object filterObj = map.get("filter");
        if (!(filterObj instanceof Map<?, ?> rawFilter)) {
            return RuleGraph.DEFAULT_DEDUP_FANOUT;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> filter = (Map<String, Object>) rawFilter;
        Object value = filter.get("dedup-fanout");
        if (value == null) {
            return RuleGraph.DEFAULT_DEDUP_FANOUT;
        }
        if (!(value instanceof Boolean dedup)) {
            issues.add(new FirewallIssue(
                FirewallIssue.Level.WARNING,
                "filter.dedup-fanout",
                "debe ser true/false"
            ));
            return RuleGraph.DEFAULT_DEDUP_FANOUT;
        }
        return dedup;
    }

    private static String parsePriority(Map<String, Object> map) {
        Object filterObj = map.get("filter");
        if (filterObj instanceof Map<?, ?> rawFilter) {
            Object value = ((Map<?, ?>) rawFilter).get("priority");
            if (value instanceof String priority && !priority.isBlank()) {
                return priority;
            }
        }
        Object value = map.get("priority");
        if (value instanceof String priority && !priority.isBlank()) {
            return priority;
        }
        return RuleGraph.DEFAULT_PRIORITY;
    }

    private static List<RuleGraph.Node> parseNodes(Map<String, Object> map, List<FirewallIssue> issues) {
        Object nodesObj = map.get("nodes");
        if (!(nodesObj instanceof List<?> rawNodes)) {
            return List.of();
        }
        List<RuleGraph.Node> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rawNodes.size(); i++) {
            Object raw = rawNodes.get(i);
            if (!(raw instanceof Map<?, ?> rawNode)) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "nodes[" + i + "]",
                    "cada nodo debe ser un mapa"
                ));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) rawNode;

            String id = null;
            Object rawId = node.get("id");
            if (!(rawId instanceof String idStr) || idStr.isBlank()) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "nodes[" + i + "].id",
                    "id obligatorio y debe ser string"
                ));
            } else {
                id = idStr;
                if (!ids.add(id)) {
                    issues.add(new FirewallIssue(
                        FirewallIssue.Level.ERROR,
                        "nodes[" + i + "].id",
                        "id duplicado: " + id
                    ));
                    id = null;
                }
            }

            String kind = null;
            Object rawKind = node.get("kind");
            if (!(rawKind instanceof String kindStr)) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "nodes[" + i + "].kind",
                    "kind obligatorio: " + String.join(", ", RuleGraph.NODE_KINDS)
                ));
            } else if (!RuleGraph.NODE_KINDS.contains(kindStr)) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "nodes[" + i + "].kind",
                    "kind inválido: " + kindStr + " (esperado: " + RuleGraph.NODE_KINDS + ")"
                ));
            } else {
                kind = kindStr;
            }

            if (id != null && kind != null) {
                nodes.add(new RuleGraph.Node(id, kind));
            }
        }
        return nodes;
    }

    private static List<RuleGraph.Edge> parseEdges(Map<String, Object> map, List<FirewallIssue> issues) {
        Object edgesObj = map.get("edges");
        if (!(edgesObj instanceof List<?> rawEdges)) {
            return List.of();
        }
        List<RuleGraph.Edge> edges = new ArrayList<>();
        for (int i = 0; i < rawEdges.size(); i++) {
            Object raw = rawEdges.get(i);
            if (!(raw instanceof Map<?, ?> rawEdge)) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "edges[" + i + "]",
                    "cada edge debe ser un mapa con from y to"
                ));
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> edge = (Map<String, Object>) rawEdge;
            Object rawFrom = edge.get("from");
            Object rawTo = edge.get("to");
            boolean valid = true;
            if (!(rawFrom instanceof String from) || from.isBlank()) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "edges[" + i + "].from",
                    "from obligatorio (string)"
                ));
                valid = false;
            }
            if (!(rawTo instanceof String to) || to.isBlank()) {
                issues.add(new FirewallIssue(
                    FirewallIssue.Level.ERROR,
                    "edges[" + i + "].to",
                    "to obligatorio (string)"
                ));
                valid = false;
            }
            if (valid) {
                edges.add(new RuleGraph.Edge((String) rawFrom, (String) rawTo));
            }
        }
        return edges;
    }

    /** @return archivo del que se cargó (o se cargaría) el grafo. */
    public Path source() {
        return source;
    }

    /** @return grafo modelado; los elementos inválidos se han descartado. */
    public RuleGraph graph() {
        return graph;
    }

    /** @return incidencias acumuladas, en orden de aparición. */
    public List<FirewallIssue> issues() {
        return issues;
    }

    /** @return {@code true} si no hay incidencias de nivel {@link FirewallIssue.Level#ERROR}. */
    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.level() == FirewallIssue.Level.ERROR);
    }
}
