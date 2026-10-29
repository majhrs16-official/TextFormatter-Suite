package me.majhrs16.suite.runtime;

import java.nio.file.Path;
import java.util.Objects;

import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.config.ConfigRegistry;
import me.majhrs16.suite.api.runtime.EventBus;
import me.majhrs16.suite.api.runtime.ModuleContext;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.runtime.ResourceTracker;
import me.majhrs16.suite.api.runtime.Scheduler;
import me.majhrs16.suite.api.runtime.ServiceRegistry;
import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Default module context created for one activation attempt.
 *
 * <p>It wires the module-scoped views of the runtime services and releases
 * everything on {@link #close()}. Closing twice is safe.</p>
 */
final class DefaultModuleContext implements ModuleContext {

    private final String moduleId;
    private final Path configDir;
    private final PluginLogger logger;
    private final ServiceRegistry services;
    private final EventBus events;
    private final Scheduler scheduler;
    private final ResourceTracker resources;
    private final PlatformInfo platform;
    private final CommandRegistry commands;
    private final ConfigRegistry configs;
    private final RuntimeServiceRegistry runtimeServices;
    private final RuntimeEventBus runtimeEvents;
    private final RuntimeScheduler runtimeScheduler;
    private final RuntimeCommandRegistry runtimeCommands;
    private final RuntimeConfigRegistry runtimeConfigs;

    DefaultModuleContext(String moduleId,
                         Path configDir,
                         PluginLogger logger,
                         RuntimeServiceRegistry services,
                         RuntimeEventBus events,
                         RuntimeScheduler scheduler,
                         RuntimeCommandRegistry commands,
                         RuntimeConfigRegistry configs,
                         PlatformInfo platform) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runtimeServices = Objects.requireNonNull(services, "services");
        this.runtimeEvents = Objects.requireNonNull(events, "events");
        this.runtimeScheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtimeCommands = Objects.requireNonNull(commands, "commands");
        this.runtimeConfigs = Objects.requireNonNull(configs, "configs");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.services = services.scope(moduleId);
        this.events = events.scope(moduleId);
        this.scheduler = scheduler.scope(moduleId);
        this.commands = commands.scope(moduleId);
        this.configs = configs.scope(moduleId);
        this.resources = new DefaultResourceTracker(logger);
    }

    @Override public String moduleId() { return moduleId; }
    @Override public Path configDir() { return configDir; }
    @Override public PluginLogger logger() { return logger; }
    @Override public ServiceRegistry services() { return services; }
    @Override public EventBus events() { return events; }
    @Override public Scheduler scheduler() { return scheduler; }
    @Override public ResourceTracker resources() { return resources; }
    @Override public CommandRegistry commands() { return commands; }
    @Override public ConfigRegistry config() { return configs; }
    @Override public PlatformInfo platform() { return platform; }

    @Override
    public void close() {
        resources.releaseAll();
        runtimeScheduler.cancelOwner(moduleId);
        runtimeEvents.removeOwner(moduleId);
        runtimeServices.removeOwner(moduleId);
        runtimeCommands.removeOwner(moduleId);
        runtimeConfigs.removeOwner(moduleId);
    }
}
