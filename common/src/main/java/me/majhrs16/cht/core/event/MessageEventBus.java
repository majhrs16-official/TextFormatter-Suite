package me.majhrs16.cht.core.event;

import me.majhrs16.cht.core.message.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe registry of {@link MessageListener}s. Fires every message through
 * each registered listener; a cancelled or replaced message is returned to the
 * caller so the router continues with the updated unit.
 */
public final class MessageEventBus {

    private final Map<String, List<MessageListener>> listeners = new ConcurrentHashMap<>();

    public void register(String id, MessageListener listener) {
        listeners.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void unregister(String id, MessageListener listener) {
        List<MessageListener> group = listeners.get(id);
        if (group != null) {
            group.remove(listener);
        }
    }

    /**
     * Fires the event to every registered listener (ordered per registration).
     *
     * @param message the incoming unit.
     * @return the (possibly replaced) message, or {@code null} if it was
     *         cancelled by a listener while processed equals {@code true}.
     */
    public Message fire(Message message) {
        if (message == null) {
            return null;
        }
        MessageEvent event = new MessageEvent(message);
        for (List<MessageListener> group : listeners.values()) {
            for (MessageListener listener : group) {
                listener.onMessage(event);
                if (event.isCancelled()) {
                    return null;
                }
                Message current = event.message();
                if (current != null && current != message) {
                    message = current;
                    event.setMessage(current);
                }
                if (event.isProcessed()) {
                    return message;
                }
            }
        }
        return message;
    }
}