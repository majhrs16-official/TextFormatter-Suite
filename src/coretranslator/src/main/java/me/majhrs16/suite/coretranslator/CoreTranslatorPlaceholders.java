package me.majhrs16.suite.coretranslator;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.ActorDirectory;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.SyncBus;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Platform-neutral engine behind the {@code %cot_*} PlaceholderAPI expansion.
 *
 * <p>Keeping this class free of Bukkit and PlaceholderAPI types makes the
 * placeholder grammar testable without a server. The Bukkit/PAPI adapter only
 * converts an {@link org.bukkit.OfflinePlayer} into an {@link Actor} and
 * delegates here.</p>
 *
 * <p>Supported commands (case-insensitive, semicolon-separated):</p>
 * <ul>
 *   <li>{@code translate;<from>;<to>;<text>}</li>
 *   <li>{@code lang;<player>}</li>
 *   <li>{@code set;<key>;<value>} / {@code get;<key>}</li>
 *   <li>{@code new}, {@code send;<uuid>}, {@code broadcast;<uuid>},
 *       {@code clone;<uuid>;<key>}</li>
 *   <li>{@code sendDiscord;<uuid>[;<channels>]} (the channel list is accepted
 *       for compatibility; the current SyncBus routes to its configured sink)</li>
 * </ul>
 *
 * <p>The historical SpEL command ({@code var;<uuid>;<expression>}) is not
 * restored: the current message model is immutable and exposing arbitrary SpEL
 * to a placeholder would reintroduce a security hole. Callers that need to
 * inspect a message should use the neutral API.</p>
 */
@Deprecated
public final class CoreTranslatorPlaceholders {

    private static final int MAX_RECENT_MESSAGES = 256;

    private final TranslationPort translations;
    private final UserLanguageStore languages;
    private final ActorDirectory actors;
    private final MessageDispatch dispatch;
    private final SyncBus syncBus;
    private final PluginLogger logger;
    private final Map<UUID, Map<String, String>> variables = new ConcurrentHashMap<>();
    private final RecentMessages recent = new RecentMessages(MAX_RECENT_MESSAGES);

