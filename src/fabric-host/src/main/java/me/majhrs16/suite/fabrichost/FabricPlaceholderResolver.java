package me.majhrs16.suite.fabrichost;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.spi.PlaceholderResolver;

/**
 * Fabric implementation of {@link PlaceholderResolver}.
 * Fabric has no PlaceholderAPI equivalent, so returns input unchanged.
 */
public final class FabricPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String resolve(Actor actor, String input) {
        return input;
    }

    @Override
    public boolean available() {
        return false;
    }
}
