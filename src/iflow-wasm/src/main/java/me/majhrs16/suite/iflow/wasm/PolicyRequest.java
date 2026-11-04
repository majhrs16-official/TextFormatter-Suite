package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.iflow.PermissionChecker;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.spi.PlaceholderResolver;

import java.util.Objects;
import java.util.function.Function;

/**
 * Entrada neutral de una evaluación de política.
 *
 * <p>El emisor y el recipient son explícitos: la política no infiere el
 * destinatario del mensaje ni de {@code Direction}. El mensaje es inmutable; el
 * journal de {@link PolicySession} produce una copia transformada.</p>
 *
 * @param message          mensaje original de esta entrega
 * @param emitter          actor que originó el evento
 * @param recipient        actor que recibirá esta entrega
 * @param channels         catálogo de canales para validar {@code set_channel}
 * @param permissions      comprobador de permisos de la plataforma
 * @param placeholders     resolvedor de placeholders; {@code null} = identidad
 * @param languageDetector detector de idioma; {@code null} = {@link Language#EN}
 */
public record PolicyRequest(
        Message message,
        Actor emitter,
        Actor recipient,
        ChannelRegistry channels,
        PermissionChecker permissions,
        PlaceholderResolver placeholders,
        Function<String, Language> languageDetector
) {

    public PolicyRequest {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(permissions, "permissions");
    }

    public static PolicyRequest of(Message message,
                                   Actor emitter,
                                   Actor recipient,
                                   ChannelRegistry channels) {
        return new PolicyRequest(
            message, emitter, recipient, channels, PermissionChecker.ALLOW_ALL, null, null);
    }

    public String resolvePlaceholders(Actor actor, String input) {
        if (placeholders == null) {
            return input;
        }
        String resolved = placeholders.resolve(actor, input);
        return resolved == null ? input : resolved;
    }

    public Language detectLanguage(String text) {
        if (languageDetector == null) {
            return Language.EN;
        }
        Language detected = languageDetector.apply(text);
        return detected == null ? Language.EN : detected;
    }
}
