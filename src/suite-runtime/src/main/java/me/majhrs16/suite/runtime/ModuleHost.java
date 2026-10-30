package me.majhrs16.suite.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.Module;
import me.majhrs16.suite.api.ModuleDescriptor;
import me.majhrs16.suite.api.Requirement;
import me.majhrs16.suite.api.runtime.ModuleActivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleActivator;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.kernel.Environment;
import me.majhrs16.suite.kernel.ModuleGraph;
import me.majhrs16.suite.kernel.ResolutionResult;

/**
 * Loads module JARs from disk and manages their activation lifecycle.
 *
 * <p>Discovery happens under {@code <library>/packages}, one isolated
 * {@link ModuleClassLoader} per JAR, activation in dependency order, and real
 * deactivation that closes the classloader and every module-owned resource.
 * Runtime states are persisted separately from {@code library/status} when a
 * {@link RuntimeStateStore} is supplied.</p>
 */
public final class ModuleHost implements AutoCloseable {

    private final Path packagesDir;
    private final Path configRoot;
    private final PlatformInfo platform;
    private final PluginLogger logger;
    private final Environment environment;
    private final RuntimeServiceRegistry services;
    private final RuntimeEventBus events;
    private final RuntimeScheduler scheduler;
    private final RuntimeCommandRegistry commands;
    private final RuntimeConfigRegistry configs;
    private final RuntimeStateStore stateStore;
    private final Map<String, LoadedModule> active = new LinkedHashMap<>();
    private final Map<String, ModuleState> states = new LinkedHashMap<>();

    /**
     * @param libraryRoot directory that contains {@code packages/}
     * @param configRoot  directory under which each module gets its config dir
     * @param platform    neutral platform information exposed to modules
     * @param logger      runtime logger
     */
    public ModuleHost(Path libraryRoot, Path configRoot, PlatformInfo platform, PluginLogger logger) {
        this(libraryRoot, configRoot, platform, logger, null);
    }

    /**
     * @param libraryRoot directory that contains {@code packages/}
     * @param configRoot  directory under which each module gets its config dir
     * @param platform    neutral platform information exposed to modules
     * @param logger      runtime logger
     * @param stateStore  optional transient store for runtime states
     */
    public ModuleHost(Path libraryRoot, Path configRoot, PlatformInfo platform, PluginLogger logger,
                      RuntimeStateStore stateStore) {
        this(libraryRoot, configRoot, platform, logger, stateStore,
                new RuntimeCommandRegistry(CommandRoot.suite(), logger),
                new RuntimeConfigRegistry());
    }

    /**
     * @param libraryRoot directory that contains {@code packages/}
     * @param configRoot  directory under which each module gets its config dir
     * @param platform    neutral platform information exposed to modules
     * @param logger      runtime logger
     * @param stateStore  optional transient store for runtime states
     * @param commands    runtime-wide command registry
     * @param configs     runtime-wide configuration registry
     */
    public ModuleHost(Path libraryRoot, Path configRoot, PlatformInfo platform, PluginLogger logger,
                      RuntimeStateStore stateStore, RuntimeCommandRegistry commands,
                      RuntimeConfigRegistry configs) {
        this.packagesDir = Objects.requireNonNull(libraryRoot, "libraryRoot").resolve("packages");
        this.configRoot = Objects.requireNonNull(configRoot, "configRoot");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.stateStore = stateStore;
        this.commands = Objects.requireNonNull(commands, "commands");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.environment = Environment.current();
        this.services = new RuntimeServiceRegistry();
        this.events = new RuntimeEventBus(logger);
        this.scheduler = new RuntimeScheduler(logger);
    }

    /** @return the runtime-wide service registry, used by the suite runtime. */
    public ServiceRegistry services() {
        return services;
    }

    /** @return the runtime-wide event bus. */
    public RuntimeEventBus events() {
        return events;
    }

    /** @return the runtime-wide scheduler. */
    public RuntimeScheduler scheduler() {
        return scheduler;
    }

    /** @return the runtime-wide command registry. */
    public RuntimeCommandRegistry commands() {
        return commands;
    }

