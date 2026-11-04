package me.majhrs16.suite.iflow.wasm;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.ColorMode;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Estado de una ejecución de política y journal de transformaciones.
 *
 * <p>El journal es read-your-writes: una escritura se refleja inmediatamente en
 * el mensaje de trabajo y las lecturas posteriores la ven. El mensaje original
 * no se modifica. La decisión es única: la segunda llamada a {@link #decide}
 * devuelve {@link PolicyAbiV3#STATUS_CONFLICT} y no sobrescribe la primera.</p>
 *
 * <p>La sesión no conoce Wasm ni Chicory; {@link PolicyHost} traduce las host
 * functions a estos métodos y {@link WasmPolicyEngine} orquesta la instancia.</p>
 */
public final class PolicySession {

    /** Cap defensivo de logs por ejecución. */
    public static final int MAX_LOGS = 64;

    private final PolicyRequest request;
    private final List<LogEntry> logs = new ArrayList<>();
    private Message message;
    private Decision decision;
    private boolean conflict;

    public PolicySession(PolicyRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        this.message = request.message();
    }

    public PolicyRequest request() {
        return request;
    }

    /** @return mensaje de trabajo con las escrituras aplicadas hasta ahora. */
    public Message message() {
        return message;
    }

    public TlvCodec.TlvValue readProperty(int scope, int property) {
        return switch (scope) {
            case PolicyAbiV3.SCOPE_MESSAGE -> readMessageProperty(property);
            case PolicyAbiV3.SCOPE_EMITTER -> readActorProperty(request.emitter(), property);
            case PolicyAbiV3.SCOPE_RECIPIENT -> readActorProperty(request.recipient(), property);
            case PolicyAbiV3.SCOPE_CHANNEL -> readChannelProperty(property);
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "scope desconocido: " + scope);
        };
    }

    public void writeProperty(int scope, int property, TlvCodec.TlvValue value) {
        Objects.requireNonNull(value, "value");
        if (scope != PolicyAbiV3.SCOPE_MESSAGE) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_DENIED, "scope de solo lectura: " + scope);
        }
        switch (property) {
            case PolicyAbiV3.MESSAGE_CONTENT -> message = message.withText(requireString(value));
            case PolicyAbiV3.MESSAGE_LANG_SOURCE -> message = message.withLangSource(requireLanguage(value));
            case PolicyAbiV3.MESSAGE_LANG_TARGET -> message = message.withLangTarget(requireLanguage(value));
            case PolicyAbiV3.MESSAGE_COLOR_MODE -> message = message.withColorMode(requireColorMode(value));
            case PolicyAbiV3.MESSAGE_FORMAT_PAPI -> message = message.withFormatPapi(requireBool(value));
            case PolicyAbiV3.MESSAGE_TRANSLATE -> message = message.withTranslate(requireBool(value));
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_DENIED, "propiedad de solo lectura: " + property);
        }
    }

    /**
     * Cambia el canal de trabajo validando existencia y permisos.
     *
     * <p>Se comprueba el permiso de envío del emisor y el de recepción del
     * recipient. Un canal inexistente devuelve {@code NOT_FOUND}; un permiso
     * ausente devuelve {@code DENIED}. El cambio es visible para lecturas
     * posteriores de {@code MESSAGE_CHANNEL} y {@code CHANNEL}.</p>
     */
    public void setChannel(String name) {
        if (name == null || name.isBlank()) {
            throw new PolicyException(PolicyAbiV3.STATUS_INVALID_ARGUMENT, "canal vacío");
        }
        Channel channel = request.channels().get(name).orElseThrow(() -> new PolicyException(
            PolicyAbiV3.STATUS_NOT_FOUND, "canal inexistente: " + name));
        requirePermission(request.emitter(), channel.sendPolicy());
        requirePermission(request.recipient(), channel.receivePolicy());
        message = message.withChannel(name);
    }

    public String transform(int kind, String input) {
        Objects.requireNonNull(input, "input");
        return switch (kind) {
            case PolicyAbiV3.TRANSFORM_RESOLVE_PLACEHOLDERS ->
                request.resolvePlaceholders(request.recipient(), input);
            case PolicyAbiV3.TRANSFORM_DETECT_LANGUAGE ->
                request.detectLanguage(input).code();
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "transformación desconocida: " + kind);
        };
    }

    public boolean permissionCheck(String actorName, String node) {
        if (actorName == null || actorName.isBlank() || node == null || node.isBlank()) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "actor o permiso vacío");
        }
        return request.permissions().has(actorFor(actorName), node);
    }

    public TlvCodec.TlvValue channelPolicy(String name, int kind) {
        if (name == null || name.isBlank()) {
            throw new PolicyException(PolicyAbiV3.STATUS_INVALID_ARGUMENT, "canal vacío");
        }
        Channel channel = request.channels().get(name).orElseThrow(() -> new PolicyException(
            PolicyAbiV3.STATUS_NOT_FOUND, "canal inexistente: " + name));
        return switch (kind) {
            case PolicyAbiV3.CHANNEL_POLICY_SEND -> stringOrNull(channel.sendPolicy());
            case PolicyAbiV3.CHANNEL_POLICY_RECEIVE -> stringOrNull(channel.receivePolicy());
            case PolicyAbiV3.CHANNEL_POLICY_RATE -> TlvCodec.i64Value(channel.rateLimitPerSecond());
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "política de canal desconocida: " + kind);
        };
    }

    /**
     * Registra la decisión única de la ejecución.
     *
     * @return {@link PolicyAbiV3#STATUS_OK} la primera vez;
     *         {@link PolicyAbiV3#STATUS_CONFLICT} si ya había una decisión
     */
    public int decide(int actionCode, String target, String reason) {
        PolicyAction action;
        try {
            action = PolicyAction.fromCode(actionCode);
        } catch (IllegalArgumentException e) {
            throw new PolicyException(PolicyAbiV3.STATUS_INVALID_ARGUMENT, e.getMessage(), e);
        }
        if (decision != null) {
            conflict = true;
            return PolicyAbiV3.STATUS_CONFLICT;
        }
        decision = new Decision(action, target == null ? "" : target, reason == null ? "" : reason);
        return PolicyAbiV3.STATUS_OK;
    }

    public int log(int level, String text) {
        if (level < PolicyAbiV3.LOG_DEBUG || level > PolicyAbiV3.LOG_ERROR) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "nivel de log desconocido: " + level);
        }
        if (logs.size() < MAX_LOGS) {
            logs.add(new LogEntry(level, text == null ? "" : text));
        }
        return PolicyAbiV3.STATUS_OK;
    }

    public long nowNanos() {
        return System.nanoTime();
    }

    /**
     * Resultado final. Si no hubo decisión, la política deniega por defecto.
     */
    public PolicyOutcome outcome() {
        if (conflict) {
            return PolicyOutcome.error(message, "decisión conflictiva");
        }
        if (decision == null) {
            return PolicyOutcome.error(message, "sin decisión");
        }
        return new PolicyOutcome(decision.action(), decision.target(), decision.reason(), message);
    }

    public PolicyOutcome failure(String reason) {
        return PolicyOutcome.error(message, reason);
    }

    public List<LogEntry> logs() {
        return List.copyOf(logs);
    }

    private TlvCodec.TlvValue readMessageProperty(int property) {
        return switch (property) {
            case PolicyAbiV3.MESSAGE_CONTENT -> TlvCodec.stringValue(message.text());
            case PolicyAbiV3.MESSAGE_CHANNEL -> stringOrNull(message.channel());
            case PolicyAbiV3.MESSAGE_DIRECTION -> TlvCodec.stringValue(message.direction().kind().name());
            case PolicyAbiV3.MESSAGE_TYPE -> TlvCodec.stringValue(message.type().name());
            case PolicyAbiV3.MESSAGE_LANG_SOURCE -> TlvCodec.stringValue(message.langSource().code());
            case PolicyAbiV3.MESSAGE_LANG_TARGET -> TlvCodec.stringValue(message.langTarget().code());
            case PolicyAbiV3.MESSAGE_COLOR_MODE -> TlvCodec.stringValue(message.colorMode().name());
            case PolicyAbiV3.MESSAGE_FORMAT_PAPI -> TlvCodec.boolValue(message.formatPapi());
            case PolicyAbiV3.MESSAGE_TRANSLATE -> TlvCodec.boolValue(message.shouldTranslate());
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_NOT_FOUND, "propiedad de mensaje desconocida: " + property);
        };
    }

    private TlvCodec.TlvValue readActorProperty(Actor actor, int property) {
        return switch (property) {
            case PolicyAbiV3.ACTOR_NAME -> TlvCodec.stringValue(actor.name());
            case PolicyAbiV3.ACTOR_UUID -> actor.uuid() == null
                ? TlvCodec.nullValue()
                : TlvCodec.stringValue(actor.uuid().toString());
            case PolicyAbiV3.ACTOR_LANGUAGE -> actor.language() == null
                ? TlvCodec.nullValue()
                : TlvCodec.stringValue(actor.language().code());
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_NOT_FOUND, "propiedad de actor desconocida: " + property);
        };
    }

    private TlvCodec.TlvValue readChannelProperty(int property) {
        Channel channel = currentChannel();
        if (channel == null) {
            throw new PolicyException(PolicyAbiV3.STATUS_NOT_FOUND, "mensaje sin canal");
        }
        return switch (property) {
            case PolicyAbiV3.CHANNEL_NAME -> TlvCodec.stringValue(channel.name());
            case PolicyAbiV3.CHANNEL_RATE -> TlvCodec.i64Value(channel.rateLimitPerSecond());
            case PolicyAbiV3.CHANNEL_SEND_POLICY -> stringOrNull(channel.sendPolicy());
            case PolicyAbiV3.CHANNEL_RECEIVE_POLICY -> stringOrNull(channel.receivePolicy());
            default -> throw new PolicyException(
                PolicyAbiV3.STATUS_NOT_FOUND, "propiedad de canal desconocida: " + property);
        };
    }

    private Channel currentChannel() {
        String name = message.channel();
        if (name == null || name.isBlank()) {
            return null;
        }
        return request.channels().get(name).orElse(null);
    }

    private Actor actorFor(String name) {
        if (request.emitter().name().equals(name)) {
            return request.emitter();
        }
        if (request.recipient().name().equals(name)) {
            return request.recipient();
        }
        return Actor.unknown(name);
    }

    private void requirePermission(Actor actor, String permission) {
        if (permission == null || permission.isBlank()) {
            return;
        }
        if (!request.permissions().has(actor, permission)) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_DENIED, "permiso denegado: " + permission);
        }
    }

    private static String requireString(TlvCodec.TlvValue value) {
        if (value.type() != TlvCodec.TYPE_STRING) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "se esperaba string TLV");
        }
        return value.asString();
    }

    private static boolean requireBool(TlvCodec.TlvValue value) {
        if (value.type() != TlvCodec.TYPE_BOOL) {
            throw new PolicyException(
                PolicyAbiV3.STATUS_INVALID_ARGUMENT, "se esperaba bool TLV");
        }
        return value.asBool();
    }

    private static Language requireLanguage(TlvCodec.TlvValue value) {
        String code = requireString(value);
        return Language.of(code).orElseThrow(() -> new PolicyException(
            PolicyAbiV3.STATUS_INVALID_ARGUMENT, "idioma inválido: " + code));
    }

    private static ColorMode requireColorMode(TlvCodec.TlvValue value) {
        String code = requireString(value);
        return ColorMode.of(code).orElseThrow(() -> new PolicyException(
            PolicyAbiV3.STATUS_INVALID_ARGUMENT, "color mode inválido: " + code));
    }

    private static TlvCodec.TlvValue stringOrNull(String value) {
        return value == null ? TlvCodec.nullValue() : TlvCodec.stringValue(value);
    }

    private record Decision(PolicyAction action, String target, String reason) {
    }

    public record LogEntry(int level, String message) {
        public LogEntry {
            Objects.requireNonNull(message, "message");
        }
    }
}
