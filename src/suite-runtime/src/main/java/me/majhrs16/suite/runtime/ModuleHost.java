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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import me.majhrs16.suite.api.runtime.ModuleDeactivatedEvent;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
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
 *
 * <p>Hot upgrades prepare the new JAR in a second classloader before touching
 * the active version. The previous version is stopped and the new one starts.
 * A failed start leaves the installation committed and the old version on
 * disk, but never restores it automatically. This ordering is deliberate: both
 * versions share the same module id, service namespaces, command paths and
 * config namespace, so starting them simultaneously would collide instead of
 * proving isolation.</p>
 */
public final class ModuleHost implements AutoCloseable {

    private static final long THREAD_RELEASE_TIMEOUT_MS = 1_000L;
    private static final AtomicLong THREAD_GROUP_SEQUENCE = new AtomicLong();
    private static final String PACKAGE_STATE_PREFIX = "package:";

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
    private final Map<String, ModuleDescriptor> knownDescriptors = new LinkedHashMap<>();
    private volatile boolean closing;
    private final Map<String, ModuleState> states = new LinkedHashMap<>();
    private final Map<String, ModuleState> packageStates = new LinkedHashMap<>();

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

    /** @return neutral information about the hosting platform. */
    public PlatformInfo platform() {
        return platform;
    }

