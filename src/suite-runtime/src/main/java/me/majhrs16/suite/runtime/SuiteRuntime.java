package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.config.ConfigRegistry;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.apt.ManagerFacade;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;

/**
 * Neutral composition root of the suite.
 *
 * <p>Owns the package manager, the module host and the package-to-runtime
 * bridge. Adapters construct it with their data directory and a
 * {@link PlatformPorts} implementation, then call {@link #start()}. No feature
 * type appears here: features are packages activated through
 * {@link ModuleHost}.</p>
 *
 * <p>Layout derived from the adapter data directory:</p>
 * <pre>
 *   &lt;data&gt;/manager/library/packages   installed module JARs
 *   &lt;data&gt;/manager/library/status     installation database
 *   &lt;data&gt;/manager/config/&lt;module&gt;     module configuration
 *   &lt;data&gt;/manager/runtime            transient runtime state
 * </pre>
 */
public final class SuiteRuntime implements AutoCloseable {

    private final Path dataDir;
    private final Path managerDir;
    private final Path libraryRoot;
    private final Path configRoot;
    private final Path runtimeDir;
    private final PlatformPorts ports;
    private final PluginLogger logger;

    private ManagerFacade manager;
    private ModuleHost moduleHost;
    private PackageRuntime packageRuntime;
    private RuntimeStateStore stateStore;
    private RuntimeCommandRegistry commands;
    private RuntimeConfigRegistry configs;
    private PlatformPorts.Registration commandRegistration;
    private final Map<Class<?>, Object> pendingPlatformServices = new LinkedHashMap<>();
    private final List<ServiceRegistry.Registration> platformRegistrations =
        new ArrayList<>();
    private boolean started;

