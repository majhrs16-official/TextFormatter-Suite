package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.majhrs16.suite.api.command.CommandArgument;
import me.majhrs16.suite.api.command.CommandConflictException;
import me.majhrs16.suite.api.command.CommandOption;
import me.majhrs16.suite.api.command.CommandPath;
import me.majhrs16.suite.api.command.CommandRegistration;
import me.majhrs16.suite.api.command.CommandResult;
import me.majhrs16.suite.api.command.CommandRoot;
import me.majhrs16.suite.api.command.CommandSource;
import me.majhrs16.suite.api.command.CommandSpec;
import me.majhrs16.suite.api.message.Actor;
import org.junit.jupiter.api.Test;

/** F4d gate: hierarchical command registration, dispatch and teardown. */
class RuntimeCommandRegistryTest {

    private final RuntimeCommandRegistry registry =
            new RuntimeCommandRegistry(CommandRoot.of("suite"), FixtureModules.logger());

    @Test
    void dispatchesHierarchicalCommandsAndRemovesThemOnClose() {
        CommandRegistration registration = registry.register("demo",
                CommandSpec.builder("demo")
                        .child(CommandSpec.builder("echo")
                                .handler(context -> context.reply(
                                        "echo:" + context.requireArg(0)))
                                .build())
                        .build());

        Source source = new Source();
        CommandResult result = registry.dispatch(source, List.of("demo", "echo", "hola"));

        assertTrue(result.handled());
        assertEquals(List.of("echo:hola"), source.replies);
        assertEquals(1, registry.commands().size());
        assertEquals(CommandResult.Status.NOT_FOUND,
                registry.dispatch(source, List.of("demo", "demo", "echo", "hola")).status());

        registration.close();
        registration.close();

        assertTrue(registry.commands().isEmpty());
        assertEquals(CommandResult.Status.NOT_FOUND,
                registry.dispatch(source, List.of("demo", "echo", "hola")).status());
    }

    @Test
    void rejectsDuplicateNamesAndAliasesWithoutSilentOverride() {
        registry.register("demo", CommandSpec.builder("demo")
                .alias("d")
                .handler(context -> { })
                .build());

        assertThrows(CommandConflictException.class, () -> registry.register("other",
                CommandSpec.builder("demo").handler(context -> { }).build()));
        assertThrows(CommandConflictException.class, () -> registry.register("other",
                CommandSpec.builder("other").alias("d").handler(context -> { }).build()));
    }

    @Test
    void enforcesNodePermissions() {
        registry.register("secret", CommandSpec.builder("secret")
                .permission("demo.secret")
                .handler(context -> context.reply("ok"))
                .build());

        Source denied = new Source();
        CommandResult result = registry.dispatch(denied, List.of("secret"));

        assertEquals(CommandResult.Status.DENIED, result.status());
        assertTrue(denied.replies.isEmpty());

        Source allowed = new Source("demo.secret");
        assertTrue(registry.dispatch(allowed, List.of("secret")).handled());
        assertEquals(List.of("ok"), allowed.replies);
    }

    @Test
    void reportsUsageAndErrorsWithoutLeakingThem() {
        registry.register("demo", CommandSpec.builder("demo")
                .usage("<arg>")
                .handler(context -> context.requireArg(0))
                .build());
        registry.register("boom", CommandSpec.builder("boom")
                .handler(context -> {
                    throw new IllegalStateException("boom");
                })
                .build());

        Source source = new Source();
        CommandResult usage = registry.dispatch(source, List.of("demo"));

        assertEquals(CommandResult.Status.USAGE, usage.status());
        assertEquals("/suite demo <arg>", usage.message());

        CommandResult error = registry.dispatch(source, List.of("boom"));
        assertEquals(CommandResult.Status.ERROR, error.status());
        assertTrue(error.message().contains("boom"));
    }

