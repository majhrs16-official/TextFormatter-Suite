package me.majhrs16.cht.core.event;

import me.majhrs16.cht.core.message.Message;

/**
 * Listener for {@link MessageEvent}s. Called synchronously on the thread that
 * dispatched the message.
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * Handles one message before the routing pipeline processes it.
     *
     * @param event the cancellable message event.
     */
    void onMessage(MessageEvent event);
}