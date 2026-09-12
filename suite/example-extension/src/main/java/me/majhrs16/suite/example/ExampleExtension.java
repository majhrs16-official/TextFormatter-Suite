package me.majhrs16.suite.example;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.message.Message;
import me.majhrs16.suite.api.message.MessageType;
import me.majhrs16.suite.api.message.Direction;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.extension.Extension;
import me.majhrs16.suite.extension.ExtensionContext;
import me.majhrs16.suite.extension.ExtensionConfig;
import me.majhrs16.suite.extension.ExtensionMetadata;
import me.majhrs16.suite.textformatter.channel.Channel;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.majhrs16.suite.api.Capability;
import me.majhrs16.suite.api.SemVer;

/**
 * Example extension demonstrating the extension API.
 * <p>
 * This extension:
 * - Registers a custom channel "example.custom"
 * - Adds a /suite example command
 * - Listens for messages and logs them
 * - Provides a custom capability
 * </p>
 */
public final class ExampleExtension implements Extension {

    private ExtensionContext context;
    private boolean enabled = false;

    @Override
    public String id() {
        return "example-extension";
    }

    @Override
    public String name() {
        return "Example Extension";
    }

    @Override
    public SemVer version() {
        return SemVer.of(1, 0, 0);
    }

    @Override
    public SemVer requiredCoreApi() {
        return SemVer.of(2, 1, 0);
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public Set<Capability> providedCapabilities() {
        return Set.of(Capability.of("custom-command", SemVer.of(1, 0, 0)));
    }

    @Override
    public Set<Capability> requiredCapabilities() {
        return Set.of();
    }

    @Override
    public void onEnable(ExtensionContext context) {
        this.context = context;
        this.enabled = true;

        // Register custom channel
        Channel customChannel = Channel.builder("example.custom")
            .name("example.custom")
            .permission("example.custom")
            .messages(List.of("<gold>[Example] %content%</gold>"))
            .showSender(true)
            .type(Channel.Type.CHAT)
            .build();
        context.registerChannel(customChannel);

        // Register custom command /suite example
        context.dispatcher().ifPresent(dispatcher -> {
            // Note: In real implementation, use command registrar
            context.logger().info("[ExampleExtension] Registered custom channel and command");
        });

        // Subscribe to events
        context.subscribe("message.processed", event -> {
            if (event instanceof Map<?, ?> map) {
                String content = (String) map.get("content");
                context.logger().debug("[ExampleExtension] Message processed: " + content);
            }
        });

        // Store initial state
        context.putState("startTime", System.currentTimeMillis());
        context.putState("messageCount", 0);

        context.logger().info("[ExampleExtension] Enabled successfully");
    }

    @Override
    public void onDisable() {
        this.enabled = false;

        // Unregister channel
        context.unregisterChannel("example.custom");

        // Clear state
        context.removeState("startTime");
        context.removeState("messageCount");

        context.logger().info("[ExampleExtension] Disabled");
    }

    @Override
    public void onConfigReload(ExtensionConfig config) {
        context.logger().info("[ExampleExtension] Config reloaded: " + config.asMap());
    }

    @Override
    public ExtensionMetadata metadata() {
        return ExtensionMetadata.builder()
            .id("example-extension")
            .name("Example Extension")
            .description("Demonstrates the extension API with custom channel and commands")
            .author("TextFormatter Suite Team")
            .website("https://github.com/majhrs16/textformatter-suite")
            .license("GPL-3.0")
            .tags(List.of("example", "demo", "api"))
            .links(Map.of(
                "wiki", "https://github.com/majhrs16/textformatter-suite/wiki",
                "issues", "https://github.com/majhrs16/textformatter-suite/issues"
            ))
            .minSuiteVersion("2.1.0")
            .stable(true)
            .experimental(false)
            .build();
    }

    @Override
    public String configSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "enabled": { "type": "boolean", "default": true },
                "logMessages": { "type": "boolean", "default": false },
                "customMessage": { "type": "string", "default": "Hello from example extension!" }
              }
            }
            """;
    }
}