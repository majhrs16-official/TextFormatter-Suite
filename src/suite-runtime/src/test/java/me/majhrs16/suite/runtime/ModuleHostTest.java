package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import me.majhrs16.suite.api.runtime.ModuleState;
import me.majhrs16.suite.api.runtime.PlatformInfo;
import me.majhrs16.suite.api.spi.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F2 gate: real module JARs are compiled, packaged and loaded from disk.
 *
 * <p>The fixtures are generated at test time so the classes only exist inside
 * the JAR. That proves activation does not depend on the test classpath.</p>
 */
class ModuleHostTest {

    @TempDir
    Path tempDir;

    @Test
    void activatesTwoVersionsOfTheSameClassInIsolatedClassLoaders() throws Exception {
        Path v1 = buildJar("fixture-v1", "1.0.0", "v1", null, null, false, false);
        Path v2 = buildJar("fixture-v2", "2.0.0", "v2", null, null, false, false);

        try (ModuleHost host = newHost()) {
            assertTrue(host.activate(v1).isSuccess());
            assertTrue(host.activate(v2).isSuccess());

            List<CharSequence> payloads = host.services().findAll(CharSequence.class);
            assertEquals(2, payloads.size());
            assertEquals(Set.of("v1", "v2"),
                    payloads.stream().map(CharSequence::toString).collect(Collectors.toSet()));
            assertNotSame(payloads.get(0).getClass(), payloads.get(1).getClass(),
                    "each module class must come from its own classloader");

            assertEquals(Map.of(
                    "fixture-v1", ModuleState.ACTIVE,
                    "fixture-v2", ModuleState.ACTIVE), host.snapshot());
            assertFalse(Files.exists(tempDir.resolve("config").resolve("fixture-v1").resolve("released.marker")));

            DeactivationResult result = host.deactivate("fixture-v1");

            assertTrue(result.isClean());
            assertTrue(Files.exists(tempDir.resolve("config").resolve("fixture-v1").resolve("released.marker")),
                    "tracked resources must be released on deactivation");
            assertEquals(ModuleState.INSTALLED, host.state("fixture-v1").orElseThrow());
            assertEquals(List.of("fixture-v2"), host.activeModules());
            assertEquals(1, host.services().findAll(CharSequence.class).size(),
                    "deactivation must remove the services owned by the module");
        }
    }

