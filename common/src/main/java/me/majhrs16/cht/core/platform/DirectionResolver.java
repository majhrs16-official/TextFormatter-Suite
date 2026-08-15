package me.majhrs16.cht.core.platform;

import me.majhrs16.cht.core.message.Actor;
import me.majhrs16.cht.core.message.Direction;

import java.util.List;

/**
 * Resolves a {@link Direction} into the concrete recipient {@link Actor}s.
 *
 * <p>The core only knows the abstract audience kinds; the platform knows how
 * to enumerate players by world, distance or permission. This port closes that
 * gap. Implementations must be safe to call from the routing thread.</p>
 */
public interface DirectionResolver {

    /**
     * @param initiator the actor who caused the event (may be {@code null}).
     * @param direction the audience to resolve.
     * @return the resolved recipients; never null (may be empty).
     */
    List<Actor> resolve(Actor initiator, Direction direction);
}