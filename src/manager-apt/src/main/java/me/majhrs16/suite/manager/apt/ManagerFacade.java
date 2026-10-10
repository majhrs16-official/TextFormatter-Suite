package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.RepositoryConfig;
import me.majhrs16.suite.manager.spi.RepoConfigParser;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.api.spi.PluginLogger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * New Module Manager facade - replaces ModuleLifecycle with APT/dpkg architecture.
 * Integrates with existing TxF runtime via SuiteBootstrap.
 */
public final class ManagerFacade {

    private final Path managerDir;
    private final PluginLogger logger;
    private final String platform;
    private final String arch;
    private final String os;
    private final int javaVersion;

    private LocalPackageDatabase database;
    private PackageInstaller installer;
    private RepositoryManager repoManager;
    private DependencyResolver resolver;
    private AptInstaller aptInstaller;
    private List<RepositoryConfig> repositories;
    private boolean initialized = false;

    public ManagerFacade(Path managerDir, PluginLogger logger, String platform, String arch, String os, int javaVersion) {
        this.managerDir = managerDir.toAbsolutePath();
        this.logger = logger;
        this.platform = platform;
        this.arch = arch;
        this.os = os;
        this.javaVersion = javaVersion;
    }

    /**
     * Initializes the manager: loads repo.yml, creates database, loads cached indexes.
     */
    public void initialize() throws IOException {
        if (initialized) return;

        // 1. Load repository configuration
        Path repoYml = managerDir.resolve("repo.yml");
        try {
            repositories = RepoConfigParser.parse(repoYml);
        } catch (RepoConfigParser.ParseException e) {
            throw new IOException("Failed to parse repo.yml: " + e.getMessage(), e);
        }
        logger.info("Loaded " + repositories.size() + " repository configurations");

        // 2. Initialize local package database (dpkg)
        database = new LocalPackageDatabase(managerDir);
        database.initialize();
        logger.info("Local package database initialized: " + database.getAllPackages().size() + " packages installed");

        // 3. Create installer (dpkg)
        Path installRoot = managerDir.getParent(); // Server root (plugins/mods dir)
        installer = new PackageInstaller(database, installRoot, logger);

        // 4. Create repository manager (APT)
        repoManager = new RepositoryManager(database, repositories, logger);
        repoManager.loadCachedIndexes();

        // 5. Create dependency resolver
        resolver = new DependencyResolver(repoManager, database, installer, logger,
                platform, arch, os, javaVersion);

        // 6. Create APT installer
        aptInstaller = new AptInstaller(repoManager, resolver, installer, database, logger);

        initialized = true;
        logger.info("Module Manager initialized (APT/dpkg architecture)");
    }

    /**
     * Updates repository indexes (`/suite repo update`).
     */
    public RepositoryManager.UpdateResult repoUpdate() throws IOException {
        ensureInitialized();
        return repoManager.updateAll();
    }

    /**
     * Installs packages from repositories (`/suite repo install`).
     */
    public AptInstaller.InstallResult repoInstall(Collection<String> packages, String version, String channel, boolean force) {
        ensureInitialized();
        return aptInstaller.install(packages, version, channel, force);
    }

    /**
     * Upgrades all packages (`/suite repo upgrade`).
     */
    public AptInstaller.InstallResult repoUpgrade() {
        ensureInitialized();
        return aptInstaller.upgrade();
    }

    /**
     * Fixes broken dependencies (`/suite repo fix-broken`).
     */
    public AptInstaller.InstallResult repoFixBroken() {
        ensureInitialized();
        return aptInstaller.fixBroken();
    }

    /**
     * Removes a package (`/suite repo remove`).
     */
    public AptInstaller.RemoveResult repoRemove(String name, String version, String channel, boolean autoRemove) {
        ensureInitialized();
        return aptInstaller.remove(name, version, channel, autoRemove);
    }

    /**
     * Installs a local ZIP package (`/suite package install`).
     */
    public List<PackageEntry> packageInstall(Path zipPath, boolean force) throws PackageInstaller.InstallException {
        ensureInitialized();
        return installer.install(zipPath, force);
    }

