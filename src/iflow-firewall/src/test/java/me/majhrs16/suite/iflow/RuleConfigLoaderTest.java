package me.majhrs16.suite.iflow;

import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.iflow.rule.TransformOp;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleConfigLoaderTest {

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

    @Test
    void missingFileYieldsEmptyRuleSet() {
        assertTrue(RuleConfigLoader.loadRules(dir, null).isEmpty());
    }

    @Test
    void canonicalRuleListIsLoadedWithConditionActionAndTransforms() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: drop-private
                target: DROP
                channel: private
                priority: 5
                reason: staff only
                condition: "#content == 'hola'"
                action: "#surface.cancel()"
                transforms:
                  - op: rewrite
                    template: nuevo
                  - op: sleep
                    millis: 10
              - id: move
                target: CHANNEL_REDIRECT
                redirect-channel: staff
            config-version: 1.0
            """);

        List<Rule> rules = RuleConfigLoader.loadRules(dir, null);

        assertEquals(2, rules.size());
        Rule first = rules.get(0);
        assertEquals("drop-private", first.id());
        assertEquals(5, first.priority());
        assertEquals(PolicyTarget.DROP, first.target());
        assertEquals("private", first.channelPath());
        assertEquals("#content == 'hola'", first.condition());
        assertEquals("#surface.cancel()", first.action());
        assertEquals(2, first.transforms().size());
        assertInstanceOf(TransformOp.Rewrite.class, first.transforms().get(0));
        assertInstanceOf(TransformOp.Sleep.class, first.transforms().get(1));
        assertEquals("staff", rules.get(1).redirectChannel());
    }

    @Test
    void rulesKeyMapIsLoaded() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: only
                target: REJECT
            config-version: 1.0
            """);

        List<Rule> rules = RuleConfigLoader.loadRules(dir, null);

        assertEquals(1, rules.size());
        assertEquals(PolicyTarget.REJECT, rules.get(0).target());
    }

    @Test
    void graphOnlyFileYieldsNoExecutableRules() throws Exception {
        Files.writeString(canonical(), """
            guard:
              max-steps: 512
            nodes:
              - id: in
                kind: input
            edges: []
            config-version: 1.0
            """);

        assertTrue(RuleConfigLoader.loadRules(dir, null).isEmpty());
    }

    @Test
    void canonicalWinsOverLegacy() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: canonical
                target: DROP
            config-version: 1.0
            """);
        Files.writeString(legacy(), """
            rules:
              - id: legacy-a
                target: LOG
              - id: legacy-b
                target: LOG
            config-version: 1.0
            """);

        List<Rule> rules = RuleConfigLoader.loadRules(dir, null);

        assertEquals(1, rules.size());
        assertEquals("canonical", rules.get(0).id());
    }

    @Test
    void legacyFlatFileIsIgnored() throws Exception {
        Files.writeString(legacy(), """
            rules:
              - id: legacy
                target: DROP
            config-version: 1.0
            """);

        assertTrue(RuleConfigLoader.loadRules(dir, null).isEmpty());
    }

    @Test
    void missingConfigVersionYieldsNoExecutableRules() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: only
                target: REJECT
            """);

        assertTrue(RuleConfigLoader.loadRules(dir, null).isEmpty());
    }

    @Test
    void invalidTargetIsSkippedButValidRulesSurvive() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: malo
                target: EXPLODE
              - id: bueno
                target: LOG
            config-version: 1.0
            """);

        List<Rule> rules = RuleConfigLoader.loadRules(dir, null);

        assertEquals(1, rules.size());
        assertEquals("bueno", rules.get(0).id());
    }

    @Test
    void channelRedirectWithoutTargetIsSkipped() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: sin-destino
                target: CHANNEL_REDIRECT
            config-version: 1.0
            """);

        assertTrue(RuleConfigLoader.loadRules(dir, null).isEmpty());
    }

    @Test
    void legacyTransformStringBecomesRewrite() throws Exception {
        Files.writeString(canonical(), """
            rules:
              - id: rewrite
                target: LOG
                transform: "<green>hola</green>"
            config-version: 1.0
            """);

        List<Rule> rules = RuleConfigLoader.loadRules(dir, null);

        assertEquals(1, rules.size());
        assertInstanceOf(TransformOp.Rewrite.class, rules.get(0).transforms().get(0));
    }

    @Test
    void canonicalPathIsTheWriteDestinationWhenNothingExists() {
        assertEquals(dir.resolve("config/iflow-firewall/rules.yml"), RuleConfigLoader.rulesFile(dir));
    }
}