    @Test
    void loadInstalledActivatesInDependencyOrder() throws Exception {
        Path base = buildJar("z-base", "1.0.0", "base", "base", null, false, true);
        Path consumer = buildJar("a-consumer", "1.0.0", "consumer", null, "base", false, true);

        Path packages = tempDir.resolve("library").resolve("packages");
        Files.createDirectories(packages);
        Files.copy(consumer, packages.resolve("a-consumer.jar"));
        Files.copy(base, packages.resolve("z-base.jar"));

        try (ModuleHost host = newHost()) {
            List<ActivationResult> results = host.loadInstalled();

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(ActivationResult::isSuccess));
            assertEquals(List.of("z-base", "a-consumer"),
                    Files.readAllLines(tempDir.resolve("config").resolve("activation-order.log")));
            assertEquals(ModuleState.ACTIVE, host.state("a-consumer").orElseThrow());
        }
    }

    @Test
    void failedActivationIsReportedAndRollsBackServices() throws Exception {
        Path broken = buildJar("broken", "1.0.0", "broken", null, null, true, false);

        try (ModuleHost host = newHost()) {
            ActivationResult result = host.activate(broken);

            assertFalse(result.isSuccess());
            assertEquals(ModuleState.FAILED, result.state());
            assertEquals(ModuleState.FAILED, host.state("broken").orElseThrow());
            assertTrue(host.activeModules().isEmpty());
            assertTrue(host.services().findAll(CharSequence.class).isEmpty());
        }
    }

    @Test
    void unsatisfiedRequirementIsNotActivated() throws Exception {
        Path orphan = buildJar("orphan", "1.0.0", "orphan", null, "missing", false, false);

        Path packages = tempDir.resolve("library").resolve("packages");
        Files.createDirectories(packages);
        Files.copy(orphan, packages.resolve("orphan.jar"));

        try (ModuleHost host = newHost()) {
            List<ActivationResult> results = host.loadInstalled();

            assertEquals(1, results.size());
            assertFalse(results.get(0).isSuccess());
            assertEquals(ModuleState.FAILED, host.state("orphan").orElseThrow());
            assertTrue(host.activeModules().isEmpty());
        }
    }

    private ModuleHost newHost() {
        return new ModuleHost(
                tempDir.resolve("library"),
                tempDir.resolve("config"),
                PlatformInfo.current("test", "1.0"),
                new RecordingLogger());
    }

    /**
     * Compiles and packages a fixture module JAR.
     */
    private Path buildJar(String moduleId, String version, String payload,
                          String provides, String requires, boolean failOnStart,
                          boolean appendOrder) throws IOException {
        Path sourceDir = tempDir.resolve("fixture-src-" + moduleId);
        Path classesDir = tempDir.resolve("fixture-classes-" + moduleId);
        Path jar = tempDir.resolve("fixture-" + moduleId + ".jar");
        Path source = sourceDir.resolve("fixture").resolve("Activator.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);

        String provideLine = provides == null ? ""
                : "builder.provide(new Capability(\"" + provides + "\", SemVer.of(1, 0, 0)));";
        String requireLine = requires == null ? ""
                : "builder.require(Requirement.atLeast(\"" + requires + "\", SemVer.of(1, 0, 0)));";
        String failLine = failOnStart ? "throw new IllegalStateException(\"fixture failure\");" : "";
        String orderLine = appendOrder
                ? "Files.writeString(context.configDir().resolveSibling(\"activation-order.log\"),"
                    + " \"" + moduleId + "\\n\", StandardOpenOption.CREATE, StandardOpenOption.APPEND);"
                : "";

        String java = """
                package fixture;

                import java.nio.file.Files;
                import java.nio.file.StandardOpenOption;
                import me.majhrs16.suite.api.Capability;
                import me.majhrs16.suite.api.ModuleDescriptor;
                import me.majhrs16.suite.api.Requirement;
                import me.majhrs16.suite.api.SemVer;
                import me.majhrs16.suite.api.runtime.ModuleActivator;
                import me.majhrs16.suite.api.runtime.ModuleContext;

                public final class Activator implements ModuleActivator {

                    private ModuleDescriptor descriptor;

                    @Override
                    public ModuleDescriptor descriptor() {
                        if (descriptor == null) {
                            ModuleDescriptor.Builder builder = ModuleDescriptor.builder("MODULE_ID")
                                    .version(SemVer.of(1, 0, 0))
                                    .contractVersion(SemVer.of(2, 1, 0))
                                    .jvmRange(17, 0);
                            PROVIDE_LINE
                            REQUIRE_LINE
                            descriptor = builder.build();
                        }
                        return descriptor;
                    }

                    @Override
                    public void start(ModuleContext context) throws Exception {
                        context.services().register(CharSequence.class, new Payload("PAYLOAD"));
                        context.resources().track(new AutoCloseable() {
                            @Override
                            public void close() throws Exception {
                                Files.writeString(context.configDir().resolve("released.marker"), "released");
                            }
                        });
                        ORDER_LINE
                        FAIL_LINE
                    }

                    @Override
                    public void stop() {
                    }

                    public static final class Payload implements CharSequence {

                        private final String value;

                        public Payload(String value) {
                            this.value = value;
                        }

                        @Override public int length() { return value.length(); }
                        @Override public char charAt(int index) { return value.charAt(index); }
                        @Override public CharSequence subSequence(int start, int end) {
                            return value.subSequence(start, end);
                        }
                        @Override public String toString() { return value; }
                    }
                }
                """;

        java = java
                .replace("MODULE_ID", moduleId)
                .replace("PAYLOAD", payload)
                .replace("PROVIDE_LINE", provideLine)
                .replace("REQUIRE_LINE", requireLine)
                .replace("ORDER_LINE", orderLine)
                .replace("FAIL_LINE", failLine);

        Files.writeString(source, java, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>();
        args.add("-classpath");
        args.add(System.getProperty("java.class.path"));
        args.add("-d");
        args.add(classesDir.toString());
        args.add(source.toString());
        int exit = compiler.run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, exit, "fixture compilation failed");

        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            List<Path> compiled;
            try (Stream<Path> walk = Files.walk(classesDir)) {
                compiled = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : compiled) {
                String name = classesDir.relativize(file).toString().replace(File.separatorChar, '/');
                jarOut.putNextEntry(new JarEntry(name));
                Files.copy(file, jarOut);
                jarOut.closeEntry();
            }
            jarOut.putNextEntry(new JarEntry(
                    "META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator"));
            jarOut.write("fixture.Activator\n".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return jar;
    }

    private static final class RecordingLogger implements PluginLogger {

        @Override public void info(String message, Object... args) { }
        @Override public void warn(String message, Object... args) { }
        @Override public void error(String message, Object... args) { }
        @Override public void error(String message, Throwable throwable) { }
        @Override public void debug(String message, Object... args) { }
    }
}