    /** @return the runtime-wide configuration registry. */
    public RuntimeConfigRegistry configs() {
        return configs;
    }

    /** @return {@code <library>/packages}. */
    public Path packagesDir() {
        return packagesDir;
    }

    /**
     * Scans {@code <library>/packages/*.jar}, resolves their descriptors and
     * activates them in dependency order.
     *
     * <p>A JAR that cannot be read or whose descriptor cannot be resolved is
     * reported as {@link ModuleState#FAILED}; it does not prevent the rest from
     * activating.</p>
     *
     * @return one result per discovered JAR, in activation order
     */
    public List<ActivationResult> loadInstalled() {
        if (!Files.isDirectory(packagesDir)) {
            return List.of();
        }

        List<PreparedModule> prepared = new ArrayList<>();
        List<ActivationResult> failures = new ArrayList<>();

        List<Path> jars;
        try (Stream<Path> stream = Files.list(packagesDir)) {
            jars = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of(ActivationResult.failed("packages", "Cannot list " + packagesDir, e));
        }

        for (Path jar : jars) {
            try {
                prepared.add(prepare(jar));
            } catch (Throwable failure) {
                String name = jar.getFileName().toString();
                setState(name, ModuleState.FAILED);
                failures.add(ActivationResult.failed(name, "Cannot load " + name + ": " + failure.getMessage(), failure));
            }
        }

        List<Module> candidates = new ArrayList<>(prepared);
        ResolutionResult resolution = new ModuleGraph(environment).resolve(candidates);
        Map<Module, PreparedModule> byModule = new IdentityHashMap<>();
        for (PreparedModule module : prepared) {
            byModule.put(module, module);
        }

        List<ActivationResult> results = new ArrayList<>();
        for (Module module : resolution.resolved()) {
            PreparedModule preparedModule = byModule.get(module);
            results.add(activatePrepared(preparedModule));
        }
        for (Module module : resolution.rejected()) {
            PreparedModule preparedModule = byModule.get(module);
            String reason = resolution.reasonOf(module);
            setState(preparedModule.id, ModuleState.FAILED);
            closeQuietly(preparedModule, reason);
            results.add(ActivationResult.failed(preparedModule.id, reason, null));
        }
        results.addAll(failures);
        return List.copyOf(results);
    }

    /**
     * Activates a single module JAR.
     *
     * @param jar module JAR
     * @return the activation outcome
     */
    public ActivationResult activate(Path jar) {
        return activate(jar, List.of());
    }

    /**
     * Activates a single module JAR with shared dependency JARs visible to its
     * classloader.
     *
     * <p>Dependency JARs are searched after the module JAR, so the module can
     * ship its own classes and still link against shared libraries installed by
     * the manager.</p>
     *
     * @param jar             module JAR
     * @param dependencyJars  shared dependency JARs
     * @return the activation outcome
     */
    public ActivationResult activate(Path jar, List<Path> dependencyJars) {
        Objects.requireNonNull(dependencyJars, "dependencyJars");
        PreparedModule prepared;
        try {
            prepared = prepare(jar, dependencyJars);
        } catch (Throwable failure) {
            String name = jar.getFileName().toString();
            setState(name, ModuleState.FAILED);
            return ActivationResult.failed(name, "Cannot load " + name + ": " + failure.getMessage(), failure);
        }
        return activatePrepared(prepared);
    }

    /**
     * Deactivates an active module, closes its classloader and releases every
     * resource it owns.
     *
     * @param moduleId module id
     * @return the deactivation outcome
     */
    public DeactivationResult deactivate(String moduleId) {
        LoadedModule module = active.remove(moduleId);
        if (module == null) {
            return new DeactivationResult(moduleId, ModuleState.FAILED,
                    "Module is not active: " + moduleId, null);
        }

        Throwable failure = null;
        try {
            module.activator.stop();
        } catch (Throwable stopFailure) {
            failure = stopFailure;
        }
        try {
            module.context.close();
        } catch (Throwable closeFailure) {
            failure = failure == null ? closeFailure : failure;
        }
        try {
            module.classLoader.close();
        } catch (IOException closeFailure) {
            failure = failure == null ? closeFailure : failure;
        }

        if (failure != null) {
            setState(moduleId, ModuleState.RESTART_REQUIRED);
            events.publish(new ModuleDeactivatedEvent(moduleId));
            String message = "Module " + moduleId + " did not release cleanly: " + failure.getMessage();
            logger.error(message, failure);
            return DeactivationResult.restartRequired(moduleId, message, failure);
        }

        setState(moduleId, ModuleState.INSTALLED);
        events.publish(new ModuleDeactivatedEvent(moduleId));
        logger.info("Module deactivated: " + moduleId);
        return DeactivationResult.deactivated(moduleId);
    }