    /**
     * @param dataDir adapter data directory (plugin data folder / mod config
     *                directory)
     * @param ports   platform ports implemented by the adapter
     * @param logger  runtime logger
     */
    public SuiteRuntime(Path dataDir, PlatformPorts ports, PluginLogger logger) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.managerDir = this.dataDir.resolve("manager");
        this.libraryRoot = managerDir.resolve("library");
        this.configRoot = managerDir.resolve("config");
        this.runtimeDir = managerDir.resolve("runtime");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Registers an adapter-provided platform service before modules activate.
     *
     * <p>Packages resolve these ports from the shared {@link ServiceRegistry}
     * during activation. Registration after {@link #start()} is rejected
     * because modules have already run their {@code start()} hooks.</p>
     *
     * @param type    service contract
     * @param service adapter implementation
     * @param <T>     service type
     */
    public synchronized <T> void registerPlatformService(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        if (started) {
            throw new IllegalStateException(
                "register platform services before start()");
        }
        pendingPlatformServices.put(type, service);
    }

    /**
     * Initializes the package manager, creates the module host and activates
     * every module already present in {@code library/packages}.
     *
     * @throws IOException if the manager cannot be initialized
     */
    public synchronized void start() throws IOException {
        if (started) {
            return;
        }

        manager = new ManagerFacade(
                managerDir,
                logger,
                ports.info().name(),
                ports.info().arch(),
                ports.info().os(),
                ports.info().javaMajor());
        manager.initialize();

        stateStore = new RuntimeStateStore(runtimeDir, logger);
        commands = new RuntimeCommandRegistry(CommandRoot.suite(), logger);
        configs = new RuntimeConfigRegistry();
        moduleHost = new ModuleHost(
                libraryRoot, configRoot, ports.info(), logger, stateStore, commands, configs);
        platformRegistrations.add(moduleHost.services().register(
            MessageDispatch.class, ports::deliver));
        registerPendingPlatformServices();
        packageRuntime = new PackageRuntime(manager, moduleHost, logger);
        SuiteCommands.register(commands, this);

        List<PackageEntry> installed = manager.packageList();
        List<ActivationResult> results = installed.isEmpty()
                ? moduleHost.loadInstalled()
                : packageRuntime.activate(installed);
        for (ActivationResult result : results) {
            if (!result.isSuccess()) {
                logger.warn("Module " + result.moduleId() + " was not activated at startup: "
                        + result.message());
            }
        }
        started = true;
    }

    private void registerPendingPlatformServices() {
        for (Map.Entry<Class<?>, Object> entry : pendingPlatformServices.entrySet()) {
            platformRegistrations.add(registerTyped(
                entry.getKey(), entry.getValue()));
        }
    }

    private <T> ServiceRegistry.Registration registerTyped(Class<T> type, Object service) {
        return moduleHost.services().register(type, type.cast(service));
    }

    /**
     * Deactivates every active module and releases runtime resources. Safe to
     * call more than once.
     */
    public synchronized void stop() {
        if (commandRegistration != null) {
            commandRegistration.close();
            commandRegistration = null;
        }
        for (ServiceRegistry.Registration registration : platformRegistrations) {
            registration.close();
        }
        platformRegistrations.clear();
        if (moduleHost != null) {
            moduleHost.close();
        }
        moduleHost = null;
        packageRuntime = null;
        stateStore = null;
        commands = null;
        configs = null;
        manager = null;
        started = false;
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Registers the neutral {@code suite} command through the platform ports.
     *
     * @return registration handle
     */
    public synchronized PlatformPorts.Registration registerCommands() {
        ensureStarted();
        if (commandRegistration == null) {
            PlatformPorts.Registration delegate =
                    ports.registerCommand(new RootCommand(commands, ports));
            commandRegistration = new PlatformPorts.Registration() {
                @Override
                public void close() {
                    try {
                        delegate.close();
                    } finally {
                        synchronized (SuiteRuntime.this) {
                            if (commandRegistration == this) {
                                commandRegistration = null;
                            }
                        }
                    }
                }
            };
        }
        return commandRegistration;
    }

    /**
     * Installs a local distribution ZIP and activates its modules.
     *
     * @param zipPath distribution ZIP
     * @param force   force conflicting packages
     * @return activation results
     * @throws PackageInstaller.InstallException if installation fails
     */
    public List<ActivationResult> installLocal(Path zipPath, boolean force)
            throws PackageInstaller.InstallException {
        ensureStarted();
        return packageRuntime.installLocal(zipPath, force);
    }

    /**
     * Installs packages from configured repositories and activates the new
     * modules.
     *
     * @param packages package names
     * @param version  version constraint
     * @param channel  release channel
     * @param force    force conflicting packages
     * @return manager result
     */
    public AptInstaller.InstallResult installFromRepositories(List<String> packages,
                                                              String version,
                                                              String channel,
                                                              boolean force) {
        ensureStarted();
        return packageRuntime.installFromRepositories(packages, version, channel, force);
    }

    /**
     * Upgrades every installed package.
     *
     * @return manager result
     */
    public AptInstaller.InstallResult upgrade() {
        ensureStarted();
        return packageRuntime.upgrade();
    }

    /**
     * Deactivates and removes an installed package.
     *
     * @param name       package name
     * @param version    exact version or {@code any}
     * @param channel    release channel
     * @param autoRemove also remove unused dependencies
     * @throws PackageInstaller.InstallException if removal fails
     */
    public void removeLocal(String name, String version, String channel, boolean autoRemove)
            throws PackageInstaller.InstallException {
        ensureStarted();
        packageRuntime.removeLocal(name, version, channel, autoRemove);
    }

    /**
     * Deactivates and removes a package through the repository manager.
     *
     * @param name       package name
     * @param version    exact version or {@code any}
     * @param channel    release channel
     * @param autoRemove also remove unused dependencies
     * @return manager result
     */
    public AptInstaller.RemoveResult removeFromRepositories(String name, String version,
                                                            String channel, boolean autoRemove) {
        ensureStarted();
        return packageRuntime.removeFromRepositories(name, version, channel, autoRemove);
    }

    /** @return runtime state per known module. */
    public Map<String, ModuleState> snapshot() {
        ensureStarted();
        return moduleHost.snapshot();
    }

    /** @return ids of currently active modules. */
    public List<String> activeModules() {
        ensureStarted();
        return moduleHost.activeModules();
    }

    /** @return live service registry shared by active modules. */
    public ServiceRegistry services() {
        ensureStarted();
        return moduleHost.services();
    }

    /** @return the runtime-wide command registry. */
    public CommandRegistry commands() {
        ensureStarted();
        return commands;
    }

    /** @return the runtime-wide configuration registry. */
    public ConfigRegistry configs() {
        ensureStarted();
        return configs;
    }

    /** @return event bus shared by active modules. */
    public RuntimeEventBus events() {
        ensureStarted();
        return moduleHost.events();
    }

    /** @return package manager, for command-level package queries. */
    public ManagerFacade manager() {
        ensureStarted();
        return manager;
    }

    /** @return module host, for advanced runtime operations. */
    public ModuleHost modules() {
        ensureStarted();
        return moduleHost;
    }

    /** @return package/runtime bridge. */
    public PackageRuntime packages() {
        ensureStarted();
        return packageRuntime;
    }

    /** @return the platform ports supplied by the adapter. */
    public PlatformPorts platform() {
        return ports;
    }

    public Path dataDir() { return dataDir; }
    public Path managerDir() { return managerDir; }
    public Path libraryRoot() { return libraryRoot; }
    public Path configRoot() { return configRoot; }
    public Path runtimeDir() { return runtimeDir; }

    /** @return {@code true} after a successful {@link #start()}. */
    public boolean isStarted() {
        return started;
    }

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException("SuiteRuntime is not started");
        }
    }
}
