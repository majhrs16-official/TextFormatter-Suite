package me.majhrs16.suite.synccommon;

import java.util.Objects;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.spi.MessageDispatch;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.api.spi.UserLanguageStore;

/**
 * Translates platform event data into neutral messages and dispatches them.
 *
 * <p>This class contains no Bukkit or Fabric types, so both synchronizers share
 * exactly the same channel selection, translation gating and fan-out rules. The
 * platform listener is responsible only for claiming/cancelling its native
 * event and for supplying the {@link Actor}.</p>
 */
public class SyncEventProcessor {

    private final MessageDispatch dispatch;
    private final ChannelRegistry channels;
    private final UserLanguageStore languages;
    private final PermissionChecker permissions;
    private final PluginLogger logger;

    public SyncEventProcessor(MessageDispatch dispatch,
                              ChannelRegistry channels,
                              UserLanguageStore languages,
                              PermissionChecker permissions,
                              PluginLogger logger) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.languages = Objects.requireNonNull(languages, "languages");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Emits the sender echo, the broadcast to the other players and, when
     * configured, the console copy of one chat line.
     *
     * @param sender         player who wrote the message
     * @param text           raw chat text
     * @param logToConsole   whether the console also receives the line
     */
    public void chat(Actor sender, String text, boolean logToConsole) {
        Objects.requireNonNull(sender, "sender");
        String channelPath = ChannelSelector.select(
                channels.all(), permission -> permissions.has(sender, permission));
        Channel channel = channels.resolve(channelPath);
        boolean translate = EventRules.shouldTranslate(languages, sender.uuid());

        if (channel.showSender()) {
            dispatch.dispatch(EventMessages.chat(
                    sender, Direction.initiator(),
                    channelPath, text, translate));
        }
        dispatch.dispatch(EventMessages.chat(
                sender, Direction.others(),
                channelPath, text, translate));
        if (logToConsole) {
            dispatch.dispatch(EventMessages.chat(
                    sender, Direction.console(), channelPath, text, translate));
        }
        logger.debug("chat event routed through channel " + channelPath);
    }

    /**
     * Emits a typed event (join, quit, death, advancement) only when its
     * conventional channel is present.
     *
     * @return {@code true} when a message was dispatched
     */
    public boolean typed(MessageType type, String channelName, Actor subject, String content) {
        Objects.requireNonNull(type, "type");
        if (!EventRules.typedEventEnabled(channels, channelName)) {
            return false;
        }
        boolean translate = EventRules.shouldTranslate(
                languages, subject == null ? null : subject.uuid());
        dispatch.dispatch(EventMessages.typed(type, subject, channelName, content, translate));
        return true;
    }
}
