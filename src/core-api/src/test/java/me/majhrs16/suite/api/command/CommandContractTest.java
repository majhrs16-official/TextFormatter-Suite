package me.majhrs16.suite.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import me.majhrs16.suite.api.config.ConfigValue;
import me.majhrs16.suite.api.message.Actor;
import org.junit.jupiter.api.Test;

/** Contract tests for the neutral command and configuration API. */
class CommandContractTest {

    @Test
    void parsesMultiSegmentRootsAndAliases() {
        CommandRoot root = CommandRoot.parse("suite module")
                .withAlias("sm");

        assertEquals(List.of("suite", "module"), root.segments());
        assertEquals("suite module", root.display());
        assertEquals(List.of("module"), root.remaining());
        assertEquals(List.of(List.of("sm")), root.aliases());
        assertThrows(IllegalArgumentException.class, () -> CommandRoot.parse("Suite"));
    }

    @Test
    void rejectsAmbiguousCommandTrees() {
        CommandSpec child = CommandSpec.builder("echo")
                .alias("e")
                .handler(context -> { })
                .build();

        assertThrows(IllegalArgumentException.class, () -> CommandSpec.builder("demo")
                .child(child)
                .child(CommandSpec.builder("other")
                        .alias("e")
                        .handler(context -> { })
                        .build())
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> CommandSpec.builder("empty").build());
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.builder("demo")
                .child(CommandSpec.builder("demo")
                        .handler(context -> { })
                        .build())
                .build());
    }

    @Test
    void validatesOptions() {
        CommandSpec spec = CommandSpec.builder("demo")
                .option(CommandOption.flag("force").describe("Fuerza la operación"))
                .option(CommandOption.valued("channel").suggest("stable", "beta"))
                .handler(context -> { })
                .build();

        assertEquals("--force", spec.options().get(0).flagToken());
        assertThrows(IllegalArgumentException.class, () -> CommandSpec.builder("demo")
                .option(CommandOption.flag("force"))
                .option(CommandOption.valued("force"))
                .handler(context -> { })
                .build());
    }

    @Test
    void composesUsageFromRootPathAndArguments() {
        CommandContext context = new CommandContext(
                CommandRoot.of("suite"),
                new Source(),
                CommandPath.of("demo", "echo"),
                List.of("hola"),
                "<texto>");

        assertEquals("/suite demo echo <texto>", context.usage());
        assertEquals("hola", context.requireArg(0));
        assertThrows(CommandUsageException.class, () -> context.requireArg(1));
    }

    @Test
    void validatesConfigValueTypes() {
        assertEquals("hola", ConfigValue.of("greeting", String.class, "hola").defaultValue());
        assertThrows(IllegalArgumentException.class,
                () -> ConfigValue.of("count", Integer.class, "not-an-int"));
    }

    private static final class Source implements CommandSource {

        @Override
        public Actor actor() {
            return Actor.unknown("tester");
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void reply(String message) {
            // Contract test: replies are not inspected here.
        }
    }
}