    /**
     * @param moduleId module id
     * @return the last known state, if the module was seen by this host
     */
    public Optional<ModuleState> state(String moduleId) {
        return Optional.ofNullable(states.get(moduleId));
    }

    /** @return an immutable snapshot of the known module states. */
    public Map<String, ModuleState> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    /** @return the ids of currently active modules. */
    public List<String> activeModules() {
        return List.copyOf(active.keySet());
    }

    @Override
    public void close() {
        for (String moduleId : new ArrayList<>(active.keySet())) {
            deactivate(moduleId);
        }
        events.close();
        scheduler.close();
    }

    private void setState(String moduleId, ModuleState state) {
        states.put(moduleId, state);
        if (stateStore != null) {
            stateStore.save(states);
        }
    }

    private PreparedModule prepare(Path jar) throws Exception {
        return prepare(jar, List.of());
    }

    private PreparedModule prepare(Path jar, List<Path> dependencyJars) throws Exception {
        ModuleClassLoader classLoader = new ModuleClassLoader(
                jar, dependencyJars, ModuleActivator.class.getClassLoader());
        try {
            List<ModuleActivator> activators = loadActivators(jar, classLoader);
            if (activators.isEmpty()) {
                throw new IllegalStateException(
                        "No META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator in " + jar);
            }
            if (activators.size() > 1) {
                throw new IllegalStateException(
                        "Multiple ModuleActivator implementations in " + jar + ": " + activators.size());
            }

            ModuleActivator activator = activators.get(0);
            ModuleDescriptor descriptor = activator.descriptor();
            if (descriptor == null) {
                throw new IllegalStateException("ModuleActivator.descriptor() returned null in " + jar);
            }

            String moduleId = descriptor.name();
            Path configDir = configRoot.resolve(moduleId);
            Files.createDirectories(configDir);
            PluginLogger moduleLogger = new PrefixedLogger(logger, moduleId);
            DefaultModuleContext context = new DefaultModuleContext(
                    moduleId, configDir, moduleLogger, services, events, scheduler,
                    commands, configs, platform);
            return new PreparedModule(moduleId, jar, descriptor, activator, classLoader, context);
        } catch (Throwable failure) {
            try {
                classLoader.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    /**
     * Reads the activator declarations from the module JAR itself.
     *
     * <p>{@link java.util.ServiceLoader} also sees the host classpath, so a
     * feature JAR present on the runtime classpath would be mistaken for a
     * module activator. The module JAR is the only authority here.</p>
     */
    private static List<ModuleActivator> loadActivators(Path jar, ModuleClassLoader classLoader)
            throws ReflectiveOperationException, IOException {
        String service = "META-INF/services/" + ModuleActivator.class.getName();
        List<String> names = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(service);
            if (entry != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        jarFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        if (comment >= 0) {
                            line = line.substring(0, comment);
                        }
                        line = line.trim();
                        if (!line.isEmpty()) {
                            names.add(line);
                        }
                    }
                }
            }
        }

        List<ModuleActivator> activators = new ArrayList<>(names.size());
        for (String name : names) {
            Class<?> type = Class.forName(name, true, classLoader);
            if (!ModuleActivator.class.isAssignableFrom(type)) {
                throw new IllegalStateException(
                        name + " is not a " + ModuleActivator.class.getName() + " in " + jar);
            }
            activators.add((ModuleActivator) type.getDeclaredConstructor().newInstance());
        }
        return activators;
    }

