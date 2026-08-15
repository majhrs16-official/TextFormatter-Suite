package me.majhrs16.cht.core.config;

import me.majhrs16.cht.core.message.ChatMessageType;
import me.majhrs16.cht.core.template.FormatSpec;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of the {@link FormatSpec} used for each {@link ChatMessageType}.
 *
 * <p>Populated by the config loader (from {@code formats.yml}) and consumed by
 * the routing engine. A missing spec simply means the event is not handled --
 * the router skips it gracefully.</p>
 */
public final class FormatCatalog {

    private final Map<ChatMessageType, FormatSpec> specs;

    public FormatCatalog() {
        this.specs = new EnumMap<>(ChatMessageType.class);
    }

    public FormatCatalog(Map<ChatMessageType, FormatSpec> specs) {
        this.specs = new EnumMap<>(specs);
    }

    public void register(ChatMessageType type, FormatSpec spec) {
        specs.put(type, spec);
    }

    public Optional<FormatSpec> spec(ChatMessageType type) {
        return Optional.ofNullable(specs.get(type));
    }

    public Map<ChatMessageType, FormatSpec> all() {
        return Collections.unmodifiableMap(specs);
    }

    public boolean isEmpty() {
        return specs.isEmpty();
    }
}