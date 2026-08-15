package me.majhrs16.cht.core.chat;

import me.majhrs16.cht.core.config.ChatSettings;
import me.majhrs16.cht.core.config.FormatGroups;
import me.majhrs16.cht.core.event.MessageEventBus;
import me.majhrs16.cht.core.language.Language;
import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.ChatMessage;
import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.message.Direction;
import me.majhrs16.cht.core.message.Formats;
import me.majhrs16.cht.core.message.Message;
import me.majhrs16.cht.core.message.MessageType;
import me.majhrs16.cht.core.message.SoundSpec;
import me.majhrs16.cht.core.platform.ChatDisplay;
import me.majhrs16.cht.core.platform.DirectionResolver;
import me.majhrs16.cht.core.platform.PermissionChecker;
import me.majhrs16.cht.core.platform.PluginLogger;
import me.majhrs16.cht.core.platform.Scheduler;
import me.majhrs16.cht.core.player.Channel;
import me.majhrs16.cht.core.player.Subject;
import me.majhrs16.cht.core.rules.RulesEngine;
import me.majhrs16.cht.core.storage.UserStore;
import me.majhrs16.cht.core.template.Template;
import me.majhrs16.cht.core.template.TemplateContext;
import me.majhrs16.cht.core.template.TemplateRenderer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entry point of the routing engine (v4 model).
 *
 * <p><b>Message model.</b> A chat event produces one or more {@link Message}
 * units. There is <em>no</em> implicit from/to pair: each unit carries its own
 * {@link Direction}, resolved at routing time to the concrete recipient list.
 * The message shown back to the initiator and the one broadcast to everyone
 * else are independent units, each with its own format group and each
 * cancellable on its own.</p>
 *
 * <p><b>Pipeline.</b> {@link #dispatch(Message...)} runs every unit through the
 * {@link RulesEngine} (native replacement for ConditionalEvents), then for each
 * surviving message resolves the direction and renders a per-recipient
 * component translated to each recipient's language. Network/translation work
 * happens on the scheduler's async executor; delivery is re-scheduled on the
 * main thread.</p>
 */
public final class ChatRouter {

    private final FormatGroups groups;
    private final TemplateRenderer renderer;
    private final DirectionResolver directions;
    private final UserStore users;
    private final ChatDisplay display;
    private final Scheduler scheduler;
    private final ChatSettings settings;
    private final PermissionChecker permissions;
    private final RulesEngine rules;
    private final PluginLogger logger;
    private final MessageEventBus events;

    public ChatRouter(
            FormatGroups groups,
            TemplateRenderer renderer,
            DirectionResolver directions,
            UserStore users,
            ChatDisplay display,
            Scheduler scheduler,
            ChatSettings settings,
            PermissionChecker permissions,
            RulesEngine rules,
            PluginLogger logger) {
        this(groups, renderer, directions, users, display, scheduler,
            settings, permissions, rules, logger, new MessageEventBus());
    }

    public ChatRouter(
            FormatGroups groups,
            TemplateRenderer renderer,
            DirectionResolver directions,
            UserStore users,
            ChatDisplay display,
            Scheduler scheduler,
            ChatSettings settings,
            PermissionChecker permissions,
            RulesEngine rules,
            PluginLogger logger,
            MessageEventBus events) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.directions = Objects.requireNonNull(directions, "directions");
        this.users = Objects.requireNonNull(users, "users");
        this.display = Objects.requireNonNull(display, "display");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Routes a batch of messages. Safe to call from any thread.
     *
     * @param messages one or more units; each is routed independently.
     */
    public void dispatch(Message... messages) {
        for (Message message : messages) {
            if (message == null || message.isCancelled()) {
                continue;
            }
            Message accepted = events.fire(message);
            if (accepted == null || accepted.isCancelled()) {
                continue;
            }
            scheduler.runAsync(() -> route(accepted));
        }
    }

    /**
     * Compatibility bridge from the legacy model. Kept so adapters and the
     * public API keep compiling during the migration.
     */
    public void dispatch(ChatMessage legacy) {
        if (legacy == null || legacy.isCancelled()) {
            return;
        }
        Direction direction = legacy.target().isPresent()
            ? Direction.specific(Channel.PRIVATE, toActor(legacy.target().get()))
            : Direction.all();
        Message message = Message.builder()
            .type(migrateType(legacy.type()))
            .sender(toActor(legacy.sender()))
            .direction(direction)
            .texts(legacy.content())
            .langSource(legacy.sourceLanguage().orElse(Language.AUTO))
            .translate(legacy.shouldTranslate())
            .build();
        dispatch(message);
    }

    /**
     * @return the event bus handed to external integrations. Registered
     *         listeners run synchronously on the dispatching thread.
     */
    public MessageEventBus events() {
        return events;
    }

    // -- pipeline ------------------------------------------------------------

    private void route(Message incoming) {
        List<Message> accepted = rules.apply(incoming);

        for (Message message : accepted) {
            if (!message.isShown()) {
                continue;
            }
            Message formatted = message.lastFormatPath() == null
                ? applyDefaultGroup(message)
                : message;
            List<Recipient> recipients = toRecipients(formatted,
                directions.resolve(formatted.sender(), formatted.direction()));
            Language source = resolvedSource(formatted);
            for (Recipient recipient : recipients) {
                renderAndDeliver(formatted, source, recipient);
            }
        }
    }

    /**
     * Applies the default format group for the message type when the message
     * was produced without an explicit {@code setFormat} (e.g. legacy bridge).
     * Falls back to {@code type.name().toLowerCase()} as the group path.
     */
    private Message applyDefaultGroup(Message message) {
        String group = defaultGroupPath(message.type());
        if (group == null) {
            return message;
        }
        me.majhrs16.cht.core.config.FormatApplier applier =
            new me.majhrs16.cht.core.config.FormatApplier(groups);
        Message built = applier.apply(message, group).build();
        // Keep original content if the group carries no texts.
        if (built.messages().isEmpty() && !message.messages().isEmpty()) {
            built = built.toBuilder().messages(message.messages()).build();
        }
        return built;
    }

    private static String defaultGroupPath(MessageType type) {
        switch (type) {
            case CHAT:
                return "chat";
            case PRIVATE:
                return "private";
            case MENTION:
                return "mention";
            case JOIN:
                return "join";
            case LEAVE:
                return "leave";
            case DEATH:
                return "death";
            case ADVANCEMENT:
                return "advancement";
            case SIGN:
                return "sign";
            default:
                return "internal";
        }
    }

    private void renderAndDeliver(Message message, Language source, Recipient recipient) {
        Component component = renderMessage(message, source, recipient);
        Component finalComponent = applyToolTip(message, source, recipient, component);
        List<SoundSpec> sounds = resolveSounds(message, recipient);

        scheduler.runOnMainThread(() -> {
            display.send(toSubject(recipient.actor), finalComponent, recipient.channel);
            for (SoundSpec sound : sounds) {
                if (recipient.actor.isPlayer()) {
                    display.playSound(toSubject(recipient.actor), sound);
                }
            }
        });
    }

    /**
     * Renders every (format, text) pair of the message for one recipient.
     * With no explicit formats declared the text is used directly (kept inside
     * a {@code <tr>} span so it is still translated).
     */
    private Component renderMessage(Message message, Language source, Recipient recipient) {
        List<Component> parts = new ArrayList<>();
        Formats formats = message.messages();
        for (int i = 0; i < formats.size(); i++) {
            String template = formats.format(i);
            String text = formats.text(i);
            if (template == null || template.trim().isEmpty()) {
                template = "<tr>" + Formats.PLACEHOLDER + "</tr>";
            }
            TemplateContext context = context(message, source, recipient, text);
            parts.add(renderer.render(Template.of(fixPlaceholder(template)), context));
        }
        if (parts.isEmpty()) {
            return Component.empty();
        }
        return Component.join(JoinConfiguration.separator(Component.newline()), parts);
    }

    private Component applyToolTip(Message message, Language source, Recipient recipient,
            Component component) {
        if (message.toolTips().isEmpty()) {
            return component;
        }
        Formats toolTips = message.toolTips();
        String template = toolTips.format(0);
        TemplateContext context = context(message, source, recipient, message.text());
        Component tooltip = renderer.render(Template.of(fixPlaceholder(template)), context);
        return component.hoverEvent(HoverEvent.showText(tooltip));
    }

    /**
     * Formats use {@code %ct_messages%} as "the text goes here" marker; the
     * TemplateRenderer already substitutes it from the context content. Legacy
     * group syntax ({@code $ct_messages$}) is normalised too.
     */
    private static String fixPlaceholder(String template) {
        return template.replace("$ct_messages$", Formats.PLACEHOLDER)
            .replace("{0}", Formats.PLACEHOLDER);
    }

    private TemplateContext context(Message message, Language source, Recipient recipient,
            String content) {
        return TemplateContext.builder(toSubject(recipient.actor), source, recipient.language)
            .content(content)
            .translate(shouldTranslate(message, recipient))
            .build();
    }

    private boolean shouldTranslate(Message message, Recipient recipient) {
        if (!message.shouldTranslate()) {
            return false;
        }
        if (isSender(recipient.actor, message.sender()) && !settings.translateToSender()) {
            return false;
        }
        return recipient.language != Language.AUTO
            && recipient.language != message.langSource();
    }

    private static boolean isSender(Actor recipient, Actor sender) {
        if (sender == null) {
            return false;
        }
        if (recipient.uuid() != null && sender.uuid() != null) {
            return recipient.uuid().equals(sender.uuid());
        }
        return recipient.name().equalsIgnoreCase(sender.name());
    }

    private List<SoundSpec> resolveSounds(Message message, Recipient recipient) {
        List<SoundSpec> result = new ArrayList<>();
        for (String raw : message.sounds()) {
            SoundSpec sound = parseSound(raw);
            if (sound == null) {
                continue;
            }
            if (!display.hasSound(sound.name())) {
                logger.warn("Unknown sound '%s' in message %s, skipping it",
                    sound.name(), message.id());
                continue;
            }
            result.add(sound);
        }
        return result;
    }

    private static SoundSpec parseSound(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(";");
        String name = parts[0].trim();
        if (name.isEmpty()) {
            return null;
        }
        float volume = parts.length > 1 ? floatOf(parts[1], 1f) : 1f;
        float pitch = parts.length > 2 ? floatOf(parts[2], 1f) : 1f;
        return new SoundSpec(name, volume, pitch);
    }

    private static float floatOf(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // -- recipients & languages ----------------------------------------------

    private List<Recipient> toRecipients(Message message, List<Actor> actors) {
        List<Recipient> result = new ArrayList<>();
        if (actors == null) {
            return result;
        }
        for (Actor actor : actors) {
            result.add(new Recipient(actor,
                channelFor(message, actor),
                languageOf(actor)));
        }
        return result;
    }

    private Channel channelFor(Message message, Actor actor) {
        if (actor.isConsole()) {
            return Channel.CONSOLE;
        }
        Channel directed = message.direction() != null
            ? message.direction().channel() : null;
        return directed != null ? directed : Channel.CHAT;
    }

    private Language languageOf(Actor actor) {
        if (actor.uuid() == null) {
            return settings.defaultLanguage();
        }
        return users.language(actor.uuid()).orElse(settings.defaultLanguage());
    }

    private Language resolvedSource(Message message) {
        Language declared = message.langSource();
        if (declared != Language.AUTO) {
            return declared;
        }
        if (!message.messages().isEmpty()) {
            Language detected = renderer.translation().detect(message.text());
            return detected != Language.AUTO ? detected : settings.defaultLanguage();
        }
        return settings.defaultLanguage();
    }

    // -- migration helpers ---------------------------------------------------

    private static MessageType migrateType(ChatMessageType legacy) {
        switch (legacy) {
            case CHAT:
                return MessageType.CHAT;
            case PRIVATE_CHAT:
                return MessageType.PRIVATE;
            case MENTION:
                return MessageType.MENTION;
            case JOIN:
                return MessageType.JOIN;
            case LEAVE:
                return MessageType.LEAVE;
            case DEATH:
                return MessageType.DEATH;
            case ADVANCEMENT:
                return MessageType.ADVANCEMENT;
            case SIGN:
                return MessageType.SIGN;
            default:
                return MessageType.INTERNAL;
        }
    }

    private static Actor toActor(Subject subject) {
        if (subject == null) {
            return Actor.unknown("UNKNOWN");
        }
        Actor.ActorKind kind = toActorKind(subject.kind());
        return new Actor(subject.uuid(), subject.name(), kind, null, subject.handle());
    }

    private static Actor.ActorKind toActorKind(Subject.SubjectKind kind) {
        switch (kind) {
            case CONSOLE:
                return Actor.ActorKind.CONSOLE;
            case UNKNOWN:
                return Actor.ActorKind.UNKNOWN;
            default:
                return Actor.ActorKind.PLAYER;
        }
    }

    private static Subject toSubject(Actor actor) {
        Subject.SubjectKind kind = actor.isConsole() ? Subject.SubjectKind.CONSOLE
            : actor.isPlayer() ? Subject.SubjectKind.PLAYER : Subject.SubjectKind.UNKNOWN;
        return new Subject(actor.uuid(), actor.name(), kind, actor.handle());
    }

    // -- types ---------------------------------------------------------------

    private static final class Recipient {
        private final Actor actor;
        private final Channel channel;
        private final Language language;

        private Recipient(Actor actor, Channel channel, Language language) {
            this.actor = actor;
            this.channel = channel;
            this.language = language;
        }
    }
}