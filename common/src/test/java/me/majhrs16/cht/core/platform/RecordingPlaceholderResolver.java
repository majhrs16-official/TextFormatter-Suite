package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.player.Subject;

/**
 * Test double that records the last value passed to {@code resolve}.
 */
public final class RecordingPlaceholderResolver implements PlaceholderResolver {

    private final boolean available;
    private String lastResolved;

    public RecordingPlaceholderResolver(boolean available) {
        this.available = available;
    }

    @Override
    public String resolve(Subject subject, String input) {
        lastResolved = input;
        return "<red>[OWNER]</red>";
    }

    @Override
    public boolean available() {
        return available;
    }

    public String lastResolved() {
        return lastResolved;
    }
}