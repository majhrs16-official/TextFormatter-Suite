package me.majhrs16.suite.api.discord;

/**
 * Settings needed by a Discord connection adapter.
 *
 * @param token     bot token
 * @param channelId destination channel; {@code 0} means no channel
 */
public record DiscordSettings(String token, long channelId) {

    public DiscordSettings {
        token = token == null ? "" : token.trim();
        if (channelId < 0) {
            channelId = 0;
        }
    }
}