    @Test
    void completesChildrenAndArguments() {
        registry.register("demo", CommandSpec.builder("demo")
                .child(CommandSpec.builder("echo").handler(context -> { }).build())
                .child(CommandSpec.builder("config").handler(context -> { }).build())
                .build());

        Source source = new Source();

        assertEquals(List.of("echo"), registry.complete(source, List.of("demo", "e")));
        assertEquals(List.of("echo", "config"), registry.complete(source, List.of("demo", "")));
        assertTrue(registry.complete(source, List.of("missing", "")).isEmpty());
    }

    @Test
    void completesOptionsAndTheirValues() {
        registry.register("demo", CommandSpec.builder("demo")
                .option(CommandOption.flag("force"))
                .option(CommandOption.valued("channel").suggest("stable", "beta"))
                .handler(context -> { })
                .build());

        Source source = new Source();

        assertEquals(List.of("--force"), registry.complete(source, List.of("demo", "--f")));
        assertEquals(List.of("stable", "beta"),
                registry.complete(source, List.of("demo", "--channel", "")));
        assertEquals(List.of("beta"),
                registry.complete(source, List.of("demo", "--channel", "b")));
    }

    @Test
    void completesRootCommandsAndArguments() {
        registry.register("demo", CommandSpec.builder("demo")
                .argument(CommandArgument.required("name").suggest("alpha", "beta"))
                .handler(context -> context.reply(context.requireArg(0)))
                .build());

        Source source = new Source();

        assertEquals(List.of("demo"), registry.complete(source, List.of("de")));
        assertEquals(List.of("alpha", "beta"), registry.complete(source, List.of("demo", "")));
        assertEquals(List.of("alpha"), registry.complete(source, List.of("demo", "a")));
    }

    @Test
    void consumesRemainingRootSegmentsForMultiSegmentRoots() {
        RuntimeCommandRegistry multi = new RuntimeCommandRegistry(
                CommandRoot.of("suite", "module"), FixtureModules.logger());
        multi.register("demo", CommandSpec.builder("demo")
                .child(CommandSpec.builder("echo")
                        .handler(context -> context.reply(context.path().display()))
                        .build())
                .build());

        Source source = new Source();
        CommandResult result = multi.dispatch(source, List.of("module", "demo", "echo"));

        assertTrue(result.handled());
        assertEquals(List.of("demo echo"), source.replies);
        assertEquals(CommandResult.Status.NOT_FOUND,
                multi.dispatch(source, List.of("demo", "echo")).status());
    }

    @Test
    void moduleScopeRejectsCommandsOutsideItsNamespace() {
        assertThrows(IllegalArgumentException.class, () -> registry.scope("demo")
                .register(CommandSpec.builder("other")
                        .handler(context -> { })
                        .build()));
    }

    @Test
    void moduleScopeCanDeclareAnExplicitPath() {
        CommandRegistration registration = registry.scope("demo").register(
                CommandPath.of("shared"),
                CommandSpec.builder("status")
                        .handler(context -> context.reply("ok"))
                        .build());

        Source source = new Source();
        assertTrue(registry.dispatch(source, List.of("shared", "status")).handled());
        assertEquals(List.of("ok"), source.replies);

        registration.close();
        assertEquals(CommandResult.Status.NOT_FOUND,
                registry.dispatch(source, List.of("shared", "status")).status());
    }

    @Test
    void removesEveryRegistrationOwnedByAModule() {
        registry.register("demo", CommandSpec.builder("demo")
                .handler(context -> { })
                .build());
        registry.register("runtime", CommandSpec.builder("list")
                .handler(context -> { })
                .build());

        registry.removeOwner("demo");

        assertEquals(List.of("list"), registry.commands().stream()
                .map(CommandSpec::name).toList());
        assertFalse(registry.commands().isEmpty());
    }

    private static final class Source implements CommandSource {

        private final Actor actor = Actor.unknown("tester");
        private final Set<String> permissions;
        private final List<String> replies = new ArrayList<>();

        private Source(String... permissions) {
            this.permissions = Set.of(permissions);
        }

        @Override
        public Actor actor() {
            return actor;
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public void reply(String message) {
            replies.add(message);
        }
    }
}
