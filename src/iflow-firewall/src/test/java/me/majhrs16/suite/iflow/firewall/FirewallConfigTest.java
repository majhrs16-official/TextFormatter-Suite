package me.majhrs16.suite.iflow.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FirewallConfigTest {

    @TempDir
    Path dir;

    private Path canonical() throws Exception {
        Path file = dir.resolve("config/iflow-firewall/rules.yml");
        Files.createDirectories(file.getParent());
        return file;
    }

    private Path legacy() {
        return dir.resolve("rules.yml");
    }

    private static boolean hasIssue(FirewallConfig config, FirewallIssue.Level level, String path) {
        return config.issues().stream()
            .anyMatch(issue -> issue.level() == level && issue.path().equals(path));
    }

    @Test
    void missingFileReportsInfoAndDefaults() {
        FirewallConfig config = FirewallConfig.load(dir);

        assertTrue(config.valid());
        assertEquals(1, config.issues().size());
        assertEquals(FirewallIssue.Level.INFO, config.issues().get(0).level());
        assertEquals("rules.yml", config.issues().get(0).path());
        assertEquals(RuleGraph.empty(), config.graph());
        assertEquals(FirewallConfig.canonicalRulesFile(dir), config.source());
    }

    @Test
    void canonicalFileWinsOverLegacy() throws Exception {
        Files.writeString(canonical(), """
            guard:
              max-steps: 128
            filter:
              dedup-fanout: false
              priority: priority-first
            priority: ignored-root
            nodes:
              - id: in
                kind: input
              - id: out
                kind: output
            edges:
              - from: in
                to: out
            """);
        Files.writeString(legacy(), "guard:\n  max-steps: 1\n");

        FirewallConfig config = FirewallConfig.load(dir);

        assertTrue(config.valid(), config.issues().toString());
        assertEquals(canonical(), config.source());
        assertEquals(128, config.graph().maxSteps());
        assertFalse(config.graph().dedupFanout());
        assertEquals("priority-first", config.graph().priority());
        assertEquals(2, config.graph().nodes().size());
        assertEquals(new RuleGraph.Edge("in", "out"), config.graph().edges().get(0));
    }

    @Test
    void legacyFileIsReadDuringMigration() throws Exception {
        Files.writeString(legacy(), """
            guard:
              max-steps: 32
            nodes:
              - id: only
                kind: cond
            """);

        FirewallConfig config = FirewallConfig.load(dir);

        assertEquals(legacy(), config.source());
        assertEquals(32, config.graph().maxSteps());
        assertEquals(1, config.graph().nodes().size());
    }

    @Test
    void topLevelPriorityIsUsedWhenFilterHasNone() throws Exception {
        Files.writeString(canonical(), "priority: round-robin\n");

        assertEquals("round-robin", FirewallConfig.load(dir).graph().priority());
    }

    @Test
    void invalidGuardIsAnErrorAndFallsBackToDefault() throws Exception {
        Files.writeString(canonical(), "guard:\n  max-steps: 0\n");

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "guard.max-steps"), config.issues().toString());
        assertEquals(RuleGraph.DEFAULT_MAX_STEPS, config.graph().maxSteps());
    }

    @Test
    void nonNumericGuardIsAnError() throws Exception {
        Files.writeString(canonical(), "guard:\n  max-steps: muchos\n");

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "guard.max-steps"));
    }

    @Test
    void invalidFilterIsAWarningButNotFatal() throws Exception {
        Files.writeString(canonical(), "filter:\n  dedup-fanout: \"si\"\n");

        FirewallConfig config = FirewallConfig.load(dir);

        assertTrue(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.WARNING, "filter.dedup-fanout"));
        assertTrue(config.graph().dedupFanout());
    }

    @Test
    void invalidNodesAreReportedAndDiscarded() throws Exception {
        Files.writeString(canonical(), """
            nodes:
              - "texto"
              - kind: input
              - id: dup
                kind: input
              - id: dup
                kind: output
              - id: sin-kind
              - id: malo
                kind: inventado
            """);

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "nodes[0]"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "nodes[1].id"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "nodes[3].id"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "nodes[4].kind"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "nodes[5].kind"));
        assertEquals(1, config.graph().nodes().size());
        assertEquals(new RuleGraph.Node("dup", "input"), config.graph().nodes().get(0));
    }

    @Test
    void invalidEdgesAreReportedAndDiscarded() throws Exception {
        Files.writeString(canonical(), """
            edges:
              - "texto"
              - to: out
              - from: in
              - from: in
                to: out
            """);

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "edges[0]"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "edges[1].from"));
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "edges[2].to"));
        assertEquals(1, config.graph().edges().size());
    }

    @Test
    void rootMustBeAMap() throws Exception {
        Files.writeString(canonical(), "solo texto");

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.ERROR, "rules.yml"));
        assertEquals(RuleGraph.empty(), config.graph());
    }

    @Test
    void legacyRuleListIsAcceptedAsInfo() throws Exception {
        Files.writeString(canonical(), """
            - id: drop-private
              target: DROP
              channel: private
            """);

        FirewallConfig config = FirewallConfig.load(dir);

        assertTrue(config.valid(), config.issues().toString());
        assertTrue(hasIssue(config, FirewallIssue.Level.INFO, "rules.yml"));
        assertEquals(RuleGraph.empty(), config.graph());
    }

    @Test
    void legacyRulesKeyIsAcceptedAsInfo() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: drop-private
                target: DROP
            """);

        FirewallConfig config = FirewallConfig.load(dir);

        assertTrue(config.valid());
        assertTrue(hasIssue(config, FirewallIssue.Level.INFO, "rules.yml"));
    }

    @Test
    void malformedYamlIsReported() throws Exception {
        Files.writeString(canonical(), "guard: [sin-cerrar\n");

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(config.issues().get(0).message().startsWith("error parseando YAML:"),
            config.issues().toString());
    }

    @Test
    void emptyFileIsReportedAsNonMap() throws Exception {
        Files.writeString(canonical(), "");

        FirewallConfig config = FirewallConfig.load(dir);

        assertFalse(config.valid());
        assertTrue(config.issues().get(0).message().contains("raíz debe ser un mapa"));
    }

    @Test
    void canonicalPathIsTheWriteDestinationWhenNothingExists() {
        assertEquals(dir.resolve("config/iflow-firewall/rules.yml"), FirewallConfig.rulesFile(dir));
    }
}
