package me.majhrs16.suite.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.majhrs16.suite.api.command.CommandArgument;
import me.majhrs16.suite.api.command.CommandContext;
import me.majhrs16.suite.api.command.CommandOption;
import me.majhrs16.suite.api.command.CommandRegistry;
import me.majhrs16.suite.api.command.CommandSpec;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;

/**
 * Built-in package commands registered in the neutral command tree.
 *
 * <p>They are ordinary {@link CommandSpec} nodes, exactly like module commands.
 * The platform adapter never sees this class; it only sees the root command
 * that delegates to {@link CommandRegistry}.</p>
 */
final class SuiteCommands {

    private static final String PERMISSION = "textformatter.suite.admin";

    private SuiteCommands() {
    }

    static void register(CommandRegistry commands, SuiteRuntime runtime) {
        commands.register(help(commands));
        commands.register(list(runtime));
        commands.register(state(runtime));
        commands.register(install(runtime));
        commands.register(remove(runtime));
        commands.register(upgrade(runtime));
        commands.register(repository(runtime));
    }

    private static CommandSpec help(CommandRegistry commands) {
        return CommandSpec.builder("help")
                .description("Muestra esta ayuda")
                .permission(PERMISSION)
                .handler(context -> {
                    context.reply("Uso: /" + context.root().display() + " <comando>");
                    for (CommandSpec command : commands.commands()) {
                        describe(context, "", command);
                    }
                })
                .build();
    }

    private static void describe(CommandContext context, String prefix, CommandSpec spec) {
        if (spec.permission() != null && !context.source().hasPermission(spec.permission())) {
            return;
        }
        String usage = spec.usage().isBlank() ? "" : " " + spec.usage();
        context.reply("  " + prefix + spec.name() + usage + "  " + spec.description());
        for (CommandOption option : spec.options()) {
            context.reply("    " + option.flagToken()
                    + (option.requiresValue() ? " <valor>" : "")
                    + "  " + option.description());
        }
        for (CommandSpec child : spec.children()) {
            describe(context, prefix + spec.name() + " ", child);
        }
    }

    private static CommandSpec list(SuiteRuntime runtime) {
        return CommandSpec.builder("list")
                .description("Lista los paquetes instalados")
                .permission(PERMISSION)
                .handler(context -> {
                    List<PackageEntry> packages = runtime.manager().packageList();
                    if (packages.isEmpty()) {
                        context.reply("No hay paquetes instalados.");
                        return;
                    }
                    Map<String, ModuleState> states = runtime.snapshot();
                    for (PackageEntry entry : packages) {
                        ModuleState state = states.get(entry.name());
                        context.reply(entry.name() + " " + entry.version()
                                + " [" + entry.channel() + "] " + entry.type()
                                + (state == null ? "" : " -> " + state));
                    }
                })
                .build();
    }

    private static CommandSpec state(SuiteRuntime runtime) {
        return CommandSpec.builder("state")
                .description("Muestra el estado runtime de los módulos")
                .permission(PERMISSION)
                .handler(context -> {
                    Map<String, ModuleState> states = runtime.snapshot();
                    if (states.isEmpty()) {
                        context.reply("No hay módulos conocidos.");
                        return;
                    }
                    states.forEach((id, state) -> context.reply(id + ": " + state));
                })
                .build();
    }

    private static CommandSpec install(SuiteRuntime runtime) {
        return CommandSpec.builder("install")
                .description("Instala y activa un ZIP local")
                .permission(PERMISSION)
                .usage("<zip>")
                .argument(CommandArgument.required("zip")
                        .describe("Ruta del ZIP, absoluta o relativa al data folder"))
                .handler(context -> {
                    Path zip = Path.of(context.requireArg(0));
                    if (!zip.isAbsolute()) {
                        zip = runtime.dataDir().resolve(zip);
                    }
                    List<ActivationResult> results = runtime.installLocal(zip, false);
                    if (results.isEmpty()) {
                        context.reply("El paquete se instaló pero no contenía módulos activables.");
                        return;
                    }
                    for (ActivationResult result : results) {
                        context.reply(result.isSuccess()
                                ? "Activado: " + result.moduleId()
                                : "Instalado pero no activado: " + result.moduleId()
                                        + " (" + result.message() + ")");
                    }
                })
                .build();
    }

    private static CommandSpec remove(SuiteRuntime runtime) {
        return CommandSpec.builder("remove")
                .description("Desactiva y elimina un paquete")
                .permission(PERMISSION)
                .usage("<pkg> [version] [canal]")
                .argument(CommandArgument.required("pkg"))
                .argument(CommandArgument.optional("version"))
                .argument(CommandArgument.optional("canal"))
                .handler(context -> {
                    String name = context.requireArg(0);
                    String version = context.arg(1) == null ? "any" : context.arg(1);
                    String channel = context.arg(2) == null ? "stable" : context.arg(2);
                    runtime.removeLocal(name, version, channel, false);
                    context.reply("Eliminado: " + name);
                })
                .build();
    }

    private static CommandSpec upgrade(SuiteRuntime runtime) {
        return CommandSpec.builder("upgrade")
                .description("Actualiza todos los paquetes")
                .permission(PERMISSION)
                .handler(context -> reportInstall(context, runtime.upgrade()))
                .build();
    }

    private static CommandSpec repository(SuiteRuntime runtime) {
        return CommandSpec.builder("repo")
                .description("Operaciones contra repositorios")
                .permission(PERMISSION)
                .usage("<install|remove|upgrade>")
                .child(repoInstall(runtime))
                .child(repoRemove(runtime))
                .child(repoUpgrade(runtime))
                .build();
    }

    private static CommandSpec repoInstall(SuiteRuntime runtime) {
        return CommandSpec.builder("install")
                .description("Instala paquetes desde repositorios")
                .permission(PERMISSION)
                .usage("<pkg...>")
                .argument(CommandArgument.required("packages"))
                .handler(context -> {
                    List<String> names = new ArrayList<>(context.requireArgs(1));
                    reportInstall(context,
                            runtime.installFromRepositories(names, "*", "stable", false));
                })
                .build();
    }

    private static CommandSpec repoRemove(SuiteRuntime runtime) {
        return CommandSpec.builder("remove")
                .description("Elimina un paquete desde repositorios")
                .permission(PERMISSION)
                .usage("<pkg> [version] [canal]")
                .argument(CommandArgument.required("pkg"))
                .argument(CommandArgument.optional("version"))
                .argument(CommandArgument.optional("canal"))
                .handler(context -> {
                    String name = context.requireArg(0);
                    String version = context.arg(1) == null ? "any" : context.arg(1);
                    String channel = context.arg(2) == null ? "stable" : context.arg(2);
                    AptInstaller.RemoveResult result =
                            runtime.removeFromRepositories(name, version, channel, false);
                    context.reply(result.success()
                            ? "Eliminado: " + result.packageName()
                            : "Error: " + result.error());
                })
                .build();
    }

    private static CommandSpec repoUpgrade(SuiteRuntime runtime) {
        return CommandSpec.builder("upgrade")
                .description("Actualiza paquetes desde repositorios")
                .permission(PERMISSION)
                .handler(context -> reportInstall(context, runtime.upgrade()))
                .build();
    }

    private static void reportInstall(CommandContext context, AptInstaller.InstallResult result) {
        if (result.requiresConfirmation()) {
            context.reply(result.confirmationMessage());
            return;
        }
        if (result.success()) {
            context.reply("Operación completada: " + result.packages());
            return;
        }
        context.reply("Error: " + result.error());
    }
}