    /**
     * Lists installed packages (`/suite package list`).
     */
    public List<PackageEntry> packageList() {
        ensureInitialized();
        return database.getAllPackages();
    }

    /**
     * Removes an installed package (`/suite package remove`).
     */
    public void packageRemove(String name, String version, String channel, boolean autoRemove) throws PackageInstaller.InstallException {
        ensureInitialized();
        installer.remove(name, version, channel, autoRemove);
    }

    /**
     * Lists files of a package (`/suite package files`).
     */
    public List<me.majhrs16.suite.manager.spi.FileEntry> packageFiles(String name, String version, String channel) {
        ensureInitialized();
        return installer.listFiles(name, version, channel);
    }

    /**
     * Verifies a package (`/suite package verify`).
     */
    public LocalPackageDatabase.VerificationResult packageVerify(String name, String version, String channel) {
        ensureInitialized();
        return installer.verify(name, version, channel);
    }

    /**
     * Gets available packages from repositories.
     */
    public Map<String, List<RepositoryManager.PackageCandidate>> getAvailablePackages() {
        ensureInitialized();
        return repoManager.getAllAvailablePackages();
    }

    /**
     * Checks for updates for installed packages.
     */
    public List<PackageEntry> checkUpdates() {
        ensureInitialized();
        List<PackageEntry> updates = new ArrayList<>();
        for (PackageEntry installed : database.getAllPackages()) {
            List<RepositoryManager.PackageCandidate> candidates = repoManager.findCandidates(
                    installed.name(), ">=" + installed.version(), installed.channel());
            if (!candidates.isEmpty()) {
                RepositoryManager.PackageCandidate best = candidates.get(0);
                if (Version.parse(best.version()).compareTo(Version.parse(installed.version())) > 0) {
                    updates.add(installed);
                }
            }
        }
        return updates;
    }

    /**
     * Discovers all modules registered via this lifecycle (for SuiteBootstrap).
     */
    public List<me.majhrs16.suite.api.Module> discoverAll(ClassLoader parent) {
        ensureInitialized();
        List<me.majhrs16.suite.api.Module> modules = new ArrayList<>();
        // Discover from kernel (core modules)
        modules.addAll(me.majhrs16.suite.kernel.ModuleLoader.discover(parent));
        // Note: Package-based module discovery not yet implemented in ModuleLoader
        return modules;
    }

    /**
     * Gets the list of currently loaded/installed modules (for compatibility).
     */
    public List<me.majhrs16.suite.manager.ModuleDescriptor> getLoadedModules() {
        ensureInitialized();
        return database.getAllPackages().stream()
                .map(this::toModuleDescriptor)
                .collect(Collectors.toList());
    }

    private me.majhrs16.suite.manager.ModuleDescriptor toModuleDescriptor(PackageEntry entry) {
        return new me.majhrs16.suite.manager.ModuleDescriptor(
                new me.majhrs16.suite.manager.ModuleCoordinate("me.majhrs16", entry.name(), me.majhrs16.suite.api.SemVer.parse(entry.version()), null),
                entry.name(),
                "",
                "TextFormatter Suite Team",
                "https://github.com/majhrs16-official/TextFormatter-Suite",
                "GPL-3.0",
                me.majhrs16.suite.api.SemVer.of(2, 1, 0),
                "17", "21",
                List.of(platform),
                entry.dependencies(),
                Set.of(),
                Set.of(),
                Map.of("channel", entry.channel(), "type", entry.type().name())
        );
    }

    public LocalPackageDatabase getDatabase() { return database; }
    public RepositoryManager getRepoManager() { return repoManager; }
    public DependencyResolver getResolver() { return resolver; }
    public AptInstaller getAptInstaller() { return aptInstaller; }
    public PackageInstaller getInstaller() { return installer; }
    public List<RepositoryConfig> getRepositories() { return repositories; }
    public Path getManagerDir() { return managerDir; }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("ManagerFacade not initialized. Call initialize() first.");
        }
    }
}