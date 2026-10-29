package me.majhrs16.suite.api.discord;

/**
 * Inbound Discord message translated by an adapter.
 *
 * @param author    display name of the author
 * @param content   plain message content
 * @param channelId Discord channel id
 */
public record DiscordMessage(String author, String content, long channelId) {
}
