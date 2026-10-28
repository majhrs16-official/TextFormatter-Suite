package me.majhrs16.suite.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.majhrs16.suite.api.message.Actor;
import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformPorts;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.AptInstaller;
import me.majhrs16.suite.manager.spi.PackageEntry;

/**
 * Neutral {@code suite} command exposed through {@link PlatformPorts}.
 *
 * <p>The adapter owns the platform command registration, permissions and
 * threading; this class only parses arguments and delegates to
 * {@link SuiteRuntime}. It is intentionally synchronous: an adapter that runs
 * commands on its main thread should dispatch long operations to its own
 * scheduler.</p>
 */
final class SuiteCommand implements PlatformPorts.Command {

    private static final String PERMISSION = "textformatter.suite.admin";

    private final SuiteRuntime runtime;
    private final PlatformPorts ports;
    private final PluginLogger logger;

    SuiteCommand(SuiteRuntime runtime, PlatformPorts ports, PluginLogger logger) {
        this.runtime = runtime;
        this.ports = ports;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "suite";
    }

    @Override
    public List<String> aliases() {
        return List.of("txf");
    }

    @Override
    public String description() {
        return "Gestiona paquetes y módulos de TextFormatter Suite";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(Actor sender, List<String> args) {
        try {
            executeChecked(sender, args);
        } catch (Exception failure) {
            logger.error("Suite command failed: " + failure.getMessage(), failure);
            reply(sender, "Error: " + failure.getMessage());
        }
    }

    private void executeChecked(Actor sender, List<String> args) throws Exception {
        if (args.isEmpty() || "help".equals(args.get(0))) {
            help(sender);
            return;
        }

        switch (args.get(0)) {
            case "list" -> list(sender);
            case "state" -> state(sender);
            case "install" -> install(sender, args);
            case "remove" -> remove(sender, args);
            case "upgrade" -> reportUpgrade(sender, runtime.upgrade());
            case "repo" -> repository(sender, args);
            default -> reply(sender, "Subcomando desconocido: " + args.get(0) + ". Usa /suite help.");
        }
    }

    private void help(Actor sender) {
        reply(sender, "Uso: /suite <list|state|install|remove|upgrade|repo>");
        reply(sender, "  list                         Paquetes instalados");
        reply(sender, "  state                        Estado runtime de los módulos");
        reply(sender, "  install <zip>                Instala y activa un ZIP local");
        reply(sender, "  remove <pkg> [ver] [canal]   Desactiva y elimina un paquete");
        reply(sender, "  upgrade                      Actualiza todos los paquetes");
        reply(sender, "  repo install <pkg...>        Instala desde repositorios");
        reply(sender, "  repo remove <pkg> [ver]      Elimina desde repositorios");
        reply(sender, "  repo upgrade                 Actualiza desde repositorios");
    }

    private void list(Actor sender) {
        List<PackageEntry> packages = runtime.manager().packageList();
        if (packages.isEmpty()) {
            reply(sender, "No hay paquetes instalados.");
            return;
        }
        Map<String, ModuleState> states = runtime.snapshot();
        for (PackageEntry entry : packages) {
            ModuleState state = states.get(entry.name());
            reply(sender, entry.name() + " " + entry.version() + " [" + entry.channel() + "] "
                    + entry.type() + (state == null ? "" : " -> " + state));
        }
    }

    private void state(Actor sender) {
        Map<String, ModuleState> states = runtime.snapshot();
        if (states.isEmpty()) {
            reply(sender, "No hay módulos conocidos.");
            return;
        }
        states.forEach((id, state) -> reply(sender, id + ": " + state));
    }

    private void install(Actor sender, List<String> args) throws Exception {
        if (args.size() < 2) {
            reply(sender, "Uso: /suite install <zip>");
            return;
        }
        Path zip = Path.of(args.get(1));
        if (!zip.isAbsolute()) {
            zip = runtime.dataDir().resolve(zip);
        }
        List<ActivationResult> results = runtime.installLocal(zip, false);
        if (results.isEmpty()) {
            reply(sender, "El paquete se instaló pero no contenía módulos activables.");
            return;
        }
        for (ActivationResult result : results) {
            reply(sender, result.isSuccess()
                    ? "Activado: " + result.moduleId()
                    : "Instalado pero no activado: " + result.moduleId() + " (" + result.message() + ")");
        }
    }

    private void remove(Actor sender, List<String> args) throws Exception {
        if (args.size() < 2) {
            reply(sender, "Uso: /suite remove <pkg> [version] [canal]");
            return;
        }
        String name = args.get(1);
        String version = args.size() > 2 ? args.get(2) : "any";
        String channel = args.size() > 3 ? args.get(3) : "stable";
        runtime.removeLocal(name, version, channel, false);
        reply(sender, "Eliminado: " + name);
    }

    private void repository(Actor sender, List<String> args) throws Exception {
        if (args.size() < 2) {
            reply(sender, "Uso: /suite repo <install|remove|upgrade> ...");
            return;
        }
        switch (args.get(1)) {
            case "install" -> {
                if (args.size() < 3) {
                    reply(sender, "Uso: /suite repo install <pkg...>");
                    return;
                }
                List<String> names = new ArrayList<>(args.subList(2, args.size()));
                reportInstall(sender, runtime.installFromRepositories(names, "*", "stable", false));
            }
            case "remove" -> {
                if (args.size() < 3) {
                    reply(sender, "Uso: /suite repo remove <pkg> [version] [canal]");
                    return;
                }
                String name = args.get(2);
                String version = args.size() > 3 ? args.get(3) : "any";
                String channel = args.size() > 4 ? args.get(4) : "stable";
                AptInstaller.RemoveResult result =
                        runtime.removeFromRepositories(name, version, channel, false);
                reply(sender, result.success()
                        ? "Eliminado: " + result.packageName()
                        : "Error: " + result.error());
            }
            case "upgrade" -> reportUpgrade(sender, runtime.upgrade());
            default -> reply(sender, "Subcomando repo desconocido: " + args.get(1));
        }
    }

    private void reportUpgrade(Actor sender, AptInstaller.InstallResult result) {
        reportInstall(sender, result);
    }

    private void reportInstall(Actor sender, AptInstaller.InstallResult result) {
        if (result.requiresConfirmation()) {
            reply(sender, result.confirmationMessage());
            return;
        }
        if (result.success()) {
            reply(sender, "Operación completada: " + result.packages());
            return;
        }
        reply(sender, "Error: " + result.error());
    }

    private void reply(Actor sender, String message) {
        ports.send(sender, message);
    }
}
