package me.majhrs16.suite.host.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates paths.json, paths.js fallback, and model.js defaults for the web editor
 * from ConfigPath enum. Single source of truth for config schema.
 * Run as: ./gradlew :src:host:generateSchema --no-daemon
 */
public final class ConfigSchemaGenerator {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ConfigSchemaGenerator <output-dir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[0]);
        Files.createDirectories(outputDir);

        // Build path hierarchy from ConfigPath enum
        Map<String, PathMeta> paths = buildPathHierarchy();

        // Add editor-specific paths (translators, sync, graph)
        addEditorSpecificPaths(paths);

        // Generate paths.json
        generatePathsJson(outputDir, paths);

        // Generate paths.js fallback
        generatePathsJs(outputDir, paths);

        // Generate model.js defaults
        generateModelJs(outputDir, paths);

        System.out.println("Generated schema files in " + outputDir + " with " + paths.size() + " paths");
    }

    private static Map<String, PathMeta> buildPathHierarchy() {
        Map<String, PathMeta> paths = new LinkedHashMap<>();

        // Build tree from ConfigPath enum using the actual YAML keys
        for (ConfigLoader.ConfigPath cp : ConfigLoader.ConfigPath.values()) {
            String yamlKey = cp.key(); // e.g., "quick-look", "parallel", "enabled", "name", etc.

            // Determine the full path by finding parent enum constants
            String fullPath = buildFullPath(cp, yamlKey);
            if (fullPath == null || fullPath.isEmpty()) continue;

            PathMeta meta = new PathMeta();
            meta.label = toLabel(cp.name());
            meta.desc = "";
            meta.type = inferType(cp);
            meta.defaultValue = getDefaultValue(cp);
            paths.put(fullPath, meta);
        }

        return paths;
    }

    private static String buildFullPath(ConfigLoader.ConfigPath cp, String yamlKey) {
        String enumName = cp.name();

        // Map enum names to their path prefixes
        if (enumName.startsWith("QUICK_LOOK")) {
            return "config.quick-look";
        }
        if (enumName.startsWith("IFLOW_ENGINE_PARALLEL")) {
            return "config.iflow.engine.parallel";
        }
        if (enumName.startsWith("IFLOW_ENGINE") || enumName.startsWith("IFLOW")) {
            return null; // Skip intermediate nodes
        }
        if (enumName.startsWith("SONIDO_ENABLED")) {
            return "config.sonido.enabled";
        }
        if (enumName.startsWith("SONIDO")) {
            return null;
        }
        if (enumName.startsWith("GENERAL_LANGUAGE")) {
            return "config.general.language";
        }
        if (enumName.startsWith("GENERAL")) {
            return null;
        }
        if (enumName.startsWith("CHAT_CLAIM_MODE")) {
            return "config.chat.claim-mode";
        }
        if (enumName.startsWith("CHAT_LOG_TO_CONSOLE")) {
            return "config.chat.log-to-console";
        }
        if (enumName.startsWith("CHAT")) {
            return null;
        }
        if (enumName.startsWith("REPOSITORY_NAME")) {
            return "config.repositories[].name";
        }
        if (enumName.startsWith("REPOSITORY_URL")) {
            return "config.repositories[].url";
        }
        if (enumName.startsWith("REPOSITORY_TYPE")) {
            return "config.repositories[].type";
        }
        if (enumName.startsWith("REPOSITORY_ENABLED")) {
            return "config.repositories[].enabled";
        }
        if (enumName.startsWith("REPOSITORIES")) {
            return null;
        }
        if (enumName.startsWith("CHANNEL_NAME")) {
            return "channels[].name";
        }
        if (enumName.startsWith("CHANNEL_PERMISSION")) {
            return "channels[].permission";
        }
        if (enumName.startsWith("CHANNEL_SEND_PERMISSION")) {
            return "channels[].send-permission";
        }
        if (enumName.startsWith("CHANNEL_RECEIVE_PERMISSION")) {
            return "channels[].receive-permission";
        }
        if (enumName.startsWith("CHANNEL_MESSAGES")) {
            return "channels[].messages";
        }
        if (enumName.startsWith("CHANNEL_TOOLTIPS")) {
            return "channels[].tooltips";
        }
        if (enumName.startsWith("CHANNEL_SHOW_SENDER")) {
            return "channels[].show-sender";
        }
        if (enumName.startsWith("CHANNEL_RATE_LIMIT")) {
            return "channels[].rate-limit-per-second";
        }
        if (enumName.startsWith("CHANNEL_LANG_SOURCE")) {
            return "channels[].lang-source";
        }
        if (enumName.startsWith("CHANNEL_LANG_TARGET")) {
            return "channels[].lang-target";
        }
        if (enumName.startsWith("CHANNEL_TYPE")) {
            return "channels[].type";
        }
        if (enumName.startsWith("CHANNEL_SOUNDS")) {
            return "channels[].sounds";
        }
        if (enumName.startsWith("CHANNEL_LANG") || enumName.startsWith("CHANNEL")) {
            return null;
        }
        if (enumName.startsWith("SOUND_NAME")) {
            return "channels[].sounds[].name";
        }
        if (enumName.startsWith("SOUND_VOLUME")) {
            return "channels[].sounds[].volume";
        }
        if (enumName.startsWith("SOUND_PITCH")) {
            return "channels[].sounds[].pitch";
        }
        if (enumName.startsWith("SOUND")) {
            return null;
        }

        return null;
    }

    private static void addEditorSpecificPaths(Map<String, PathMeta> paths) {
        // Translators
        addPath(paths, "translators.google.active", "Activo (Google)", "", "boolean", true);
        addPath(paths, "translators.google.provider", "Proveedor (Google)", "", "string", "google");
        addPath(paths, "translators.libre.active", "Activo (Libre)", "", "boolean", false);
        addPath(paths, "translators.libre.provider", "Proveedor (Libre)", "", "string", "libre");
        addPath(paths, "translators.libre.base-url", "Base URL (Libre)", "", "string", "");
        addPath(paths, "translators.libre.api-key", "API Key (Libre)", "", "string", "");
        addPath(paths, "translators.libre.pool.max-concurrent", "Max Concurrent (Libre)", "", "integer", 6);

        // Sync
        addPath(paths, "sync.discord.enabled", "Habilitado (Discord)", "", "boolean", false);
        addPath(paths, "sync.discord.token", "Token (Discord)", "", "string", "");
        addPath(paths, "sync.discord.channel", "Channel ID (Discord)", "", "integer", 0);
        addPath(paths, "sync.discord.intents", "Intents (Discord)", "", "array", new String[]{"GUILD_MESSAGES", "MESSAGE_CONTENT"});

        addPath(paths, "sync.telegram.enabled", "Habilitado (Telegram)", "", "boolean", false);
        addPath(paths, "sync.telegram.token", "Token (Telegram)", "", "string", "");
        addPath(paths, "sync.telegram.chat-id", "Chat ID (Telegram)", "", "integer", 0);
        addPath(paths, "sync.telegram.hub", "Hub Channel (Telegram)", "", "string", "");

        addPath(paths, "sync.http.enabled", "Habilitado (HTTP)", "", "boolean", false);
        addPath(paths, "sync.http.webhook-url", "Webhook URL (HTTP)", "", "string", "");
        addPath(paths, "sync.http.inbound-port", "Inbound Port (HTTP)", "", "integer", 0);
        addPath(paths, "sync.http.path", "Path (HTTP)", "", "string", "");

        addPath(paths, "sync.tcp-udp.enabled", "Habilitado (TCP/UDP)", "", "boolean", false);
        addPath(paths, "sync.tcp-udp.protocol", "Protocol (TCP/UDP)", "", "string", "TCP");
        addPath(paths, "sync.tcp-udp.host", "Host (TCP/UDP)", "", "string", "0.0.0.0");
        addPath(paths, "sync.tcp-udp.outbound-port", "Outbound Port (TCP/UDP)", "", "integer", 0);
        addPath(paths, "sync.tcp-udp.inbound-port", "Inbound Port (TCP/UDP)", "", "integer", 0);

        addPath(paths, "sync.velocity.enabled", "Habilitado (Velocity)", "Proxy plugin (F7+)", "boolean", false);
        addPath(paths, "sync.velocity.secret", "Secret (Velocity)", "", "string", "");
        addPath(paths, "sync.velocity.servers", "Servers (Velocity)", "", "array", new String[]{});
        addPath(paths, "sync.velocity.mapping", "Mapping (Velocity)", "", "string", "* → chat.hub");

        // Graph / iFlow
        addPath(paths, "graph.guard.max-steps", "Max Steps", "Límite de pasos en el grafo iFlow", "integer", 512);
        addPath(paths, "graph.filter.dedup-fanout", "Dedup Fan-out", "Deduplicación de fan-out", "boolean", true);
        addPath(paths, "graph.filter.priority", "Priority Strategy", "Estrategia de prioridad", "string", "batch-first");
    }

    private static void addPath(Map<String, PathMeta> paths, String path, String label, String desc, String type, Object defaultValue) {
        PathMeta meta = new PathMeta();
        meta.label = label;
        meta.desc = desc;
        meta.type = type;
        meta.defaultValue = defaultValue;
        paths.put(path, meta);
    }

    private static void generatePathsJson(Path outputDir, Map<String, PathMeta> paths) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", 1);

        Map<String, Map<String, Object>> pathsMap = new LinkedHashMap<>();
        for (Map.Entry<String, PathMeta> entry : paths.entrySet()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", entry.getValue().label);
            meta.put("desc", entry.getValue().desc);
            meta.put("type", entry.getValue().type);
            meta.put("default", entry.getValue().defaultValue);
            pathsMap.put(entry.getKey(), meta);
        }
        output.put("paths", pathsMap);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputDir.resolve("paths.json").toFile())) {
            gson.toJson(output, writer);
        }
    }

    private static void generatePathsJs(Path outputDir, Map<String, PathMeta> paths) throws IOException {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("version", 1);
        Map<String, Map<String, Object>> pathsMap = new LinkedHashMap<>();
        for (Map.Entry<String, PathMeta> entry : paths.entrySet()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", entry.getValue().label);
            meta.put("desc", entry.getValue().desc);
            meta.put("type", entry.getValue().type);
            meta.put("default", entry.getValue().defaultValue);
            pathsMap.put(entry.getKey(), meta);
        }
        fallback.put("paths", pathsMap);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(fallback);

        String js = "/* paths.js — carga y gestiona paths.json (fuente única de verdad para data-bind) */\n" +
                "(function (global) {\n" +
                "  'use strict';\n\n" +
                "  let pathsData = null;\n" +
                "  let pathsLoaded = false;\n" +
                "  let loadPromise = null;\n\n" +
                "  async function loadPaths() {\n" +
                "    if (pathsLoaded) {\n" +
                "      return pathsData;\n" +
                "    }\n" +
                "    if (loadPromise) {\n" +
                "      return loadPromise;\n" +
                "    }\n\n" +
                "    loadPromise = (async () => {\n" +
                "      try {\n" +
                "        const res = await fetch('./paths.json');\n" +
                "        if (!res.ok) {\n" +
                "          throw new Error('Failed to load paths.json: ' + res.status);\n" +
                "        }\n" +
                "        pathsData = await res.json();\n" +
                "        pathsLoaded = true;\n" +
                "        return pathsData;\n" +
                "      } catch (e) {\n" +
                "        console.warn('Failed to load paths.json, using fallback:', e);\n" +
                "        pathsData = getFallbackPaths();\n" +
                "        pathsLoaded = true;\n" +
                "        return pathsData;\n" +
                "      }\n" +
                "    })();\n" +
                "    return loadPromise;\n" +
                "  }\n\n" +
                "  function getFallbackPaths() {\n" +
                "    return " + json + ";\n" +
                "  }\n\n" +
                "  function getPaths() {\n" +
                "    if (!pathsLoaded) {\n" +
                "      console.warn('paths.json not loaded yet, using fallback');\n" +
                "      return getFallbackPaths().paths;\n" +
                "    }\n" +
                "    return pathsData.paths;\n" +
                "  }\n\n" +
                "  function getPathMeta(path) {\n" +
                "    const paths = getPaths();\n" +
                "    return paths[path];\n" +
                "  }\n\n" +
                "  function getAllSwitchPaths() {\n" +
                "    const paths = getPaths();\n" +
                "    return Object.entries(paths)\n" +
                "      .filter(([_, meta]) => meta.type === 'boolean')\n" +
                "      .map(([path, meta]) => ({ path, ...meta }));\n" +
                "  }\n\n" +
                "  global.Suite = global.Suite || {};\n" +
                "  global.Suite.paths = {\n" +
                "    load: loadPaths,\n" +
                "    getPaths: getPaths,\n" +
                "    getPathMeta: getPathMeta,\n" +
                "    getAllSwitchPaths: getAllSwitchPaths,\n" +
                "    getFallbackPaths: getFallbackPaths,\n" +
                "  };\n" +
                "})(window || this);\n";

        Files.writeString(outputDir.resolve("paths.js"), js);
    }

    private static void generateModelJs(Path outputDir, Map<String, PathMeta> paths) throws IOException {
        // Extract config defaults from paths
        Map<String, Object> configDefaults = new LinkedHashMap<>();
        Map<String, Object> translatorDefaults = new LinkedHashMap<>();
        Map<String, Object> syncDefaults = new LinkedHashMap<>();
        Map<String, Object> graphDefaults = new LinkedHashMap<>();

        for (Map.Entry<String, PathMeta> entry : paths.entrySet()) {
            String path = entry.getKey();
            Object defaultValue = entry.getValue().defaultValue;

            if (path.startsWith("config.")) {
                setNested(configDefaults, path.substring(7), defaultValue);
            } else if (path.startsWith("translators.")) {
                setNested(translatorDefaults, path.substring(12), defaultValue);
            } else if (path.startsWith("sync.")) {
                setNested(syncDefaults, path.substring(5), defaultValue);
            } else if (path.startsWith("graph.")) {
                setNested(graphDefaults, path.substring(6), defaultValue);
            }
        }

        String modelJs = "/* model.js — modelo del proyecto (fuente única) + export/import exacto.\n" +
                " * config.yml + channels/*.yml replican el schema del host ConfigLoader.\n" +
                " * rules.yml / translators / sync / manifest = schema v2.2 del editor. */\n" +
                "(function (global) {\n" +
                "  'use strict';\n\n" +
                "  const KNOWN_LANGS = ['auto', 'en', 'es', 'pt', 'de', 'fr', 'it', 'ja', 'ko', 'zh', 'ar', 'ru'];\n" +
                "  const CHANNEL_DEFAULTS = " + toJsObject(getChannelDefaults()) + ";\n\n" +
                "  function clone(v) {\n" +
                "    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));\n" +
                "  }\n" +
                "  function ch(name, over) {\n" +
                "    return Object.assign({ name }, clone(CHANNEL_DEFAULTS), clone(over || {}));\n" +
                "  }\n\n" +
                "  function defaults() {\n" +
                "    return " + toJsObject(buildFullDefaults(configDefaults, translatorDefaults, syncDefaults, graphDefaults)) + ";\n" +
                "  }\n\n" +
                getModelJsRest() +
                "})(window || this);\n";

        Files.writeString(outputDir.resolve("model.js"), modelJs);
    }

    private static Map<String, Object> buildFullDefaults(Map<String, Object> configDefaults,
                                                         Map<String, Object> translatorDefaults,
                                                         Map<String, Object> syncDefaults,
                                                         Map<String, Object> graphDefaults) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("config", configDefaults);
        defaults.put("channels", buildDefaultChannels());
        defaults.put("graph", buildDefaultGraph());
        // Hardcode editor-specific defaults to match original model.js exactly
        defaults.put("translators", buildDefaultTranslators());
        defaults.put("sync", buildDefaultSync());
        defaults.put("perms", buildDefaultPerms());
        defaults.put("extensions", new LinkedHashMap<>());
        defaults.put("extra", new LinkedHashMap<>());
        return defaults;
    }

    private static Map<String, Object> buildDefaultTranslators() {
        Map<String, Object> translators = new LinkedHashMap<>();
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("provider", "google");
        google.put("active", true);
        google.put("pool", Map.of("max-concurrent", 6));
        translators.put("google", google);

        Map<String, Object> libre = new LinkedHashMap<>();
        libre.put("provider", "libre");
        libre.put("active", false);
        libre.put("base-url", "");
        libre.put("api-key", "");
        libre.put("pool", Map.of("max-concurrent", 6));
        translators.put("libre", libre);
        return translators;
    }

    private static Map<String, Object> buildDefaultSync() {
        Map<String, Object> sync = new LinkedHashMap<>();

        Map<String, Object> discord = new LinkedHashMap<>();
        discord.put("enabled", false);
        discord.put("token", "");
        discord.put("channel", 0);
        discord.put("intents", List.of("GUILD_MESSAGES", "MESSAGE_CONTENT"));
        sync.put("discord", discord);

        Map<String, Object> telegram = new LinkedHashMap<>();
        telegram.put("enabled", false);
        telegram.put("token", "");
        telegram.put("chat-id", 0);
        telegram.put("hub", "");
        sync.put("telegram", telegram);

        Map<String, Object> http = new LinkedHashMap<>();
        http.put("enabled", false);
        http.put("webhook-url", "");
        http.put("inbound-port", 0);
        http.put("path", "");
        sync.put("http", http);

        Map<String, Object> tcpudp = new LinkedHashMap<>();
        tcpudp.put("enabled", false);
        tcpudp.put("protocol", "TCP");
        tcpudp.put("host", "0.0.0.0");
        tcpudp.put("outbound-port", 0);
        tcpudp.put("inbound-port", 0);
        sync.put("tcp-udp", tcpudp);

        Map<String, Object> velocity = new LinkedHashMap<>();
        velocity.put("enabled", false);
        velocity.put("secret", "");
        velocity.put("servers", new ArrayList<>());
        velocity.put("mapping", "* → chat.hub");
        sync.put("velocity", velocity);

        return sync;
    }

    private static Map<String, Object> buildDefaultChannels() {
        Map<String, Object> channels = new LinkedHashMap<>();
        // chat.global
        Map<String, Object> cg = new LinkedHashMap<>();
        cg.put("name", "chat.global");
        cg.put("permission", "cht.chat.global");
        cg.put("send-permission", "cht.chat.global.send");
        cg.put("receive-permission", "cht.chat.global.receive");
        cg.put("show-sender", true);
        cg.put("rate-limit-per-second", 0);
        cg.put("lang-source", "auto");
        cg.put("lang-target", "auto");
        cg.put("messages", List.of("&7👉 &f%player_name%&7: %content%", "<green>💬 %content%</green>"));
        cg.put("tooltips", List.of("Hover: %lang_source% → %lang_target%"));
        cg.put("sounds", List.of(Map.of("name", "entity.experience_orb.pickup", "volume", 1.0, "pitch", 1.0)));
        cg.put("type", "chat");
        channels.put("chat.global", cg);

        // chat.hub
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", "chat.hub");
        ch.put("permission", "cht.chat.hub");
        ch.put("send-permission", null);
        ch.put("receive-permission", null);
        ch.put("show-sender", true);
        ch.put("rate-limit-per-second", 0);
        ch.put("lang-source", "auto");
        ch.put("lang-target", "auto");
        ch.put("messages", List.of("<gold>⛨</gold> %content%"));
        ch.put("tooltips", new ArrayList<>());
        ch.put("sounds", new ArrayList<>());
        ch.put("type", "chat");
        channels.put("chat.hub", ch);

        // staff.alert
        Map<String, Object> sa = new LinkedHashMap<>();
        sa.put("name", "staff.alert");
        sa.put("permission", "cht.staff.alert");
        sa.put("send-permission", null);
        sa.put("receive-permission", null);
        sa.put("show-sender", false);
        sa.put("rate-limit-per-second", 0);
        sa.put("lang-source", "auto");
        sa.put("lang-target", "auto");
        sa.put("messages", List.of("<red>⚠ %content%</red>"));
        sa.put("tooltips", new ArrayList<>());
        sa.put("sounds", List.of(Map.of("name", "block.note_block.pling", "volume", 0.8, "pitch", 1.2)));
        sa.put("type", "chat");
        channels.put("staff.alert", sa);

        // vip.chat
        Map<String, Object> vc = new LinkedHashMap<>();
        vc.put("name", "vip.chat");
        vc.put("permission", "cht.vip.chat");
        vc.put("send-permission", null);
        vc.put("receive-permission", null);
        vc.put("show-sender", true);
        vc.put("rate-limit-per-second", 0);
        vc.put("lang-source", "es");
        vc.put("lang-target", "auto");
        vc.put("messages", List.of("<purple>✦ %player_name%: %content%</purple>"));
        vc.put("tooltips", new ArrayList<>());
        vc.put("sounds", new ArrayList<>());
        vc.put("type", "chat");
        channels.put("vip.chat", vc);

        return channels;
    }

    private static Map<String, Object> getChannelDefaults() {
        // Order matters for YAML byte-identical round-trip test
        // Matches original model.js CHANNEL_DEFAULTS (without 'type' - added by overrides)
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("permission", null);
        defaults.put("send-permission", null);
        defaults.put("receive-permission", null);
        defaults.put("show-sender", true);
        defaults.put("rate-limit-per-second", 0);
        defaults.put("lang-source", "auto");
        defaults.put("lang-target", "auto");
        defaults.put("messages", new ArrayList<>());
        defaults.put("tooltips", new ArrayList<>());
        defaults.put("sounds", new ArrayList<>());
        return defaults;
    }

    private static Map<String, Object> buildDefaultPerms() {
        Map<String, Object> perms = new LinkedHashMap<>();
        perms.put("roles", List.of("owner", "admin", "moderator", "guard", "player", "guest"));
        perms.put("cols", List.of("send", "receive", "bypass-rate", "mute", "broadcast", "ctr.*", "admin"));
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("owner", List.of(1, 1, 1, 1, 1, 1, 1));
        matrix.put("admin", List.of(1, 1, 1, 1, 1, 1, 0));
        matrix.put("moderator", List.of(1, 1, 1, 1, 0, 0, 0));
        matrix.put("guard", List.of(1, 1, 0, 1, 0, 0, 0));
        matrix.put("player", List.of(1, 1, 0, 0, 0, 0, 0));
        matrix.put("guest", List.of(0, 1, 0, 0, 0, 0, 0));
        perms.put("matrix", matrix);
        return perms;
    }

    private static Map<String, Object> buildDefaultGraph() {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("guard", Map.of("max-steps", 512));
        graph.put("filter", Map.of("dedup-fanout", true, "priority", "batch-first"));
        graph.put("priority", "batch-first");

        List<Map<String, Object>> nodes = new ArrayList<>();

        Map<String, Object> n1 = new LinkedHashMap<>();
        n1.put("id", "n_chat.global"); n1.put("kind", "input"); n1.put("label", "chat.global"); n1.put("x", 60); n1.put("y", 110); n1.put("w", 150);
        nodes.add(n1);

        Map<String, Object> n2 = new LinkedHashMap<>();
        n2.put("id", "n_cond"); n2.put("kind", "cond"); n2.put("label", "filtro anti-silencio");
        n2.put("matcher", Map.of("channel", "chat.global"));
        n2.put("condition", "'spam' in #msg.texts[0]");
        n2.put("actions", List.of("cancel()", "skipTranslate()"));
        n2.put("target", "DROP"); n2.put("priority", 100); n2.put("x", 340); n2.put("y", 70); n2.put("w", 150);
        nodes.add(n2);

        Map<String, Object> n3 = new LinkedHashMap<>();
        n3.put("id", "n_transform"); n3.put("kind", "transform"); n3.put("label", "reformatear evento");
        Map<String, Object> t1 = new LinkedHashMap<>(); t1.put("op", "rewrite"); t1.put("template", "<green>💬 %content%</green>");
        Map<String, Object> t2 = new LinkedHashMap<>(); t2.put("op", "sounds"); t2.put("add", List.of("entity.experience_orb.pickup")); t2.put("remove", List.of("block.note_block.pling"));
        n3.put("transforms", List.of(t1, t2));
        n3.put("priority", 200); n3.put("x", 660); n3.put("y", 40); n3.put("w", 160);
        nodes.add(n3);

        Map<String, Object> n4 = new LinkedHashMap<>();
        n4.put("id", "n_loop"); n4.put("kind", "loop"); n4.put("label", "bucle re-intento");
        n4.put("loopBack", "n_cond"); n4.put("priority", 300); n4.put("x", 660); n4.put("y", 280); n4.put("w", 140);
        nodes.add(n4);

        Map<String, Object> n5 = new LinkedHashMap<>();
        n5.put("id", "n_sleep"); n5.put("kind", "sleep"); n5.put("label", "delay");
        Map<String, Object> t3 = new LinkedHashMap<>(); t3.put("op", "sleep"); t3.put("millis", 1500);
        n5.put("transforms", List.of(t3));
        n5.put("priority", 400); n5.put("x", 1000); n5.put("y", 60); n5.put("w", 130);
        nodes.add(n5);

        Map<String, Object> n6 = new LinkedHashMap<>();
        n6.put("id", "n_clean"); n6.put("kind", "output"); n6.put("label", "chat.hub"); n6.put("priority", 500); n6.put("x", 1000); n6.put("y", 250); n6.put("w", 130);
        nodes.add(n6);

        Map<String, Object> n7 = new LinkedHashMap<>();
        n7.put("id", "n_redirect"); n7.put("kind", "redirect"); n7.put("label", "redirigir a staff");
        n7.put("target", Map.of("channel", "staff.alert")); n7.put("priority", 600); n7.put("x", 340); n7.put("y", 330); n7.put("w", 150);
        nodes.add(n7);

        Map<String, Object> n8 = new LinkedHashMap<>();
        n8.put("id", "n_channel_redirect"); n8.put("kind", "channel_redirect"); n8.put("label", "cambiar canal");
        n8.put("target", "CHANNEL_REDIRECT"); n8.put("redirectChannel", "staff.alert"); n8.put("priority", 700); n8.put("x", 340); n8.put("y", 400); n8.put("w", 150);
        nodes.add(n8);

        graph.put("nodes", nodes);

        List<Map<String, Object>> edges = new ArrayList<>();
        Map<String, Object> e1 = new LinkedHashMap<>(); e1.put("from", "n_chat.global"); e1.put("to", "n_cond"); edges.add(e1);
        Map<String, Object> e2 = new LinkedHashMap<>(); e2.put("from", "n_cond"); e2.put("to", "n_transform"); edges.add(e2);
        Map<String, Object> e3 = new LinkedHashMap<>(); e3.put("from", "n_transform"); e3.put("to", "n_sleep"); edges.add(e3);
        Map<String, Object> e4 = new LinkedHashMap<>(); e4.put("from", "n_sleep"); e4.put("to", "n_clean"); edges.add(e4);
        Map<String, Object> e5 = new LinkedHashMap<>(); e5.put("from", "n_transform"); e5.put("to", "n_loop"); edges.add(e5);
        Map<String, Object> e6 = new LinkedHashMap<>(); e6.put("from", "n_loop"); e6.put("to", "n_cond"); edges.add(e6);
        Map<String, Object> e7 = new LinkedHashMap<>(); e7.put("from", "n_cond"); e7.put("to", "n_redirect"); edges.add(e7);
        Map<String, Object> e8 = new LinkedHashMap<>(); e8.put("from", "n_cond"); e8.put("to", "n_channel_redirect"); edges.add(e8);
        graph.put("edges", edges);

        return graph;
    }

    private static void setNested(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = map;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            // Handle array notation like repositories[] or channels[] or sounds[]
            if (part.endsWith("[]")) {
                part = part.substring(0, part.length() - 2);
                // For arrays, we don't create nested structure in defaults
                // The defaults for array elements are handled in buildDefaultChannels
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) current.computeIfAbsent(part, k -> new LinkedHashMap<>());
            current = next;
        }
        String lastPart = parts[parts.length - 1];
        if (!lastPart.endsWith("[]")) {
            current.put(lastPart, value);
        }
    }

    private static String toJsObject(Object obj) {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(obj)
                .replace("null", "null")
                .replace("true", "true")
                .replace("false", "false");
    }

    private static String getModelJsRest() {
        return "\n" +
                "  /* ── EXPORT ────────────────────────────────────────────── */\n" +
                "  function exportFiles(state, validation) {\n" +
                "    const files = {};\n" +
                "    files['config.yml'] = Suite.yaml.stringify(state.config);\n\n" +
                "    const sortedChannels = Object.keys(state.channels).sort();\n" +
                "    for (const name of sortedChannels) {\n" +
                "      files['channels/' + name + '.yml'] = Suite.yaml.stringify(state.channels[name]);\n" +
                "    }\n\n" +
                "    files['rules.yml'] = Suite.yaml.stringify(state.graph);\n" +
                "    files['manifest.json'] = JSON.stringify(manifest(state, validation), null, 2);\n\n" +
                "    for (const [key, edge] of Object.entries(state.sync)) {\n" +
                "      files['sync/' + key + '.yml'] = Suite.yaml.stringify(edge);\n" +
                "    }\n" +
                "    ftrans(state.translators, files);\n\n" +
                "    // Extensions\n" +
                "    for (const [id, cfg] of Object.entries(state.extensions)) {\n" +
                "      files['extensions/' + id + '.yml'] = Suite.yaml.stringify(cfg);\n" +
                "    }\n\n" +
                "    for (const [path, text] of Object.entries(state.extra || {})) {\n" +
                "      files[path] = text;\n" +
                "    }\n" +
                "    return files;\n" +
                "  }\n" +
                "  function ftrans(translators, files) {\n" +
                "    for (const [key, cfg] of Object.entries(translators)) {\n" +
                "      files['translators/' + key + '.yml'] = Suite.yaml.stringify(cfg);\n" +
                "    }\n" +
                "  }\n" +
                "  function manifest(state, validation) {\n" +
                "    validation = validation || { errors: 0, warnings: 0, blocking: false, issues: [] };\n" +
                "    const hasTransform = state.graph.nodes.some(n => n.kind === 'transform');\n" +
                "    return {\n" +
                "      schema: 'v2.2',\n" +
                "      'suite-version': '2.1.0',\n" +
                "      'generated-at': new Date().toISOString(),\n" +
                "      capabilities: { transforms: hasTransform },\n" +
                "      validation: {\n" +
                "        errors: validation.errors,\n" +
                "        warnings: validation.warnings,\n" +
                "        blocking: validation.blocking,\n" +
                "        issues: validation.issues,\n" +
                "      },\n" +
                "    };\n" +
                "  }\n\n" +
                "  /* ── IMPORT ────────────────────────────────────────────── */\n" +
                "  function importFromFiles(state, files) {\n" +
                "    const next = defaults();\n" +
                "    if (files['config.yml']) {\n" +
                "      const parsed = Suite.yaml.parse(files['config.yml']);\n" +
                "      if (parsed) {\n" +
                "        next.config = deepMerge(next.config, parsed);\n" +
                "      }\n" +
                "    }\n" +
                "    for (const [path, text] of Object.entries(files)) {\n" +
                "      const m = /^channels\\/(.+)\\.yml$/.exec(path);\n" +
                "      if (m) {\n" +
                "        const parsed = Suite.yaml.parse(text);\n" +
                "        if (parsed && typeof parsed === 'object' && parsed.name) {\n" +
                "          next.channels[parsed.name] = deepMerge(ch(parsed.name), parsed);\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    if (files['rules.yml']) {\n" +
                "      const parsed = Suite.yaml.parse(files['rules.yml']);\n" +
                "      if (parsed) {\n" +
                "        next.graph = deepMerge(next.graph, parsed);\n" +
                "      }\n" +
                "    }\n" +
                "    for (const [key] of Object.entries(next.translators)) {\n" +
                "      if (files['translators/' + key + '.yml']) {\n" +
                "        const parsed = Suite.yaml.parse(files['translators/' + key + '.yml']);\n" +
                "        if (parsed) {\n" +
                "          next.translators[key] = deepMerge(parsed, next.translators[key]);\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    for (const key of Object.keys(next.sync)) {\n" +
                "      if (files['sync/' + key + '.yml']) {\n" +
                "        const parsed = Suite.yaml.parse(files['sync/' + key + '.yml']);\n" +
                "        if (parsed) {\n" +
                "          next.sync[key] = deepMerge(parsed, next.sync[key]);\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    // Extensions\n" +
                "    for (const [path, text] of Object.entries(files)) {\n" +
                "      const m = /^extensions\\/(.+)\\.yml$/.exec(path);\n" +
                "      if (m) {\n" +
                "        const parsed = Suite.yaml.parse(text);\n" +
                "        if (parsed && typeof parsed === 'object') {\n" +
                "          next.extensions[m[1]] = parsed;\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    // conserva archivos desconocidos\n" +
                "    next.extra = {};\n" +
                "    for (const [path, text] of Object.entries(files)) {\n" +
                "      if (\n" +
                "        !path.startsWith('channels/') &&\n" +
                "        path !== 'config.yml' &&\n" +
                "        path !== 'rules.yml' &&\n" +
                "        path !== 'manifest.json' &&\n" +
                "        !path.startsWith('translators/') &&\n" +
                "        !path.startsWith('sync/') &&\n" +
                "        !path.startsWith('extensions/')\n" +
                "      ) {\n" +
                "        next.extra[path] = text;\n" +
                "      }\n" +
                "    }\n" +
                "    return next;\n" +
                "  }\n" +
                "  function deepMerge(base, over) {\n" +
                "    if (over === null || typeof over !== 'object' || Array.isArray(over)) {\n" +
                "      return over;\n" +
                "    }\n" +
                "    const out = clone(base || {});\n" +
                "    for (const k of Object.keys(over)) {\n" +
                "      if (k === '__proto__' || k === 'constructor') {\n" +
                "        continue;\n" +
                "      }\n" +
                "      const b = out[k],\n" +
                "        o = over[k];\n" +
                "      if (\n" +
                "        o !== null &&\n" +
                "        typeof o === 'object' &&\n" +
                "        !Array.isArray(o) &&\n" +
                "        b !== null &&\n" +
                "        typeof b === 'object' &&\n" +
                "        !Array.isArray(b)\n" +
                "      ) {\n" +
                "        out[k] = deepMerge(b, o);\n" +
                "      } else {\n" +
                "        out[k] = o;\n" +
                "      }\n" +
                "    }\n" +
                "    return out;\n" +
                "  }\n\n" +
                "  /* ── CRUD ──────────────────────────────────────────────── */\n" +
                "  function addNode(state, kind, label, x, y) {\n" +
                "    const id =\n" +
                "      'n_' +\n" +
                "      (label || kind)\n" +
                "        .toLowerCase()\n" +
                "        .replace(/[\\s.]/g, '-')\n" +
                "        .replace(/[^a-z0-9_-]/g, '') +\n" +
                "      '_' +\n" +
                "      Math.random().toString(36).slice(2, 6);\n" +
                "    const node = { id, kind, label: label || kind, x, y, w: 150 };\n" +
                "    if (kind === 'output') {\n" +
                "      node.label = node.label || 'chat.hub';\n" +
                "    }\n" +
                "    state.graph.nodes.push(node);\n" +
                "    return node;\n" +
                "  }\n" +
                "  function removeNode(state, id) {\n" +
                "    state.graph.nodes = state.graph.nodes.filter(n => n.id !== id);\n" +
                "    state.graph.edges = state.graph.edges.filter(e => e.from !== id && e.to !== id);\n" +
                "  }\n" +
                "  function addChannel(state, name, seedMessages) {\n" +
                "    const safe = name || 'nuevo.chat';\n" +
                "    let key = safe;\n" +
                "    if (state.channels[key]) {\n" +
                "      let i = 1;\n" +
                "      while (state.channels[key + (i === 1 ? '' : i)]) {\n" +
                "        i++;\n" +
                "      }\n" +
                "      void i;\n" +
                "      key = safe + '-' + i;\n" +
                "    }\n" +
                "    state.channels[key] = ch(key, { messages: seedMessages || ['&7👉 &f%player_name%&7: %content%'] });\n" +
                "    addNode(state, 'input', key, 60 + Math.random() * 220, 100 + Math.random() * 220);\n" +
                "    return key;\n" +
                "  }\n" +
                "  function renameChannel(state, oldName, newName) {\n" +
                "    if (oldName === newName || !state.channels[oldName] || state.channels[newName]) {\n" +
                "      return false;\n" +
                "    }\n" +
                "    const cfg = state.channels[oldName];\n" +
                "    delete state.channels[oldName];\n" +
                "    cfg.name = newName;\n" +
                "    state.channels[newName] = cfg;\n" +
                "    for (const n of state.graph.nodes) {\n" +
                "      if ((n.kind === 'input' || n.kind === 'output') && n.label === oldName) {\n" +
                "        n.label = newName;\n" +
                "      }\n" +
                "    }\n" +
                "    for (const edge of Object.values(state.sync)) {\n" +
                "      if (edge && typeof edge === 'object' && edge.channel === oldName) {\n" +
                "        edge.channel = newName;\n" +
                "      }\n" +
                "    }\n" +
                "    return true;\n" +
                "  }\n" +
                "  function addEdge(state, from, to) {\n" +
                "    if (from === to) {\n" +
                "      return;\n" +
                "    }\n" +
                "    const dup = state.graph.edges.some(e => e.from === from && e.to === to);\n" +
                "    if (dup) {\n" +
                "      return;\n" +
                "    }\n" +
                "    state.graph.edges.push({ from, to });\n" +
                "  }\n" +
                "  function removeEdge(state, from, to) {\n" +
                "    state.graph.edges = state.graph.edges.filter(e => !(e.from === from && e.to === to));\n" +
                "  }\n\n" +
                "  global.Suite = global.Suite || {};\n" +
                "  global.Suite.model = {\n" +
                "    defaults,\n" +
                "    clone,\n" +
                "    exportFiles,\n" +
                "    importFromFiles,\n" +
                "    manifest,\n" +
                "    addNode,\n" +
                "    removeNode,\n" +
                "    addChannel,\n" +
                "    renameChannel,\n" +
                "    addEdge,\n" +
                "    removeEdge,\n" +
                "    KNOWN_LANGS,\n" +
                "  };\n";
    }

    private static String toLabel(String enumName) {
        String[] parts = enumName.split("_");
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) label.append(" ");
            String part = parts[i].toLowerCase();
            if (part.equals("iflow")) part = "iFlow";
            if (part.equals("sonido")) part = "Sonido";
            if (part.equals("parallel")) part = "Parallel";
            if (part.equals("claim")) part = "Claim";
            if (part.equals("mode")) part = "Mode";
            if (part.equals("log")) part = "Log";
            if (part.equals("to")) part = "To";
            if (part.equals("console")) part = "Console";
            if (part.equals("rate")) part = "Rate";
            if (part.equals("limit")) part = "Limit";
            if (part.equals("per")) part = "Per";
            if (part.equals("second")) part = "Second";
            if (part.equals("lang")) part = "Lang";
            if (part.equals("source")) part = "Source";
            if (part.equals("target")) part = "Target";
            if (part.equals("send")) part = "Send";
            if (part.equals("receive")) part = "Receive";
            if (part.equals("permission")) part = "Permission";
            if (part.equals("show")) part = "Show";
            if (part.equals("sender")) part = "Sender";
            if (part.equals("messages")) part = "Messages";
            if (part.equals("tooltips")) part = "Tooltips";
            if (part.equals("sounds")) part = "Sounds";
            if (part.equals("name")) part = "Name";
            if (part.equals("volume")) part = "Volume";
            if (part.equals("pitch")) part = "Pitch";
            if (part.equals("repositories")) part = "Repositories";
            if (part.equals("repository")) part = "Repository";
            if (part.equals("url")) part = "URL";
            if (part.equals("enabled")) part = "Enabled";
            if (part.equals("type")) part = "Type";
            label.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return label.toString();
    }

    private static String inferType(ConfigLoader.ConfigPath cp) {
        String name = cp.name().toLowerCase();
        if (name.contains("parallel") || name.contains("enabled") || name.contains("show") || name.contains("log") || name.contains("dedup")) {
            return "boolean";
        }
        if (name.contains("rate") || name.contains("limit") || name.contains("port") || name.contains("max") || name.contains("step") || name.contains("chat") || name.contains("pitch") || name.contains("volume")) {
            return name.contains("pitch") || name.contains("volume") ? "float" : "integer";
        }
        return "string";
    }

    private static Object getDefaultValue(ConfigLoader.ConfigPath cp) {
        String name = cp.name().toLowerCase();
        if (name.contains("parallel")) return false;
        if (name.contains("enabled") || name.contains("show") || name.contains("log") || name.contains("dedup")) return true;
        if (name.contains("quick")) return true;
        if (name.contains("language")) return "en";
        if (name.contains("claim") && name.contains("mode")) return "cancel-event";
        if (name.contains("rate") || name.contains("limit") || name.contains("port") || name.contains("max") || name.contains("step")) return 0;
        if (name.contains("pitch") || name.contains("volume")) return 1.0;
        if (name.contains("source") || name.contains("target")) return "auto";
        if (name.contains("type")) return "chat";
        return "";
    }

    private static class PathMeta {
        String label;
        String desc;
        String type;
        Object defaultValue;
    }
}