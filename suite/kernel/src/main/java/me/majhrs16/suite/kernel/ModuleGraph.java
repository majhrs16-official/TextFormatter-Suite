package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.SemVer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a set of candidate modules against a host environment into an
 * activation order, reporting per-module status through
 * {@link ResolutionResult}.
 *
 * <p>Two native capabilities are always available: {@code api} (the host's
 * contract version) and {@code jvm} (the running JVM). Requiring them engages
 * the handshake; a module compiled against a contract the host cannot honour
 * is rejected with {@link ResolutionStatus#CONTRACT_MISMATCH}, and a module
 * that can only ever be satisfied by itself or its own cycle is reported as
 * {@link ResolutionStatus#CYCLE}.</p>
 */
public final class ModuleGraph {

    private final Environment environment;

    public ModuleGraph(Environment environment) {
        this.environment = environment;
    }

    public ResolutionResult resolve(Collection<Module> modules) {
        Map<Module, ResolutionResult.Node> nodes = new LinkedHashMap<>();
        List<Capability> available = new ArrayList<>();
        available.add(environment.apiCapability());
        available.add(environment.jvmCapability());

        List<Module> pending = new ArrayList<>(modules);
        Set<Module> active = new LinkedHashSet<>();

        while (true) {
            List<Module> chosen = new ArrayList<>();
            for (Module module : pending) {
                if (contractMismatch(module.descriptor()) == null
                        && jvmMismatch(module.descriptor()) == null
                        && unmetRequirements(module.descriptor(), available, active).isEmpty()) {
                    chosen.add(module);
                }
            }
            if (chosen.isEmpty()) {
                break;
            }
            for (Module module : chosen) {
                pending.remove(module);
                active.add(module);
                available.addAll(module.descriptor().provides());
                nodes.put(module, new ResolutionResult.Node(ResolutionStatus.RESOLVED, null));
            }
        }

        markRejected(nodes, pending, active, available);
        return new ResolutionResult(nodes);
    }

    private void markRejected(Map<Module, ResolutionResult.Node> nodes,
                              List<Module> pending,
                              Set<Module> active,
                              List<Capability> available) {
        Set<Module> classified = new LinkedHashSet<>();
        for (Module module : pending) {
            String contractReason = contractMismatch(module.descriptor());
            if (contractReason != null) {
                nodes.put(module, new ResolutionResult.Node(
                        ResolutionStatus.CONTRACT_MISMATCH, module.descriptor().name() + ": " + contractReason));
                classified.add(module);
                continue;
            }
            String jvmReason = jvmMismatch(module.descriptor());
            if (jvmReason != null) {
                nodes.put(module, new ResolutionResult.Node(
                        ResolutionStatus.JVM_MISMATCH, module.descriptor().name() + ": " + jvmReason));
                classified.add(module);
            }
        }

        List<Module> unresolved = new ArrayList<>();
        for (Module module : pending) {
            if (!classified.contains(module)) {
                unresolved.add(module);
            }
        }
        List<List<Module>> cycles = stronglyConnected(unresolved, active, available);
        Set<Module> inCycle = new LinkedHashSet<>();
        for (List<Module> component : cycles) {
            inCycle.addAll(component);
        }
        for (Module module : unresolved) {
            if (inCycle.contains(module)) {
                nodes.put(module, new ResolutionResult.Node(
                        ResolutionStatus.CYCLE, module.descriptor().name()));
                continue;
            }
            List<String> unmet = unmetRequirements(module.descriptor(), available, active);
            nodes.put(module, new ResolutionResult.Node(
                    ResolutionStatus.UNSATISFIED_REQUIREMENT,
                    module.descriptor().name() + " requires " + unmet));
        }
    }

    /**
     * Tarjan SCC over <em>unresolved</em> modules using the latent edges among
     * them: A is linked to B when B provides a capability A still requires.
     * A singleton with a self-edge is a cycle.
     */
    private List<List<Module>> stronglyConnected(List<Module> unresolved,
                                                 Set<Module> active,
                                                 List<Capability> available) {
        List<List<Module>> components = new ArrayList<>();
        Map<Module, Integer> index = new HashMap<>();
        Map<Module, Integer> lowLink = new HashMap<>();
        Deque<Module> stack = new ArrayDeque<>();
        Set<Module> onStack = new LinkedHashSet<>();
        int[] counter = {0};

        for (Module module : unresolved) {
            if (!index.containsKey(module)) {
                strongConnect(module, unresolved, active, available, index, lowLink,
                        stack, onStack, counter, components);
            }
        }
        return components;
    }

    private boolean latent(Module from, Module to,
                           Set<Module> active,
                           List<Capability> available) {
        for (Requirement requirement : from.descriptor().requires()) {
            boolean met = available.stream()
                    .anyMatch(cap -> cap.name().equals(requirement.name())
                            && requirement.satisfiedBy(cap.version()));
            if (met) {
                continue;
            }
            for (Module module : active) {
                if (module.descriptor().provides().stream()
                        .anyMatch(cap -> cap.name().equals(requirement.name())
                                && requirement.satisfiedBy(cap.version()))) {
                    met = true;
                    break;
                }
            }
            if (!met) {
                boolean provides = to.descriptor().provides().stream()
                        .anyMatch(cap -> cap.name().equals(requirement.name())
                                && requirement.satisfiedBy(cap.version()));
                if (provides) {
                    return true;
                }
            }
        }
        return false;
    }

    private void strongConnect(Module module,
                               List<Module> unresolved,
                               Set<Module> active,
                               List<Capability> available,
                               Map<Module, Integer> index,
                               Map<Module, Integer> lowLink,
                               Deque<Module> stack,
                               Set<Module> onStack,
                               int[] counter,
                               List<List<Module>> components) {
        index.put(module, counter[0]);
        lowLink.put(module, counter[0]);
        counter[0]++;
        stack.push(module);
        onStack.add(module);

        for (Module other : unresolved) {
            if (other == module) {
                continue;
            }
            Set<Module> edges = new LinkedHashSet<>();
            if (latent(module, other, active, available)) {
                edges.add(other);
            }
            for (Module edge : edges) {
                if (!index.containsKey(edge)) {
                    strongConnect(edge, unresolved, active, available, index, lowLink,
                            stack, onStack, counter, components);
                    lowLink.put(module, Math.min(lowLink.get(module), lowLink.get(edge)));
                } else if (onStack.contains(edge)) {
                    lowLink.put(module, Math.min(lowLink.get(module), index.get(edge)));
                }
            }
        }

        if (lowLink.get(module).equals(index.get(module))) {
            List<Module> component = new ArrayList<>();
            Module member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (member != module);
            if (component.size() > 1) {
                components.add(component);
            }
        }
    }

    private String contractMismatch(ModuleDescriptor descriptor) {
        SemVer host = environment.contractVersion();
        if (descriptor.contractVersion().major() < host.major()) {
            return "compiled against " + descriptor.contractVersion()
                    + " but host contract major is " + host.major();
        }
        if (descriptor.contractVersion().major() > host.major()) {
            return "compiled against a newer contract (" + descriptor.contractVersion()
                    + ") than the host provides (" + host + ")";
        }
        if (descriptor.contractVersion().compareTo(host) > 0) {
            return "requires contract " + descriptor.contractVersion()
                    + " but host provides " + host;
        }
        return null;
    }

    private String jvmMismatch(ModuleDescriptor descriptor) {
        int running = environment.jvmVersion().major();
        if (descriptor.jvmMin() > 0 && running < descriptor.jvmMin()) {
            return "needs JVM >= " + descriptor.jvmMin() + " but running " + running;
        }
        if (descriptor.jvmMax() > 0 && running > descriptor.jvmMax()) {
            return "needs JVM <= " + descriptor.jvmMax() + " but running " + running;
        }
        return null;
    }

    private List<String> unmetRequirements(ModuleDescriptor descriptor,
                                           List<Capability> available,
                                           Set<Module> active) {
        List<String> unmet = new ArrayList<>();
        for (Requirement requirement : descriptor.requires()) {
            boolean met = available.stream()
                    .anyMatch(cap -> cap.name().equals(requirement.name())
                            && requirement.satisfiedBy(cap.version()));
            for (Module module : active) {
                if (module.descriptor().provides().stream()
                        .anyMatch(cap -> cap.name().equals(requirement.name())
                                && requirement.satisfiedBy(cap.version()))) {
                    met = true;
                    break;
                }
            }
            if (!met) {
                unmet.add(requirement.toString());
            }
        }
        return unmet;
    }
}