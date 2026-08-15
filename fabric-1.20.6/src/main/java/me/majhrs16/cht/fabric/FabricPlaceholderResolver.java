package me.majhrs16.cht.fabric;

import me.majhrs16.cht.core.platform.PlaceholderResolver;
import me.majhrs16.cht.core.player.Subject;

/**
 * Fabric {@link PlaceholderResolver}. There is no cross-mod placeholder
 * ecosystem equivalent to PlaceholderAPI, so this falls back to identity.
 */
final class FabricPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String resolve(Subject subject, String input) {
        return input;
    }

    @Override
    public boolean available() {
        return false;
    }
}