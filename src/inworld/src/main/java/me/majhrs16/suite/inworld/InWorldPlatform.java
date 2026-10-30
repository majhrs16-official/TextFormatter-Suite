package me.majhrs16.suite.inworld;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;

import java.util.List;

/**
 * Platform side of the neutral in-world domain.
 *
 * <p>The host adapter supplies players, formatting and click actions. The
 * {@link InWorldHandler} owns channel resolution, message construction and
 * caches; this port keeps Bukkit/Paper/Fabric out of the package.</p>
 */
public interface InWorldPlatform {

    /** Renders {@code message} with {@code content} as the template content. */
    String format(Message message, String content);

    /** @return players currently in {@code world}. */
    List<Actor> playersInWorld(String world);

    /** @return players within {@code radius} of the given block position. */
    List<Actor> playersInRadius(String world, double x, double y, double z,
                                double radius);

    void openUrl(Actor actor, String url);

    void runCommand(Actor actor, String command);

    void suggestCommand(Actor actor, String command);

    void changePage(Actor actor, String page);

    void copyToClipboard(Actor actor, String text);
}
