package me.majhrs16.suite.host;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.IfowVmModule;
import me.majhrs16.suite.iflow.Router;
import me.majhrs16.suite.iflow.channel.PermissionChecker;
import me.majhrs16.suite.iflow.firewall.IflowModule;
import me.majhrs16.suite.iflow.target.PolicyTarget;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;
import me.majhrs16.suite.textformatter.TextFormatterModule;
import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterFactoryTest {

    private static final Actor STEVE = Actor.unknown("Steve");
    private static final Actor ALEX = Actor.unknown("Alex");

    @TempDir
    Path dir;

    private static ChannelRegistry registry() {
        return ChannelRegistry.builder()
            .register(Channel.builder("chat").build())
            .register(Channel.builder("private").build())
            .build();
    }

    private static Message message(String path, String text) {
        return Message.builder()
            .sender(STEVE)
            .direction(Direction.others())
            .text(text)
            .channel(path)
            .build();
    }

    private static HostConfig config(HostConfig.Engine engine) {
        return new HostConfig(true, Language.EN, false, engine, true,
            HostConfig.ClaimMode.CANCEL_EVENT, true, List.of(), List.of());
    }

    @Test
    void vmEngineIsTheDefault() {
        Router router = RouterFactory.create(HostConfig.defaults(), dir, registry(),
            PermissionChecker.ALLOW_ALL, null, null, null);

        assertEquals(PolicyTarget.LOG, router.route(message("chat", "hola"), ALEX).decision().target());
    }

    @Test
    void firewallEngineAppliesLegacySpelRules() throws Exception {
        Path rules = dir.resolve("config/iflow-firewall/rules.yml");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, """
            - id: drop-private
              target: DROP
              channel: private
            - id: drop-hola
              target: DROP
              condition: "#content == 'hola'"
            """);

        Router router = RouterFactory.create(config(HostConfig.Engine.FIREWALL), dir, registry(),
            PermissionChecker.ALLOW_ALL, null, null, null);

        assertEquals(PolicyTarget.DROP,
            router.route(message("private", "adios"), ALEX).decision().target());
        assertEquals(PolicyTarget.DROP,
            router.route(message("chat", "hola"), ALEX).decision().target());
        assertEquals(PolicyTarget.LOG,
            router.route(message("chat", "adios"), ALEX).decision().target());

        ((AutoCloseable) router).close();
    }

    @Test
    void bothIflowModulesResolveInTheSameGraph() {
        ModuleGraph graph = new ModuleGraph(Environment.current());
        ResolutionResult result = graph.resolve(List.of(
            new IflowModule(),
            new IfowVmModule(),
            new TextFormatterModule()));

        assertTrue(result.allResolved(), result.rejected().toString());
    }
}
