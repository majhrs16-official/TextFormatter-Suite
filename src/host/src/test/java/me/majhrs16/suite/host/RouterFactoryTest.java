package me.majhrs16.suite.host;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.SemVer;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.IflowFirewallModule;
import me.majhrs16.suite.iflow.IfowVmModule;
import me.majhrs16.suite.api.iflow.Router;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;

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
    void iflowEnginesAreAlternativeModules() {
        ModuleDescriptor firewall = new IflowFirewallModule().descriptor();
        ModuleDescriptor vm = new IfowVmModule().descriptor();
        assertEquals("iflow-firewall", firewall.name());
        assertEquals("iflow-vm", vm.name());
        assertTrue(firewall.provides().contains(Capability.of("router", SemVer.of(2, 1, 0))));
        assertTrue(vm.provides().contains(Capability.of("iflow-vm", SemVer.of(2, 1, 0))));
    }

    @Test
    void onlyTheSelectedIflowEngineReachesTheKernel() {
        List<Module> engines = List.of(new IflowFirewallModule(), new IfowVmModule());

        assertEquals(List.of("iflow-vm"),
            names(IflowEngineSelector.select(engines, HostConfig.Engine.VM)));
        assertEquals(List.of("iflow-firewall"),
            names(IflowEngineSelector.select(engines, HostConfig.Engine.FIREWALL)));
    }

    private static List<String> names(List<Module> modules) {
        return modules.stream().map(module -> module.descriptor().name()).toList();
    }
}
