package me.majhrs16.suite.host;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.RouteDecision;
import me.majhrs16.suite.api.iflow.PolicyTarget;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.SoundSpec;
import me.majhrs16.suite.api.pipeline.PipelineMessage;
import me.majhrs16.suite.api.pipeline.RenderedOutput;
import me.majhrs16.suite.api.render.ChatDelivery;
import me.majhrs16.suite.api.render.ComponentRenderedOutput;
import me.majhrs16.suite.api.spi.PluginLogger;

import net.kyori.adventure.text.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes a rendered unit into the platform without deciding the pipeline
 * order.
 *
 * <p>It is the delivery half of the old {@code MessageDispatcher}: redirects,
 * sleeps, sounds and the concrete {@link ChatDelivery} calls live here, while
 * {@link PipelineEngine} owns stages, recipients and traces.</p>
 */
public final class DeliveryExecutor implements AutoCloseable {

    private static final long SLEEP_POLL_MILLIS = 5_000L;

    private final ChatDelivery delivery;
    private final Optional<ChannelRegistry> channels;
    private final boolean soundEnabled;
    private final PluginLogger logger;
    private final ScheduledExecutorService sleepScheduler;

    public DeliveryExecutor(ChatDelivery delivery,
                            ChannelRegistry channels,
                            boolean soundEnabled,
                            PluginLogger logger) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.channels = Optional.ofNullable(channels);
        this.soundEnabled = soundEnabled;
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sleepScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "msg-delivery-sleep-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Delivers {@code unit} to {@code recipient}.
     *
     * <p>A missing {@link PipelineMessage#rendered()} is a programmer error:
     * the engine always supplies a neutral fallback. It is not converted into
     * a silent success.</p>
     */
    public DeliveryResult deliver(Actor recipient, PipelineMessage unit) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(unit, "unit");
        Component component = componentOf(unit.rendered());
        RouteDecision decision = unit.decision();

        if (decision != null && decision.target() == PolicyTarget.REDIRECT) {
            delivery.deliverConsole(component);
            return DeliveryResult.redirected();
        }
        if (decision != null && !decision.delivered()) {
            return DeliveryResult.silenced(decision.describe());
        }

        Message message = unit.message();
        long sleepMillis = message.sleepMillis();
        if (sleepMillis > 0) {
            sleepScheduler.schedule(() -> deliverNow(recipient, component, message),
                    sleepMillis, TimeUnit.MILLISECONDS);
            return DeliveryResult.delivered();
        }
        deliverNow(recipient, component, message);
        return DeliveryResult.delivered();
    }

    private void deliverNow(Actor recipient, Component component, Message message) {
        try {
            delivery.deliver(recipient, component, message);
            playChannelSounds(message, recipient);
        } catch (RuntimeException failure) {
            logger.error("Error delivering message to " + recipient.name(), failure);
            throw failure;
        }
    }

    private Component componentOf(RenderedOutput rendered) {
        Objects.requireNonNull(rendered, "rendered output");
        if (rendered instanceof ComponentRenderedOutput component) {
            return component.component();
        }
        return Component.text(rendered.plainText());
    }

    private void playChannelSounds(Message message, Actor recipient) {
        if (!soundEnabled) {
            return;
        }
        channels.map(registry -> registry.resolve(message.channel()))
                .ifPresent(channel -> playChannel(channel, recipient));

        for (String soundName : message.sounds()) {
            playSoundIfAvailable(new SoundSpec(soundName, 1.0f, 1.0f), recipient);
        }
    }

    private void playChannel(Channel channel, Actor recipient) {
        for (SoundSpec sound : channel.sounds()) {
            playSoundIfAvailable(sound, recipient);
        }
    }

    private void playSoundIfAvailable(SoundSpec sound, Actor recipient) {
        try {
            if (delivery.hasSound(sound.name())) {
                delivery.playSound(recipient, sound);
            } else {
                logger.debug("sound '" + sound.name() + "' not in registry; skipped");
            }
        } catch (RuntimeException failure) {
            logger.error("sound '" + sound.name() + "' failed", failure);
        }
    }

    @Override
    public void close() {
        sleepScheduler.shutdown();
        try {
            if (!sleepScheduler.awaitTermination(SLEEP_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                sleepScheduler.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            sleepScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