    /** @return the runtime-wide service registry, used by the suite runtime. */
    public RuntimeServiceRegistry services() {
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
     * Scans {@code <library>/packages/.../*.jar}, resolves their descriptors and
     * activates them in dependency order.
     *
     * <p>A JAR that cannot be read or whose descriptor cannot be resolved is
     * reported as {@link ModuleState#FAILED}; it does not prevent the rest from
     * activating. When two JARs declare the same module id (for example after a
     * crash during an upgrade), only the highest version is activated.</p>
     *
     * @return one result per discovered module, in activation order
     */
    public synchronized List<ActivationResult> loadInstalled() {
        if (!Files.isDirectory(packagesDir)) {
            return List.of();
        }

        List<PreparedModule> prepared = new ArrayList<>();
        List<ActivationResult> failures = new ArrayList<>();

        List<Path> jars;
        try (Stream<Path> stream = Files.walk(packagesDir)) {
            jars = stream
                    .filter(Files::isRegularFile)
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

        List<PreparedModule> selected = selectHighestVersions(prepared);
        knownDescriptors.clear();
        for (PreparedModule module : selected) {
            knownDescriptors.put(module.id, module.descriptor);
        }
        List<Module> candidates = new ArrayList<>(selected);
        ResolutionResult resolution = new ModuleGraph(environment).resolve(candidates);
        Map<Module, PreparedModule> byModule = new IdentityHashMap<>();
        for (PreparedModule module : selected) {
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
            ReleaseReport release = closeQuietly(preparedModule, false, reason);
            ModuleState state = release.isClean() ? ModuleState.FAILED : ModuleState.RESTART_REQUIRED;
            setState(preparedModule.id, state);
            results.add(state == ModuleState.FAILED
                    ? ActivationResult.failed(preparedModule.id, reason, null)
                    : ActivationResult.restartRequired(preparedModule.id, reason, release.failure()));
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
    public synchronized ActivationResult activate(Path jar, List<Path> dependencyJars) {
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
     * Reads the descriptor of a module JAR without starting it.
     *
     * <p>Package upgrades use this to map every JAR in a multi-JAR package to
     * its real module id. The temporary classloader is closed before returning;
     * no module, context or thread group is created.</p>
     *
     * @param jar            module JAR
     * @param dependencyJars shared dependency JARs
     * @return the descriptor declared by the JAR
     * @throws Exception if the JAR cannot be read or declares an invalid
     *                   activator
     */
    public ModuleDescriptor inspect(Path jar, List<Path> dependencyJars) throws Exception {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(dependencyJars, "dependencyJars");
        try (ModuleClassLoader classLoader = new ModuleClassLoader(
                jar, dependencyJars, ModuleActivator.class.getClassLoader())) {
            ModuleActivator activator = loadSingleActivator(jar, classLoader);
            return descriptorOf(activator, jar);
        }
    }

    /**
     * Replaces an active module with a new JAR.
     *
     * <p>The new JAR is prepared in a second classloader first. The active
     * version is then stopped and the new version starts. If the new version
     * fails, the previous version is <strong>not</strong> restored: the file
     * commit already happened and only the runtime reports the failed
     * activation. The old version directory remains on disk for a later,
     * explicit cleanup.</p>
     *
     * @param moduleId       active module id
     * @param newJar         new module JAR
     * @param dependencyJars shared dependency JARs for the new version
     * @return upgrade outcome
     */
    public synchronized UpgradeResult upgrade(String moduleId, Path newJar, List<Path> dependencyJars) {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(newJar, "newJar");
        Objects.requireNonNull(dependencyJars, "dependencyJars");

        LoadedModule previous = active.get(moduleId);
        if (previous == null) {
            ModuleState state = states.getOrDefault(moduleId, ModuleState.FAILED);
            return UpgradeResult.failed(moduleId, null, null, state,
                    "Module is not active: " + moduleId, null);
        }
        String previousVersion = previous.descriptor.version().toString();

        PreparedModule next;
        try {
            next = prepare(newJar, dependencyJars);
        } catch (Throwable failure) {
            return UpgradeResult.failed(moduleId, previousVersion, null, ModuleState.ACTIVE,
                    "Cannot prepare new version of " + moduleId + ": "
                            + failure.getMessage(), failure);
        }
        String newVersion = next.descriptor.version().toString();
        if (!next.id.equals(moduleId)) {
            ReleaseReport release = closeQuietly(next, false, "descriptor id mismatch");
            return UpgradeResult.failed(moduleId, previousVersion, newVersion,
                    release.isClean() ? ModuleState.ACTIVE : ModuleState.RESTART_REQUIRED,
                    "New JAR declares module " + next.id + " instead of " + moduleId,
                    release.failure());
        }

        String unsatisfied = unsatisfiedRequirement(next.descriptor);
        if (unsatisfied != null) {
            ReleaseReport release = closeQuietly(next, false, "unsatisfied requirement");
            return UpgradeResult.failed(moduleId, previousVersion, newVersion,
                    release.isClean() ? ModuleState.ACTIVE : ModuleState.RESTART_REQUIRED,
                    unsatisfied, release.failure());
        }

        active.remove(moduleId);
        ReleaseReport oldRelease = release(previous.runtime(), true, "upgrade");
        events.publish(new ModuleDeactivatedEvent(moduleId));
        if (!oldRelease.isClean()) {
            setState(moduleId, ModuleState.RESTART_REQUIRED);
            closeQuietly(next, false, "upgrade aborted");
            String message = "Module " + moduleId + " did not release before the upgrade: "
                    + oldRelease.failure().getMessage();
            logger.error(message, oldRelease.failure());
            return UpgradeResult.restartRequired(moduleId, previousVersion, newVersion,
                    message, oldRelease.failure());
        }

        markActivationPending(moduleId);
        ActivationResult activation = activatePrepared(next);
        if (activation.isSuccess()) {
            logger.info("Module upgraded: " + moduleId + " " + previousVersion + " -> " + newVersion);
            return UpgradeResult.upgraded(moduleId, previousVersion, newVersion);
        }

        String message = "New version " + newVersion + " of " + moduleId
                + " failed; the previous version was not restored because the installation "
                + "is already committed: " + activation.message();
        logger.warn(message);
        return UpgradeResult.failed(moduleId, previousVersion, newVersion,
                activation.state(), message, activation.cause());
    }

    /**
     * Deactivates an active module, closes its classloader and releases every
     * resource it owns.
     *
     * @param moduleId module id
     * @return the deactivation outcome
     */
    public synchronized DeactivationResult deactivate(String moduleId) {
        LoadedModule module = active.remove(moduleId);
        if (module == null) {
            return new DeactivationResult(moduleId, ModuleState.FAILED,
                    "Module is not active: " + moduleId, null);
        }

        ReleaseReport release = release(module.runtime(), true, "deactivation");
        if (!release.isClean()) {
            setState(moduleId, ModuleState.RESTART_REQUIRED);
            events.publish(new ModuleDeactivatedEvent(moduleId));
            String message = "Module " + moduleId + " did not release cleanly: "
                    + release.failure().getMessage();
            logger.error(message, release.failure());
            return DeactivationResult.restartRequired(moduleId, message, release.failure());
        }

        setState(moduleId, ModuleState.INSTALLED);
        events.publish(new ModuleDeactivatedEvent(moduleId));
        logger.info("Module deactivated: " + moduleId);
        return DeactivationResult.deactivated(moduleId);
    }

    /**
     * Restarts every active module in dependency order without changing the
     * installed set.
     *
     * <p>Modules are stopped in reverse activation order and started again in
     * the original order. If a module does not release cleanly, the reload
     * stops immediately and the affected module is left in
     * {@link ModuleState#RESTART_REQUIRED}; modules stopped before it remain
     * inactive until an explicit restart or activation.</p>
     *
     * @return one activation result per restarted module, or one
     *         restart-required result when a deactivation fails
     */
    public synchronized List<ActivationResult> reloadActiveModules() {
        List<LoadedModule> snapshot = new ArrayList<>(active.values());
        if (snapshot.isEmpty()) {
            return List.of();
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            LoadedModule module = snapshot.get(i);
            DeactivationResult result = deactivate(module.id);
            if (!result.isClean()) {
                return List.of(ActivationResult.restartRequired(
                        module.id, result.message(), result.cause()));
            }
        }

        List<ActivationResult> results = new ArrayList<>(snapshot.size());
        for (LoadedModule module : snapshot) {
            results.add(activate(module.jar, module.dependencyJars));
        }
        return List.copyOf(results);
    }

    /**
     * @param moduleId module id
     * @return the last known state, if the module was seen by this host
     */
    public synchronized Optional<ModuleState> state(String moduleId) {
        return Optional.ofNullable(states.get(moduleId));
    }

    /** @return an immutable snapshot of the known module states. */
    public synchronized Map<String, ModuleState> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    /** @return the ids of currently active modules. */
    public synchronized List<String> activeModules() {
        return List.copyOf(active.keySet());
    }

    /**
     * @return descriptors of every installed module seen by this host, active
     *         or not; used to tell an unknown stage from an inactive one
     */
    public synchronized Map<String, ModuleDescriptor> knownDescriptors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(knownDescriptors));
    }

    /**
     * Persists that activation of a module has started but is not confirmed.
     * A process crash at this point must resume activation instead of assuming
     * the module is active.
     *
     * @param moduleId module id
     */
    public synchronized void markActivationPending(String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        setState(moduleId, ModuleState.ACTIVATION_PENDING);
    }

    /**
     * Persists that activation of a package has started but is not confirmed.
     *
     * @param packageId package name
     */
    public synchronized void markPackageActivationPending(String packageId) {
        markPackageState(packageId, ModuleState.ACTIVATION_PENDING);
    }

    /**
     * Records the activation state of a committed package.
     *
     * @param packageId package name
     * @param state     activation state
     */
    public synchronized void markPackageState(String packageId, ModuleState state) {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(state, "state");
        packageStates.put(packageId, state);
        persistStates();
    }

    /**
     * Removes the runtime activation entry of a package that is no longer
     * installed.
     *
     * @param packageId package name
     */
    public synchronized void clearPackageState(String packageId) {
        Objects.requireNonNull(packageId, "packageId");
        if (packageStates.remove(packageId) != null) {
            persistStates();
        }
    }

    /**
     * @param packageId package name
     * @return the last known activation state of the package
     */
    public synchronized Optional<ModuleState> packageState(String packageId) {
        return Optional.ofNullable(packageStates.get(packageId));
    }

    /**
     * Marks a known module as requiring a restart without pretending it was
     * cleanly unloaded.
     *
     * @param moduleId module id
     * @param reason   human-readable cause
     */
    public synchronized void requireRestart(String moduleId, String reason) {
        Objects.requireNonNull(moduleId, "moduleId");
        if (active.containsKey(moduleId) || states.containsKey(moduleId)) {
            setState(moduleId, ModuleState.RESTART_REQUIRED);
            logger.error("Module " + moduleId + " requires restart: " + reason);
        }
    }

    @Override
    public synchronized void close() {
        closing = true;
        for (String moduleId : new ArrayList<>(active.keySet())) {
            deactivate(moduleId);
        }
        events.close();
        scheduler.close();
    }

    private void setState(String moduleId, ModuleState state) {
        states.put(moduleId, state);
        persistStates();
    }

    private void persistStates() {
        if (stateStore == null) {
            return;
        }
        Map<String, ModuleState> persisted = new LinkedHashMap<>(states);
        for (Map.Entry<String, ModuleState> entry : packageStates.entrySet()) {
            persisted.put(PACKAGE_STATE_PREFIX + entry.getKey(), entry.getValue());
        }
        stateStore.save(persisted);
    }

    private PreparedModule prepare(Path jar) throws Exception {
        return prepare(jar, List.of());
    }

    private PreparedModule prepare(Path jar, List<Path> dependencyJars) throws Exception {
        ModuleClassLoader classLoader = new ModuleClassLoader(
                jar, dependencyJars, ModuleActivator.class.getClassLoader());
        try {
            ModuleActivator activator = loadSingleActivator(jar, classLoader);
            ModuleDescriptor descriptor = descriptorOf(activator, jar);

            String moduleId = descriptor.name();
            knownDescriptors.put(moduleId, descriptor);
            Path configDir = configRoot.resolve(moduleId);
            Files.createDirectories(configDir);
            PluginLogger moduleLogger = new PrefixedLogger(logger, moduleId);
            DefaultModuleContext context = new DefaultModuleContext(
                    moduleId, configDir, moduleLogger, services, events, scheduler,
                    commands, configs, platform, () -> closing);
            ThreadGroup threadGroup = new ThreadGroup(
                    "suite-module-" + moduleId + "-" + descriptor.version()
                            + "-" + THREAD_GROUP_SEQUENCE.incrementAndGet());
            return new PreparedModule(moduleId, jar, descriptor, activator, classLoader,
                    context, threadGroup, List.copyOf(dependencyJars));
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

    private List<PreparedModule> selectHighestVersions(List<PreparedModule> prepared) {
        Map<String, PreparedModule> selected = new LinkedHashMap<>();
        for (PreparedModule candidate : prepared) {
            PreparedModule current = selected.get(candidate.id);
            if (current == null) {
                selected.put(candidate.id, candidate);
                continue;
            }
            if (candidate.descriptor.version().compareTo(current.descriptor.version()) > 0) {
                selected.put(candidate.id, candidate);
                closeQuietly(current, false, "superseded by " + candidate.descriptor.version());
            } else {
                closeQuietly(candidate, false, "superseded by " + current.descriptor.version());
            }
        }
        return new ArrayList<>(selected.values());
    }

    private static ModuleActivator loadSingleActivator(Path jar, ModuleClassLoader classLoader)
            throws ReflectiveOperationException, IOException {
        List<ModuleActivator> activators = loadActivators(jar, classLoader);
        if (activators.isEmpty()) {
            throw new IllegalStateException(
                    "No META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator in " + jar);
        }
        if (activators.size() > 1) {
            throw new IllegalStateException(
                    "Multiple ModuleActivator implementations in " + jar + ": " + activators.size());
        }
        return activators.get(0);
    }

    private static ModuleDescriptor descriptorOf(ModuleActivator activator, Path jar) {
        ModuleDescriptor descriptor = activator.descriptor();
        if (descriptor == null) {
            throw new IllegalStateException("ModuleActivator.descriptor() returned null in " + jar);
        }
        return descriptor;
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
            ReleaseReport release = closeQuietly(prepared, false, message);
            if (!release.isClean()) {
                setState(prepared.id, ModuleState.RESTART_REQUIRED);
                return ActivationResult.restartRequired(prepared.id, message, release.failure());
            }
            return ActivationResult.failed(prepared.id, message, null);
        }

        String unsatisfied = unsatisfiedRequirement(prepared.descriptor);
        if (unsatisfied != null) {
            ReleaseReport release = closeQuietly(prepared, false, unsatisfied);
            ModuleState state = release.isClean() ? ModuleState.FAILED : ModuleState.RESTART_REQUIRED;
            setState(prepared.id, state);
            return state == ModuleState.FAILED
                    ? ActivationResult.failed(prepared.id, unsatisfied, null)
                    : ActivationResult.restartRequired(prepared.id, unsatisfied, release.failure());
        }

        try {
            runInModuleThread(prepared.threadGroup, prepared.classLoader,
                    "suite-module-" + prepared.id + "-start",
                    () -> prepared.activator.start(prepared.context));
        } catch (Throwable failure) {
            ReleaseReport release = release(prepared.runtime(), true, "activation failure");
            ModuleState state = release.isClean() ? ModuleState.FAILED : ModuleState.RESTART_REQUIRED;
            setState(prepared.id, state);
            logger.error("Module activation failed: " + prepared.id
                    + " (" + failure.getMessage() + ")", failure);
            return state == ModuleState.FAILED
                    ? ActivationResult.failed(prepared.id,
                            "Module activation failed: " + failure.getMessage(), failure)
                    : ActivationResult.restartRequired(prepared.id,
                            "Module activation failed and did not release cleanly: "
                                    + release.failure().getMessage(), release.failure());
        }

        active.put(prepared.id, new LoadedModule(
                prepared.id, prepared.jar, prepared.descriptor, prepared.activator,
                prepared.classLoader, prepared.context, prepared.threadGroup,
                prepared.dependencyJars));
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

    private ReleaseReport closeQuietly(PreparedModule prepared, boolean stopActivator, String reason) {
        return release(prepared.runtime(), stopActivator, reason);
    }

    private ReleaseReport release(ModuleRuntime runtime, boolean stopActivator, String reason) {
        Throwable failure = null;
        if (stopActivator) {
            try {
                runInModuleThread(runtime.threadGroup, runtime.classLoader,
                        "suite-module-" + runtime.id + "-stop",
                        runtime.activator::stop);
            } catch (Throwable stopFailure) {
                failure = stopFailure;
            }
        }
        try {
            runtime.context.close();
        } catch (Throwable closeFailure) {
            failure = first(failure, closeFailure);
        }
        failure = first(failure, runtime.context.releaseFailure());
        try {
            runtime.classLoader.close();
        } catch (IOException closeFailure) {
            failure = first(failure, closeFailure);
        }
        if (runtime.context.hasOwnedRegistrations()) {
            failure = first(failure, new IllegalStateException(
                    "module registrations survived context close"));
        }

        List<String> leakedThreads = awaitNoThreads(runtime.threadGroup);
        if (!leakedThreads.isEmpty()) {
            failure = first(failure, new IllegalStateException(
                    "module threads still alive: " + String.join(", ", leakedThreads)));
        }

        if (failure != null) {
            logger.error("Module " + runtime.id + " did not release cleanly ("
                    + reason + "): " + failure.getMessage(), failure);
        }
        return new ReleaseReport(failure, leakedThreads);
    }

    private static Throwable first(Throwable current, Throwable candidate) {
        return current == null ? candidate : current;
    }

    private static void runInModuleThread(ThreadGroup group, ClassLoader classLoader,
                                          String name, ThrowingRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(group, () -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, name);
        thread.setContextClassLoader(classLoader);
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new IllegalStateException(thrown);
        }
    }

    private static List<String> awaitNoThreads(ThreadGroup group) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(THREAD_RELEASE_TIMEOUT_MS);
        List<String> active = activeThreads(group);
        while (!active.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            active = activeThreads(group);
        }
        return active;
    }

    private static List<String> activeThreads(ThreadGroup group) {
        Thread[] threads = new Thread[Math.max(1, group.activeCount() + 8)];
        int count = group.enumerate(threads, true);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (threads[i] != null && threads[i].isAlive()) {
                names.add(threads[i].getName());
            }
        }
        return List.copyOf(names);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record ReleaseReport(Throwable failure, List<String> leakedThreads) {
        private boolean isClean() {
            return failure == null && leakedThreads.isEmpty();
        }
    }

    private record ModuleRuntime(String id, ModuleActivator activator,
                                 ModuleClassLoader classLoader, DefaultModuleContext context,
                                 ThreadGroup threadGroup) {
    }

    private static final class PreparedModule implements Module {

        private final String id;
        private final Path jar;
        private final ModuleDescriptor descriptor;
        private final ModuleActivator activator;
        private final ModuleClassLoader classLoader;
        private final DefaultModuleContext context;
        private final ThreadGroup threadGroup;
        private final List<Path> dependencyJars;

        private PreparedModule(String id, Path jar, ModuleDescriptor descriptor,
                               ModuleActivator activator, ModuleClassLoader classLoader,
                               DefaultModuleContext context, ThreadGroup threadGroup,
                               List<Path> dependencyJars) {
            this.id = id;
            this.jar = jar;
            this.descriptor = descriptor;
            this.activator = activator;
            this.classLoader = classLoader;
            this.context = context;
            this.threadGroup = threadGroup;
            this.dependencyJars = dependencyJars;
        }

        private ModuleRuntime runtime() {
            return new ModuleRuntime(id, activator, classLoader, context, threadGroup);
        }

        @Override
        public ModuleDescriptor descriptor() {
            return descriptor;
        }
    }

    private record LoadedModule(String id, Path jar, ModuleDescriptor descriptor,
                                ModuleActivator activator, ModuleClassLoader classLoader,
                                DefaultModuleContext context, ThreadGroup threadGroup,
                                List<Path> dependencyJars) {

        private ModuleRuntime runtime() {
            return new ModuleRuntime(id, activator, classLoader, context, threadGroup);
        }
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
