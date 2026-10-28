package me.majhrs16.suite.api.runtime;

import java.util.Objects;

/**
 * Published by the runtime after a module has been deactivated and its
 * services and resources have been released.
 *
 * <p>Consumers use this event to drop cached services owned by the module that
 * just disappeared, which is required for a real hot unload.</p>
 *
 * @param moduleId deactivated module id
 */
public record ModuleDeactivatedEvent(String moduleId) {

    public ModuleDeactivatedEvent {
        Objects.requireNonNull(moduleId, "moduleId");
    }
}
