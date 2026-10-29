package me.majhrs16.suite.runtime;

import java.util.Objects;

import me.majhrs16.suite.api.command.CommandSource;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.runtime.PlatformPorts;

/** Adapter from the platform ports to the neutral command source. */
final class PlatformCommandSource implements CommandSource {

    private final Actor actor;
    private final PlatformPorts ports;

    PlatformCommandSource(Actor actor, PlatformPorts ports) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.ports = Objects.requireNonNull(ports, "ports");
    }

    @Override
    public Actor actor() {
        return actor;
    }

    @Override
    public boolean hasPermission(String permission) {
        return permission == null || ports.hasPermission(actor, permission);
    }

    @Override
    public void reply(String message) {
        ports.send(actor, message);
    }
}
