package me.majhrs16.suite.host;

import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.host.config.HostConfig;

import java.util.List;
import java.util.Set;

/**
 * Enforces the iFlow engine exclusivity rule before the kernel resolves the
 * module graph.
 *
 * <p>{@code iflow-vm}, {@code iflow-firewall} and {@code iflow-wasm} are
 * alternative engines: only one may be active at a time. A development
 * classpath can still contain more than one JAR (the monorepo builds all of
 * them for tests), so this selector drops the engines that do not match
 * {@code iflow.engine.kind}. Production packaging must additionally ship only
 * one engine JAR.</p>
 */
final class IflowEngineSelector {

    private static final Set<String> IFLOW_ENGINES =
        Set.of("iflow-vm", "iflow-firewall", "iflow-wasm");

    private IflowEngineSelector() {
    }

    /**
     * Keeps the engine selected by {@code iflow.engine.kind} and removes the
     * alternative iFlow engine descriptors. Non-iFlow modules are untouched.
     *
     * @param modules discovered descriptors
     * @param engine  configured engine
     * @return descriptors to feed into the kernel graph
     */
    static List<Module> select(List<Module> modules, HostConfig.Engine engine) {
        String selected = engine == HostConfig.Engine.FIREWALL ? "iflow-firewall" : "iflow-vm";
        return modules.stream()
            .filter(module -> {
                String name = module.descriptor().name();
                return !IFLOW_ENGINES.contains(name) || name.equals(selected);
            })
            .toList();
    }
}