    private ActivationResult activatePrepared(PreparedModule prepared) {
        if (active.containsKey(prepared.id)) {
            String message = "Module already active: " + prepared.id;
            closeQuietly(prepared, message);
            return ActivationResult.failed(prepared.id, message, null);
        }

        String unsatisfied = unsatisfiedRequirement(prepared.descriptor);
        if (unsatisfied != null) {
            closeQuietly(prepared, unsatisfied);
            setState(prepared.id, ModuleState.FAILED);
            return ActivationResult.failed(prepared.id, unsatisfied, null);
        }

        try {
            prepared.activator.start(prepared.context);
        } catch (Throwable failure) {
            closeQuietly(prepared, failure.getMessage());
            setState(prepared.id, ModuleState.FAILED);
            logger.error("Module activation failed: " + prepared.id + " (" + failure.getMessage() + ")", failure);
            return ActivationResult.failed(prepared.id,
                    "Module activation failed: " + failure.getMessage(), failure);
        }

        active.put(prepared.id, new LoadedModule(
                prepared.id, prepared.jar, prepared.descriptor, prepared.activator,
                prepared.classLoader, prepared.context));
        setState(prepared.id, ModuleState.ACTIVE);
        events.publish(new ModuleActivatedEvent(prepared.id, prepared.descriptor));
        logger.info("Module activated: " + prepared.id + " " + prepared.descriptor.version());
        return ActivationResult.activated(prepared.id);
    }

    private String unsatisfiedRequirement(ModuleDescriptor descriptor) {
        List<Capability> available = new ArrayList<>();
        available.add(new Capability("api", environment.contractVersion()));
        available.add(new Capability("jvm", environment.jvmVersion()));
        for (LoadedModule module : active.values()) {
            available.addAll(module.descriptor.provides());
        }

        for (Requirement requirement : descriptor.requires()) {
            boolean satisfied = false;
            for (Capability capability : available) {
                if (capability.name().equals(requirement.name())
                        && requirement.satisfiedBy(capability.version())) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                return "Unsatisfied requirement in " + descriptor.name()
                        + ": " + requirement.name() + "@" + requirement.min();
            }
        }
        return null;
    }

    private void closeQuietly(PreparedModule prepared, String reason) {
        try {
            prepared.context.close();
        } catch (RuntimeException closeFailure) {
            logger.error("Failed to close context for " + prepared.id + " (" + reason + ")", closeFailure);
        }
        try {
            prepared.classLoader.close();
        } catch (IOException closeFailure) {
            logger.error("Failed to close classloader for " + prepared.id + " (" + reason + ")", closeFailure);
        }
    }

    private static final class PreparedModule implements Module {

        private final String id;
        private final Path jar;
        private final ModuleDescriptor descriptor;
        private final ModuleActivator activator;
        private final ModuleClassLoader classLoader;
        private final DefaultModuleContext context;

        private PreparedModule(String id, Path jar, ModuleDescriptor descriptor,
                               ModuleActivator activator, ModuleClassLoader classLoader,
                               DefaultModuleContext context) {
            this.id = id;
            this.jar = jar;
            this.descriptor = descriptor;
            this.activator = activator;
            this.classLoader = classLoader;
            this.context = context;
        }

        @Override
        public ModuleDescriptor descriptor() {
            return descriptor;
        }
    }

    private record LoadedModule(String id, Path jar, ModuleDescriptor descriptor,
                                ModuleActivator activator, ModuleClassLoader classLoader,
                                ModuleContext context) {
    }

    private static final class PrefixedLogger implements PluginLogger {

        private final PluginLogger delegate;
        private final String prefix;

        private PrefixedLogger(PluginLogger delegate, String moduleId) {
            this.delegate = delegate;
            this.prefix = "[" + moduleId + "] ";
        }

        @Override public void info(String message, Object... args) { delegate.info(prefix + message, args); }
        @Override public void warn(String message, Object... args) { delegate.warn(prefix + message, args); }
        @Override public void error(String message, Object... args) { delegate.error(prefix + message, args); }
        @Override public void error(String message, Throwable throwable) { delegate.error(prefix + message, throwable); }
        @Override public void debug(String message, Object... args) { delegate.debug(prefix + message, args); }
    }
}
