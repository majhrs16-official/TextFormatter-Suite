package me.majhrs16.cht.core.storage;

import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.player.Subject;

import java.util.Optional;
import java.util.UUID;

/**
 * Port over the persistent per-user state.
 *
 * <p>The engine only knows about the two things every feature needs: the
 * player's language preference and the optional Discord account binding. The
 * concrete backends (YAML, SQLite, MySQL) live behind this port.</p>
 */
public interface UserStore {

    /**
     * Reads the language preference of a player.
     *
     * @param uuid player identity, not null.
     * @return the stored language, or empty when unset.
     */
    Optional<Language> language(UUID uuid);

    /**
     * Stores the language preference of a player.
     *
     * @param uuid     player identity.
     * @param language language to store.
     */
    void setLanguage(UUID uuid, Language language);

    /**
     * The Discord account id bound to a player's Minecraft account.
     *
     * @param uuid player identity.
     * @return the discord snowflake id, or empty when not linked.
     */
    Optional<String> discordLink(UUID uuid);

    /**
     * Binds a discord account id to a player.
     *
     * @param uuid       player identity.
     * @param discordId  discord snowflake id.
     */
    void linkDiscord(UUID uuid, String discordId);

    /** Removes any discord binding for the player. */
    void unlinkDiscord(UUID uuid);

    /**
     * Looks up the player bound to a discord account (reverse search).
     *
     * @param discordId discord snowflake id.
     * @return the bound player subject name/uuid, if any.
     */
    Optional<UUID> playerBoundToDiscord(String discordId);

    /** Human-readable backend name for error messages. */
    String type();

    /** Closes the underlying connection/resources. */
    void close();
}