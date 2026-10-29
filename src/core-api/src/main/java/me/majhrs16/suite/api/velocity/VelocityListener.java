package me.majhrs16.suite.api.velocity;

/** Inbound callback used by the neutral Velocity sink. */
public interface VelocityListener {

    /**
     * Called for each payload received on the configured channel.
     *
     * @param payload raw plugin-message bytes
     */
    void onMessage(byte[] payload);
}
