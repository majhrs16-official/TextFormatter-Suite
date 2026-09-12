package me.majhrs16.suite.presets;

import me.majhrs16.suite.textformatter.channel.Channel;
import me.majhrs16.suite.textformatter.channel.ChannelRegistry;
import me.majhrs16.suite.host.config.HostConfig;
import me.majhrs16.suite.iflow.rule.Rule;
import me.majhrs16.suite.host.config.HostConfig;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages loading, storing, and applying presets.
 */
public final class PresetManager {

    private final Map<String, Preset> presets = new ConcurrentHashMap<>();
    private final Path presetsDir;
    private final Yaml yaml = new Yaml();

    public PresetManager(Path presetsDir) {
        this.presetsDir = presetsDir.toAbsolutePath();
        try {
            Files.createDirectories(this.presetsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create presets dir: " + presetsDir, e);
        }
        loadBuiltInPresets();
        loadCustomPresets();
    }

    /**
     * Loads built-in presets that ship with the suite.
     */
    private void loadBuiltInPresets() {
        // Default / Standard preset
        registerBuiltIn(new Preset(
            "standard",
            "Standard",
            "Default configuration for general-purpose chat formatting and translation",
            "TextFormatter Suite Team",
            "1.0.0",
            "2.1.0",
            List.of("default", "standard", "general"),
            new Preset.PresetConfig(true, "en", false, true, "cancel-event"),
            List.of(
                new Preset.PresetChannel("chat.global", "CHAT", "cht.chat.global",
                    "cht.chat.global.send", "cht.chat.global.receive",
                    true, 0, "auto", "auto",
                    List.of("<green>💬 %content%</green>", "&7👉 &f%player_name%&7: %content%"),
                    List.of("Hover: %lang_source% → %lang_target%"),
                    List.of(new Preset.PresetSound("entity.experience_orb.pickup", 1.0f, 1.0f))),
                new Preset.PresetChannel("join", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<green>+</green> %player_name% joined the game"),
                    List.of(), List.of()),
                new Preset.PresetChannel("quit", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<red>-</red> %player_name% left the game"),
                    List.of(), List.of()),
                new Preset.PresetChannel("death", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<red>%content%</red>"),
                    List.of(), List.of()),
                new Preset.PresetChannel("advancement", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<yellow>%player_name% has made the advancement [%content%]</yellow>"),
                    List.of(), List.of())
            ),
            List.of(),
            List.of(
                new Preset.PresetTranslator("google", true, "", "", 6),
                new Preset.PresetTranslator("libre", false, "https://libretranslate.example", "", 6)
            ),
            List.of(
                new Preset.PresetSync("discord", false, Map.of("token", "", "channel", "0")),
                new Preset.PresetSync("telegram", false, Map.of("token", "", "chat-id", "0", "hub", "")),
                new Preset.PresetSync("http", false, Map.of("webhook-url", "", "inbound-port", "0", "path", "")),
                new Preset.PresetSync("tcp-udp", false, Map.of("protocol", "TCP", "host", "0.0.0.0", "outbound-port", "0", "inbound-port", "0")),
                new Preset.PresetSync("velocity", false, Map.of("secret", "", "servers", "[]", "mapping", "* -> chat.hub"))
            )
        ));

        // Survival / RPG preset
        registerBuiltIn(new Preset(
            "rpg",
            "RPG / Survival",
            "Immersive chat with roleplay formatting, custom channels, and lore-friendly formatting",
            "TextFormatter Suite Team",
            "1.0.0",
            "2.1.0",
            List.of("rpg", "survival", "roleplay", "immersive"),
            new Preset.PresetConfig(true, "en", false, true, "cancel-event"),
            List.of(
                new Preset.PresetChannel("chat.global", "CHAT", "rpg.chat.global",
                    "rpg.chat.global.send", "rpg.chat.global.receive",
                    true, 3, "auto", "auto",
                    List.of("<gradient:gold:yellow>[<dark_gray>%player_name%</dark_gray>]</gradient> <white>%content%</white>"),
                    List.of("Hover for original: %content%"),
                    List.of(new Preset.PresetSound("entity.player.levelup", 0.5f, 1.2f))),
                new Preset.PresetChannel("chat.local", "CHAT", "rpg.chat.local",
                    "rpg.chat.local.send", "rpg.chat.local.receive",
                    true, 5, "auto", "auto",
                    List.of("<green>[Local]</green> <gray>%player_name%</gray>: <white>%content%</white>"),
                    List.of("Local chat - only nearby players see this"),
                    List.of(new Preset.PresetSound("entity.experience_orb.pickup", 0.3f, 1.0f))),
                new Preset.PresetChannel("chat.rp", "CHAT", "rpg.chat.rp",
                    "rpg.chat.rp.send", "rpg.chat.rp.receive",
                    true, 2, "auto", "auto",
                    List.of("<italic><dark_purple>* %player_name% %content%</dark_purple></italic>"),
                    List.of("Roleplay action"),
                    List.of()),
                new Preset.PresetChannel("chat.ooc", "CHAT", "rpg.chat.ooc",
                    "rpg.chat.ooc.send", "rpg.chat.ooc.receive",
                    true, 10, "auto", "auto",
                    List.of("<dark_gray>(OOC) %player_name%: %content%</dark_gray>"),
                    List.of("Out of character chat"),
                    List.of()),
                new Preset.PresetChannel("join", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<gold>✦</gold> <yellow>%player_name%</yellow> <gray>has joined the realm</gray>"),
                    List.of(), List.of(new Preset.PresetSound("entity.player.levelup", 0.5f, 1.0f))),
                new Preset.PresetChannel("quit", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<gold>✦</gold> <gray>%player_name% <gray>has left the realm</gray>"),
                    List.of(), List.of(new Preset.PresetSound("entity.villager.no", 0.5f, 0.8f))),
                new Preset.PresetChannel("death", "EVENT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<red>☠</red> <red>%content%</red>"),
                    List.of(), List.of(new Preset.PresetSound("entity.generic.death", 0.7f, 0.9f)))
            ),
            List.of(
                new Preset.PresetRule("anti-spam", 10,
                    "'spam' in #msg.texts[0] or 'SPAM' in #msg.texts[0]",
                    "cancel()", "chat.global"),
                new Preset.PresetRule("rp-format", 20,
                    "#msg.channel == 'chat.rp'",
                    "setText('<italic><dark_purple>* ' + #msg.sender.name + ' ' + #msg.texts[0] + '</dark_purple></italic>')", "chat.rp")
            ),
            List.of(
                new Preset.PresetTranslator("google", true, "", "", 6),
                new Preset.PresetTranslator("libre", false, "https://libretranslate.example", "", 6)
            ),
            List.of()
        ));

        // Staff / Moderation preset
        registerBuiltIn(new Preset(
            "staff",
            "Staff / Moderation",
            "Tools for server moderation with alerts, logs, and staff channels",
            "TextFormatter Suite Team",
            "1.0.0",
            "2.1.0",
            List.of("staff", "moderation", "admin", "moderation"),
            new Preset.PresetConfig(true, "en", true, true, "cancel-event"),
            List.of(
                new Preset.PresetChannel("staff.chat", "CHAT", "staff.chat",
                    "staff.chat.send", "staff.chat.receive",
                    true, 0, "auto", "auto",
                    List.of("<red>[STAFF]</red> <dark_red>%player_name%</dark_red>: <white>%content%</white>"),
                    List.of("Staff-only channel"),
                    List.of(new Preset.PresetSound("block.note_block.pling", 1.0f, 1.5f))),
                new Preset.PresetChannel("staff.alerts", "CHAT", "staff.alerts",
                    "staff.alerts.send", "staff.alerts.receive",
                    false, 0, "auto", "auto",
                    List.of("<red>⚠ ALERT</red> <yellow>%content%</yellow>"),
                    List.of("Staff alerts"),
                    List.of(new Preset.PresetSound("block.note_block.pling", 1.0f, 2.0f))),
                new Preset.PresetChannel("mod.log", "CHAT", "mod.log",
                    "mod.log.send", "mod.log.receive",
                    false, 0, "auto", "auto",
                    List.of("<dark_gray>[LOG]</dark_gray> <gray>%content%</gray>"),
                    List.of("Moderation logs"),
                    List.of())
            ),
            List.of(
                new Preset.PresetRule("anti-spam", 10,
                    "'spam' in #msg.texts[0].lower() or 'hack' in #msg.texts[0].lower()",
                    "cancel(); log('spam attempt by ' + #msg.sender.name)", "chat.global"),
                new Preset.PresetRule("staff-alert", 5,
                    "#msg.sender.hasPermission('staff.alerts')",
                    "redirect('staff.alerts')", "chat.global")
            ),
            List.of(
                new Preset.PresetTranslator("google", true, "", "", 6)
            ),
            List.of(
                new Preset.PresetSync("discord", true, Map.of(
                    "token", "",
                    "channel", "0",
                    "intents", "GUILD_MESSAGES,MESSAGE_CONTENT"
                )),
                new Preset.PresetSync("http", true, Map.of(
                    "webhook-url", "",
                    "inbound-port", "8080",
                    "path", "/webhook"
                ))
            )
        ));

        // Minimal / Lightweight preset
        registerBuiltIn(new Preset(
            "minimal",
            "Minimal / Lightweight",
            "Minimal configuration for low-resource servers or testing",
            "TextFormatter Suite Team",
            "1.0.0",
            "2.1.0",
            List.of("minimal", "lightweight", "testing"),
            new Preset.PresetConfig(true, "en", false, false, "cancel-event"),
            List.of(
                new Preset.PresetChannel("chat.global", "CHAT", "",
                    "", "", true, 0, "auto", "auto",
                    List.of("<white>%player_name%: %content%</white>"),
                    List.of(), List.of())
            ),
            List.of(),
            List.of(
                new Preset.PresetTranslator("google", true, "", "", 6)
            ),
            List.of()
        ));
    }

