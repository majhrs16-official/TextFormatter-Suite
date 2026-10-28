package me.majhrs16.suite.api.runtime;

import java.util.Objects;

import me.majhrs16.suite.api.ModuleDescriptor;

/**
 * Published by the runtime after a module has been activated and its services
 * are visible in the {@link ServiceRegistry}.
 *
 * <p>Consumers use this event to discover services that appear after they were
 * activated, without importing the provider module.</p>
 *
 * @param moduleId   activated module id
 * @param descriptor activated module descriptor
 */
public record ModuleActivatedEvent(String moduleId, ModuleDescriptor descriptor) {

    public ModuleActivatedEvent {
        Objects.requireNonNull(moduleId, "moduleId");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
