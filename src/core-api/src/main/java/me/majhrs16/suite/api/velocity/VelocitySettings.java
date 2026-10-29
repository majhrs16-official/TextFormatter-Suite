package me.majhrs16.suite.api.velocity;

/**
 * Channel names used by the suite Velocity transport.
 *
 * @param channel    Velocity plugin-message channel
 * @param subchannel suite subchannel written inside the payload
 */
public record VelocitySettings(String channel, String subchannel) {

    public static final String DEFAULT_CHANNEL = "textformatter:velocity";
    public static final String DEFAULT_SUBCHANNEL = "textformatter";

    public VelocitySettings {
        channel = channel == null || channel.isBlank() ? DEFAULT_CHANNEL : channel.trim();
        subchannel = subchannel == null || subchannel.isBlank()
            ? DEFAULT_SUBCHANNEL : subchannel.trim();
    }

    public static VelocitySettings defaults() {
        return new VelocitySettings(DEFAULT_CHANNEL, DEFAULT_SUBCHANNEL);
    }
}
