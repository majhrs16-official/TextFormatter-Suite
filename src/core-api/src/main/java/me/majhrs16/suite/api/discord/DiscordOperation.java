package me.majhrs16.suite.api.discord;

/**
 * Neutral native operation executed by a {@link DiscordConnection}.
 *
 * <p>These records carry only strings, integers and reasons. No JDA, HTTP or
 * transport type crosses this boundary: the adapter translates each operation
 * into its own SDK calls, and the sink publishes the matching surface entry.
 * An adapter that cannot execute a published operation must fail explicitly;
 * it must never treat the operation as a no-op.</p>
 */
public sealed interface DiscordOperation {

    /**
     * Creates a text channel in a guild.
     *
     * @param guildId numeric guild id
     * @param name    channel name
     * @param topic   optional topic; {@code null} when absent
     */
    record CreateChannel(String guildId, String name, String topic) implements DiscordOperation {
        public CreateChannel {
            guildId = snowflake("guildId", guildId);
            name = text("name", name);
            topic = optionalText(topic);
        }
    }

    /**
     * Deletes a channel.
     *
     * @param channelId numeric channel id
     * @param reason    optional audit-log reason
     */
    record DeleteChannel(String channelId, String reason) implements DiscordOperation {
        public DeleteChannel {
            channelId = snowflake("channelId", channelId);
            reason = optionalText(reason);
        }
    }

    /**
     * Deletes a message from a channel.
     *
     * @param channelId numeric channel id
     * @param messageId numeric message id
     * @param reason    optional audit-log reason
     */
    record DeleteMessage(String channelId, String messageId, String reason)
            implements DiscordOperation {
        public DeleteMessage {
            channelId = snowflake("channelId", channelId);
            messageId = snowflake("messageId", messageId);
            reason = optionalText(reason);
        }
    }

    /**
     * Edits the text content of a message.
     *
     * @param channelId numeric channel id
     * @param messageId numeric message id
     * @param content   new non-blank text content
     */
    record EditMessage(String channelId, String messageId, String content)
            implements DiscordOperation {
        public EditMessage {
            channelId = snowflake("channelId", channelId);
            messageId = snowflake("messageId", messageId);
            content = text("content", content);
        }
    }

    /**
     * Bans a user from a guild.
     *
     * @param guildId              numeric guild id
     * @param userId               numeric user id
     * @param deleteMessageSeconds messages deleted from the last seconds; 0..604800
     * @param reason               optional audit-log reason
     */
    record BanMember(String guildId, String userId, int deleteMessageSeconds, String reason)
            implements DiscordOperation {
        public BanMember {
            guildId = snowflake("guildId", guildId);
            userId = snowflake("userId", userId);
            if (deleteMessageSeconds < 0 || deleteMessageSeconds > 604_800) {
                throw new IllegalArgumentException(
                        "deleteMessageSeconds must be between 0 and 604800");
            }
            reason = optionalText(reason);
        }
    }

    /**
     * Assigns a role to a guild member.
     *
     * @param guildId numeric guild id
     * @param userId  numeric user id
     * @param roleId  numeric role id
     * @param reason  optional audit-log reason
     */
    record AssignRole(String guildId, String userId, String roleId, String reason)
            implements DiscordOperation {
        public AssignRole {
            guildId = snowflake("guildId", guildId);
            userId = snowflake("userId", userId);
            roleId = snowflake("roleId", roleId);
            reason = optionalText(reason);
        }
    }

    private static String snowflake(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' must not be blank");
        }
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                    "'" + name + "' must be a numeric Discord id: '" + value + "'");
        }
        if (value.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException("'" + name + "' must be a positive Discord id");
        }
        return value;
    }

    private static String text(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' must not be blank");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
