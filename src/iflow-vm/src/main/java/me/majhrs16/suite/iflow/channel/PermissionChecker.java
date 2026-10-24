package me.majhrs16.suite.iflow.channel;

import me.majhrs16.suite.api.message.Actor;

/**
 * Platform permission hook the router uses to evaluate channel policies.
 *
 * <p>The host (Spigot/Fabric/console harness) plugs in a real implementation;
 * the default used in pure-JVM tests grants everything ({@code ACCEPT}).</p>
 */
@FunctionalInterface
public interface PermissionChecker {

    /** @return whether {@code actor} possesses {@code permission}. */
    boolean has(Actor actor, String permission);

    /** Default that answers {@code true} to everything (bare ACCEPT policy). */
    PermissionChecker ALLOW_ALL = (actor, permission) -> true;
}