    CoreTranslatorPlaceholders(TranslationPort translations,
                               UserLanguageStore languages,
                               ActorDirectory actors,
                               MessageDispatch dispatch,
                               SyncBus syncBus,
                               PluginLogger logger) {
        this.translations = Objects.requireNonNull(translations, "translations");
        this.languages = languages;
        this.actors = actors;
        this.dispatch = dispatch;
        this.syncBus = syncBus;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Resolves one identifier received after the {@code cot_} prefix.
     *
     * @param actor      player or console issuing the placeholder request
     * @param identifier command and arguments, e.g. {@code translate;en;es;hi}
     * @return the resolved value, or an empty string when the request cannot
     *         be satisfied
     */
    public String resolve(Actor actor, String identifier) {
        if (identifier == null) {
            return "";
        }
        String raw = identifier.trim();
        if (raw.isEmpty()) {
            return "";
        }
        int separator = raw.indexOf(';');
        String command = (separator < 0 ? raw : raw.substring(0, separator))
            .trim()
            .toLowerCase(Locale.ROOT);
        String args = separator < 0 ? "" : raw.substring(separator + 1);
        try {
            return switch (command) {
                case "translate" -> translate(args);
                case "lang" -> lang(actor, args);
                case "set" -> set(actor, args);
                case "get" -> get(actor, args);
                case "new" -> newMessage(actor);
                case "send" -> send(args);
                case "broadcast" -> broadcast(args);
                case "clone" -> cloneMessage(actor, args);
                case "senddiscord" -> sendDiscord(args);
                default -> "";
            };
        } catch (RuntimeException failure) {
            logger.warn("CoreTranslator placeholder '" + command + "' failed: " + failure.getMessage());
            return "";
        }
    }

    /** @return the server console actor, even without an ActorDirectory. */
    public Actor console() {
        return actors != null ? actors.console() : Actor.console("CONSOLE", Language.EN);
    }

    private String translate(String args) {
        String[] parts = split(args, 3);
        if (parts.length < 3) {
            return "";
        }
        Language from = Language.of(parts[0]).orElse(null);
        Language to = Language.of(parts[1]).orElse(null);
        if (from == null || to == null) {
            return "";
        }
        String text = parts[2];
        return text.isEmpty() ? "" : translations.translate(text, from, to);
    }

    private String lang(Actor actor, String args) {
        String requested = args.trim();
        Actor target = actor;
        if (!requested.isEmpty() && actors != null) {
            target = actors.byName(requested).orElse(null);
        }
        if (target == null) {
            return "";
        }
        if (languages != null && target.uuid() != null) {
            Optional<String> stored = languages.languageOf(target.uuid());
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        return target.language() == null ? UserLanguageStore.AUTO : target.language().code();
    }

    private String set(Actor actor, String args) {
        String[] parts = split(args, 2);
        if (parts.length == 0 || parts[0].isBlank()) {
            return "";
        }
        String key = parts[0].trim();
        String value = parts.length > 1 ? parts[1] : "";
        return setVariable(actor, key, value);
    }

    private String setVariable(Actor actor, String key, String value) {
        if (actor == null || actor.uuid() == null || key == null || key.isBlank()) {
            return "";
        }
        Map<String, String> playerVariables =
            variables.computeIfAbsent(actor.uuid(), ignored -> new ConcurrentHashMap<>());
        if (value == null || value.isEmpty() || value.equals("null")) {
            playerVariables.remove(key);
            if (playerVariables.isEmpty()) {
                variables.remove(actor.uuid(), playerVariables);
            }
            return "";
        }
        playerVariables.put(key, value);
        return value;
    }

    private String get(Actor actor, String args) {
        if (actor == null || actor.uuid() == null) {
            return "";
        }
        String key = args.trim();
        if (key.isEmpty()) {
            return "";
        }
        Map<String, String> playerVariables = variables.get(actor.uuid());
        return playerVariables == null ? "" : playerVariables.getOrDefault(key, "");
    }

    private String newMessage(Actor actor) {
        Actor sender = actor == null ? console() : actor;
        Message message = Message.builder()
            .sender(sender)
            .direction(Direction.others())
            .text("")
            .build();
        recent.put(message);
        return message.id().toString();
    }

    private String send(String args) {
        Message message = recent.get(parseUuid(args));
        if (message == null || dispatch == null) {
            return "";
        }
        dispatch.dispatch(message);
        return "ok";
    }

    private String broadcast(String args) {
        Message message = recent.get(parseUuid(args));
        if (message == null || dispatch == null) {
            return "";
        }
        Message broadcast = message.toBuilder()
            .id(UUID.randomUUID())
            .direction(Direction.all())
            .build();
        dispatch.dispatch(broadcast);
        return "ok";
    }

    private String cloneMessage(Actor actor, String args) {
        String[] parts = split(args, 2);
        if (parts.length < 2) {
            return "";
        }
        Message message = recent.get(parseUuid(parts[0]));
        if (message == null) {
            return "";
        }
        Message clone = message.toBuilder().id(UUID.randomUUID()).build();
        recent.put(clone);
        setVariable(actor, parts[1].trim(), clone.id().toString());
        return clone.id().toString();
    }

    private String sendDiscord(String args) {
        String[] parts = split(args, 2);
        Message message = recent.get(parseUuid(parts[0]));
        if (message == null || syncBus == null) {
            return "";
        }
        if (parts.length > 1 && !parts[1].isBlank()) {
            logger.debug("CoreTranslator: explicit Discord channel list is ignored; "
                + "SyncBus uses its configured sink");
        }
        return syncBus.sendTo("discord", message) ? "ok" : "";
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Splits on semicolons, trimming the command fields but preserving the
     * payload's trailing whitespace (historical PAPI behaviour).
     */
    private static String[] split(String args, int limit) {
        String[] parts = args.split(";", limit);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].stripLeading();
            if (i < parts.length - 1) {
                parts[i] = parts[i].trim();
            }
        }
        return parts;
    }

    /** Translation seam; the activator binds it to {@code TranslationService}. */
    @FunctionalInterface
    interface TranslationPort {
        String translate(String text, Language from, Language to);
    }

    private static final class RecentMessages {

        private final int max;
        private final Map<UUID, Message> byId =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Message> eldest) {
                    return size() > max;
                }
            };

        private RecentMessages(int max) {
            this.max = max;
        }

        synchronized void put(Message message) {
            byId.put(message.id(), message);
        }

        synchronized Message get(UUID id) {
            return id == null ? null : byId.get(id);
        }
    }
}