    private void registerBuiltIn(Preset preset) {
        presets.put(preset.id(), preset);
    }

    private void loadCustomPresets() {
        if (!Files.exists(presetsDir)) {
            try {
                Files.createDirectories(presetsDir);
            } catch (IOException e) {
                return;
            }
        }

        try (var stream = Files.list(presetsDir)) {
            stream.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        Yaml yaml = new Yaml();
                        Map<String, Object> map = (Map<String, Object>) new Yaml().load(content);
                        // TODO: Parse custom preset from YAML
                    } catch (Exception e) {
                        // Log error
                    }
                });
        } catch (IOException ignored) {}
    }

    // ============================================================
    // Public API
    // ============================================================

    public Optional<Preset> get(String id) {
        return Optional.ofNullable(presets.get(id));
    }

    public List<Preset> listAll() {
        return new ArrayList<>(presets.values());
    }

    public List<Preset> listByTag(String tag) {
        return presets.values().stream()
            .filter(p -> p.tags().contains(tag))
            .toList();
    }

    public boolean register(Preset preset) {
        if (presets.containsKey(preset.id())) {
            return false;
        }
        presets.put(preset.id(), preset);
        return true;
    }

    public void unregister(String id) {
        presets.remove(id);
    }

    /**
     * Applies a preset by ID.
     */
    public boolean apply(String presetId, ChannelRegistry registry, HostConfig hostConfig,
                         List<Rule> ruleList, List<Channel> channelList) {
        Preset preset = presets.get(presetId);
        if (preset == null) return false;

        preset.apply(registry, hostConfig, new ArrayList<>(), new ArrayList<>());
        return true;
    }

    /**
     * Exports a preset to YAML file.
     */
    public void export(String presetId, Path outputFile) throws IOException {
        Preset preset = presets.get(presetId);
        if (preset == null) return;

        // TODO: Export to YAML
    }

    public Collection<Preset> getAll() {
        return Collections.unmodifiableCollection(presets.values());
    }
}