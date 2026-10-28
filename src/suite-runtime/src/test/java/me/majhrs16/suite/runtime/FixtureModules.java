package me.majhrs16.suite.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import me.majhrs16.suite.api.spi.PluginLogger;

/**
 * Builds real module JARs and distribution ZIPs for runtime tests.
 *
 * <p>Fixtures are compiled at test time so their classes exist only inside the
 * generated JAR, never on the test classpath.</p>
 */
final class FixtureModules {

    private FixtureModules() { }

    static Path buildJar(Path workDir, String moduleId, String version, String payload,
                         String provides, String requires, boolean failOnStart,
                         boolean appendOrder) throws IOException {
        return buildJar(workDir, moduleId, version, payload, provides, requires,
                failOnStart, appendOrder, false);
    }

    /**
     * Builds a module whose {@code stop()} throws, to exercise the
     * {@code RESTART_REQUIRED} path of the host.
     */
    static Path buildJarFailingOnStop(Path workDir, String moduleId, String version,
                                      String payload) throws IOException {
        return buildJar(workDir, moduleId, version, payload, null, null, false, false, true);
    }

    private static Path buildJar(Path workDir, String moduleId, String version, String payload,
                                 String provides, String requires, boolean failOnStart,
                                 boolean appendOrder, boolean failOnStop) throws IOException {
        Path sourceDir = workDir.resolve("fixture-src-" + moduleId);
        Path classesDir = workDir.resolve("fixture-classes-" + moduleId);
        Path jar = workDir.resolve("fixture-" + moduleId + ".jar");
        Path source = sourceDir.resolve("fixture").resolve("Activator.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);

        String provideLine = provides == null ? ""
                : "builder.provide(new Capability(\"" + provides + "\", SemVer.of(1, 0, 0)));";
        String requireLine = requires == null ? ""
                : "builder.require(Requirement.atLeast(\"" + requires + "\", SemVer.of(1, 0, 0)));";
        String failLine = failOnStart ? "throw new IllegalStateException(\"fixture failure\");" : "";
        String stopLine = failOnStop ? "throw new IllegalStateException(\"fixture stop failure\");" : "";
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
                                    .version(SemVer.parse("MODULE_VERSION"))
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
                    public void stop() throws Exception {
                        STOP_LINE
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
                .replace("MODULE_VERSION", version)
                .replace("PAYLOAD", payload)
                .replace("PROVIDE_LINE", provideLine)
                .replace("REQUIRE_LINE", requireLine)
                .replace("ORDER_LINE", orderLine)
                .replace("STOP_LINE", stopLine)
                .replace("FAIL_LINE", failLine);

        Files.writeString(source, java, StandardCharsets.UTF_8);

        compile(source, classesDir, List.of());
        writeJar(classesDir, jar, "fixture.Activator\n");
        return jar;
    }

    static Path buildDependencyJar(Path workDir, String className, String value) throws IOException {
        int dot = className.lastIndexOf('.');
        String packageName = dot < 0 ? "" : className.substring(0, dot);
        String simpleName = dot < 0 ? className : className.substring(dot + 1);

        Path sourceDir = workDir.resolve("dep-src-" + className);
        Path classesDir = workDir.resolve("dep-classes-" + className);
        Path jar = workDir.resolve("dep-" + className + ".jar");
        Path source = sourceDir.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);

        String java = (packageName.isEmpty() ? "" : "package " + packageName + ";\n\n")
                + "public final class " + simpleName + " {\n"
                + "    public static String value() {\n"
                + "        return \"" + value + "\";\n"
                + "    }\n"
                + "}\n";
        Files.writeString(source, java, StandardCharsets.UTF_8);
        compile(source, classesDir, List.of());
        writeJar(classesDir, jar, null);
        return jar;
    }

    static Path buildJarUsingDependency(Path workDir, String moduleId, String version,
                                        Path dependencyJar, String dependencyClass,
                                        String dependencyField) throws IOException {
        Path sourceDir = workDir.resolve("fixture-src-" + moduleId);
        Path classesDir = workDir.resolve("fixture-classes-" + moduleId);
        Path jar = workDir.resolve("fixture-" + moduleId + ".jar");
        Path source = sourceDir.resolve("fixture").resolve("Activator.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);

        String java = """
                package fixture;

                import me.majhrs16.suite.api.ModuleDescriptor;
                import me.majhrs16.suite.api.SemVer;
                import me.majhrs16.suite.api.runtime.ModuleActivator;
                import me.majhrs16.suite.api.runtime.ModuleContext;

                public final class Activator implements ModuleActivator {

                    @Override
                    public ModuleDescriptor descriptor() {
                        return ModuleDescriptor.builder("MODULE_ID")
                                .version(SemVer.parse("MODULE_VERSION"))
                                .contractVersion(SemVer.of(2, 1, 0))
                                .jvmRange(17, 0)
                                .build();
                    }

                    @Override
                    public void start(ModuleContext context) {
                        context.services().register(CharSequence.class, DEPENDENCY_CLASS.DEPENDENCY_FIELD);
                    }

                    @Override
                    public void stop() {
                    }
                }
                """;
        java = java
                .replace("MODULE_ID", moduleId)
                .replace("MODULE_VERSION", version)
                .replace("DEPENDENCY_CLASS", dependencyClass)
                .replace("DEPENDENCY_FIELD", dependencyField);
        Files.writeString(source, java, StandardCharsets.UTF_8);

        compile(source, classesDir, List.of(dependencyJar));
        writeJar(classesDir, jar, "fixture.Activator\n");
        return jar;
    }

    static Path buildSinkJar(Path workDir, String moduleId, String version,
                             String sinkName) throws IOException {
        Path sourceDir = workDir.resolve("fixture-src-" + moduleId);
        Path classesDir = workDir.resolve("fixture-classes-" + moduleId);
        Path jar = workDir.resolve("fixture-" + moduleId + ".jar");
        Path source = sourceDir.resolve("fixture").resolve("Activator.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classesDir);

        String java = """
                package fixture;

                import me.majhrs16.suite.api.ModuleDescriptor;
                import me.majhrs16.suite.api.Requirement;
                import me.majhrs16.suite.api.SemVer;
                import me.majhrs16.suite.api.message.Message;
                import me.majhrs16.suite.api.runtime.ModuleActivator;
                import me.majhrs16.suite.api.runtime.ModuleContext;
                import me.majhrs16.suite.api.spi.SyncBus;
                import me.majhrs16.suite.api.spi.SyncListener;
                import me.majhrs16.suite.api.spi.SyncSink;

                public final class Activator implements ModuleActivator {

                    private SyncBus bus;
                    private String sinkName;

                    @Override
                    public ModuleDescriptor descriptor() {
                        return ModuleDescriptor.builder("MODULE_ID")
                                .version(SemVer.parse("MODULE_VERSION"))
                                .contractVersion(SemVer.of(2, 1, 0))
                                .jvmRange(17, 0)
                                .require(Requirement.atLeast("sync-bus", SemVer.of(2, 1, 0)))
                                .build();
                    }

                    @Override
                    public void start(ModuleContext context) {
                        SyncBus resolved = context.services().find(SyncBus.class)
                                .orElseThrow(() -> new IllegalStateException("sync-bus service missing"));
                        FixtureSink sink = new FixtureSink("SINK_NAME");
                        resolved.register(sink);
                        this.bus = resolved;
                        this.sinkName = sink.name();
                    }

                    @Override
                    public void stop() {
                        if (bus != null) {
                            bus.unregister(sinkName);
                            bus = null;
                        }
                    }

                    static final class FixtureSink implements SyncSink {

                        private final String name;

                        FixtureSink(String name) {
                            this.name = name;
                        }

                        @Override public String name() { return name; }
                        @Override public DeliverySemantics deliverySemantics() {
                            return DeliverySemantics.AT_LEAST_ONCE;
                        }
                        @Override public Ordering ordering() { return Ordering.PER_CHANNEL; }
                        @Override public void start() { }
                        @Override public void stop() { }
                        @Override public void send(Message message) { }
                        @Override public void setListener(SyncListener listener) { }
                    }
                }
                """;
        java = java
                .replace("MODULE_ID", moduleId)
                .replace("MODULE_VERSION", version)
                .replace("SINK_NAME", sinkName);
        Files.writeString(source, java, StandardCharsets.UTF_8);

        compile(source, classesDir, List.of());
        writeJar(classesDir, jar, "fixture.Activator\n");
        return jar;
    }

    private static void compile(Path source, Path classesDir, List<Path> extraClasspath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringBuilder classpath = new StringBuilder(System.getProperty("java.class.path"));
        for (Path entry : extraClasspath) {
            classpath.append(File.pathSeparatorChar).append(entry);
        }

        List<String> args = new ArrayList<>();
        args.add("-classpath");
        args.add(classpath.toString());
        args.add("-d");
        args.add(classesDir.toString());
        args.add(source.toString());
        int exit = compiler.run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, exit, "fixture compilation failed");
    }

    private static void writeJar(Path classesDir, Path jar, String serviceClass) throws IOException {
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
            if (serviceClass != null) {
                jarOut.putNextEntry(new JarEntry(
                        "META-INF/services/me.majhrs16.suite.api.runtime.ModuleActivator"));
                jarOut.write(serviceClass.getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
    }

    static Path buildPackageZip(Path workDir, String packageName, String version, String channel,
                                Path moduleJar, String dependencies) throws IOException {
        return buildPackageZip(workDir, packageName, version, channel, moduleJar, dependencies,
                "PACKAGE", "manager/library/packages/" + packageName + ".jar");
    }

    static Path buildDependencyPackageZip(Path workDir, String packageName, String version,
                                          String channel, Path dependencyJar) throws IOException {
        return buildPackageZip(workDir, packageName, version, channel, dependencyJar, null,
                "DEPENDENCY", "manager/library/dependencies/" + packageName + ".jar");
    }

    private static Path buildPackageZip(Path workDir, String packageName, String version,
                                        String channel, Path moduleJar, String dependencies,
                                        String type, String jarPath) throws IOException {
        byte[] jarBytes = Files.readAllBytes(moduleJar);
        String jarSha = sha256(jarBytes);
        String deps = dependencies == null ? "" : dependencies;

        String manifest = "Name: " + packageName + "\n"
                + "Version: " + version + "\n"
                + "Channel: " + channel + "\n"
                + "Type: " + type + "\n"
                + "Dependencies: " + deps + "\n"
                + "Arch: none\n"
                + "Platform: common\n"
                + "URL: \n"
                + "OS: none\n"
                + "SHA256: \n"
                + "Size: 0\n"
                + "Files: \n"
                + "  " + jarPath + "\t" + jarBytes.length + "\t" + jarSha + "\t" + packageName;

        Path zip = workDir.resolve("package-" + packageName + ".zip");
        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(zip))) {
            zipOut.putNextEntry(new ZipEntry("METADATA/package.list"));
            zipOut.write(manifest.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            zipOut.putNextEntry(new ZipEntry(jarPath));
            zipOut.write(jarBytes);
            zipOut.closeEntry();
        }
        return zip;
    }

    static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void info(String message, Object... args) { }
            @Override public void warn(String message, Object... args) { }
            @Override public void error(String message, Object... args) { }
            @Override public void error(String message, Throwable throwable) { }
            @Override public void debug(String message, Object... args) { }
        };
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }
}
