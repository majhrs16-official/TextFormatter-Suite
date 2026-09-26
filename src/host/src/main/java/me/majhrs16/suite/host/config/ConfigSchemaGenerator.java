package me.majhrs16.suite.host.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates paths.json for the web editor from ConfigPath enum.
 * This ensures the editor's paths.json stays in sync with the Java config schema.
 * Run as: ./gradlew :src:host:run --args="<output-dir>" --no-daemon
 */
public final class ConfigSchemaGenerator {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: ConfigSchemaGenerator <output-dir>");
            System.exit(1);
        }
        Path outputDir = Path.of(args[0]);

        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();

        // Generate from ConfigPath enum
        for (ConfigLoader.ConfigPath cp : ConfigLoader.ConfigPath.values()) {
            String[] parts = cp.name().split("_");
            StringBuilder pathBuilder = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].toLowerCase();
                if (i == 0) {
                    if (part.equals("channel") || part.equals("sound") || part.equals("repository")) {
                        // Skip top-level collection names, they're handled by the parent
                        continue;
                    }
                    pathBuilder.append("config.");
                } else {
                    pathBuilder.append(".");
                }
                pathBuilder.append(part);
            }

            String path = pathBuilder.toString();
            if (path.isEmpty()) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", toLabel(cp.name()));
            meta.put("desc", "");
            meta.put("type", inferType(cp));
            meta.put("default", getDefaultValue(cp));
            paths.put(path, meta);
        }

        // Add additional paths not in ConfigPath (translators, sync, graph)
        addEditorSpecificPaths(paths);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", 1);
        output.put("paths", paths);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputDir.resolve("paths.json").toFile())) {
            gson.toJson(output, writer);
        }
        System.out.println("Generated " + outputDir.resolve("paths.json") + " with " + paths.size() + " paths");
    }

    private static void addEditorSpecificPaths(Map<String, Map<String, Object>> paths) {
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

    private static void addPath(Map<String, Map<String, Object>> paths, String path, String label, String desc, String type, Object defaultValue) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("label", label);
        meta.put("desc", desc);
        meta.put("type", type);
        meta.put("default", defaultValue);
        paths.put(path, meta);
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
}