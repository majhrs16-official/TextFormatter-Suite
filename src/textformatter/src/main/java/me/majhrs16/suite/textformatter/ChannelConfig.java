package me.majhrs16.suite.textformatter;

import me.majhrs16.suite.api.channel.Channel;
import me.majhrs16.suite.api.channel.ChannelRegistry;
import me.majhrs16.suite.api.message.Formats;
import me.majhrs16.suite.api.message.Language;
import me.majhrs16.suite.api.message.SoundSpec;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Carga la configuración de canales desde {@code config/textformatter/channels/*.yml}.
 * <p>
 * Este componente es propiedad del módulo {@code textformatter} y reemplaza
 * a {@code ConfigLoader.loadChannels()} del host, siguiendo el principio de ownership
 * de configuración por módulo.
 * </p>
 */
public final class ChannelConfig {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private ChannelConfig() {
    }

    /**
     * Resultado de la carga de configuración de canales.
     */
    public static final class LoadResult<T> {
        private final T config;
        private final List<String> errors;

        public LoadResult(T config, List<String> errors) {
            this.config = config;
            this.errors = List.copyOf(errors);
        }

        public T config() {
            return config;
        }

        public List<String> errors() {
            return errors;
        }

        public boolean isValid() {
            return errors.isEmpty();
        }
    }

    /**
     * Carga el registro de canales desde {@code dir/config/textformatter/channels/}.
     * <p>
     * Se mantienen dos rutas heredadas ({@code textformatter/channels/} y
     * {@code channels/}) para no romper instalaciones previas durante la migración
     * al layout de {@code storage.md}.
     * </p>
     *
     * @param dir directorio base del suite (ej. plugin data folder)
     * @return LoadResult con ChannelRegistry y errores
     */
    public static LoadResult<ChannelRegistry> load(Path dir) {
        return loadChannels(resolveChannelsDir(dir));
    }

    /** @return el primer directorio de canales existente, o la ruta canónica. */
    public static Path resolveChannelsDir(Path dir) {
        Path canonical = dir.resolve("config").resolve("textformatter").resolve("channels");
        if (Files.isDirectory(canonical)) {
            return canonical;
        }
        Path intermediate = dir.resolve("textformatter").resolve("channels");
        if (Files.isDirectory(intermediate)) {
            return intermediate;
        }
        Path legacy = dir.resolve("channels");
        if (Files.isDirectory(legacy)) {
            return legacy;
        }
        return canonical;
    }

    /**
     * Carga el registro de canales desde un directorio específico.
     * Método package-private para tests y migración gradual.
     */
    static LoadResult<ChannelRegistry> loadChannels(Path channelsDir) {
        ChannelRegistry.Builder builder = ChannelRegistry.builder();
        var errors = new ArrayList<String>();

        if (!Files.isDirectory(channelsDir)) {
            errors.add("directorio de canales no encontrado: " + channelsDir + "; registro vacío");
            return new LoadResult<>(builder.build(), errors);
        }

        try (var stream = Files.list(channelsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml"))
                .forEach(p -> {
                    try {
                        Channel channel = readChannel(p);
                        builder.register(channel);
                    } catch (IOException | RuntimeException e) {
                        errors.add("Error cargando canal " + p.getFileName() + ": " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            errors.add("Error listando directorio de canales: " + e.getMessage());
        }

        return new LoadResult<>(builder.build(), errors);
    }

    private static Channel readChannel(Path file) throws IOException {
        Object root = YAML.load(Files.readString(file));
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Canal YAML inválido: raíz no es un mapa");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;

        String configuredName = str(map.get("name"), null);
        String name = configuredName == null || configuredName.isBlank()
            ? fileName(file)
            : configuredName.trim();
        // null/vacío = ACCEPT en Channel; una cadena en blanco haría fallar todo chequeo.
        String permission = permissionOf(map.get("permission"));
        String sendPermission = permissionOf(map.get("send-permission"));
        String receivePermission = permissionOf(map.get("receive-permission"));
        Channel.Type type = typeOf(map.get("type"));

        boolean showSender = bool(map.get("show-sender"), true);
        int rateLimit = intOf(map.get("rate-limit-per-second"), 0);
        String langSource = str(map.get("lang-source"), "auto");
        String langTarget = str(map.get("lang-target"), "auto");

        // Las plantillas de canal viven en el array de formatos, no en los textos:
        // Formats.format(i) es lo que DefaultTextFormatter consume.
        Formats messages = readFormats(map.get("messages"));
        Formats tooltips = readFormats(map.get("tooltips"));

        // Cargar sounds
        List<SoundSpec> sounds = new ArrayList<>();
        Object soundsObj = map.get("sounds");
        if (soundsObj instanceof java.util.List) {
            for (Object o : (java.util.List<?>) soundsObj) {
                if (o instanceof String) {
                    sounds.add(new SoundSpec((String) o, 1.0f, 1.0f));
                } else if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> soundMap = (Map<String, Object>) o;
                    String soundName = str(soundMap.get("name"), "");
                    if (!soundName.isBlank()) {
                        sounds.add(new SoundSpec(soundName,
                            floatOf(soundMap.get("volume"), 1.0f),
                            floatOf(soundMap.get("pitch"), 1.0f)));
                    }
                }
            }
        }

        Channel.Builder builder = Channel.builder(name)
            .permission(permission)
            .sendPermission(sendPermission)
            .receivePermission(receivePermission)
            .type(type)
            .showSender(showSender)
            .rateLimitPerSecond(rateLimit)
            .langSource(Language.of(langSource).orElse(Language.AUTO))
            .langTarget(Language.of(langTarget).orElse(Language.AUTO))
            .messages(messages)
            .tooltips(tooltips)
            .sounds(sounds);

        return builder.build();
    }

    private static Formats readFormats(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return Formats.empty();
        }
        List<String> templates = new ArrayList<>();
        for (Object o : list) {
            if (o != null) templates.add(String.valueOf(o));
        }
        return templates.isEmpty()
            ? Formats.empty()
            : new Formats(new String[0], templates.toArray(new String[0]));
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static Channel.Type typeOf(Object o) {
        String value = str(o, null);
        if (value == null || value.isBlank()) {
            return Channel.Type.CHAT;
        }
        try {
            return Channel.Type.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Channel.Type.CHAT;
        }
    }

    /** Permiso ausente o en blanco equivale a ACCEPT ({@code null}). */
    private static String permissionOf(Object o) {
        String value = str(o, null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static float floatOf(Object o, float def) {
        if (o instanceof Number n) return n.floatValue();
        if (o instanceof String s) {
            try {
                return Float.parseFloat(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static String fileName(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}