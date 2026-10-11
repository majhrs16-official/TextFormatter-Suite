package me.majhrs16.suite.manager.apt;

import me.majhrs16.suite.manager.core.LocalPackageDatabase;
import me.majhrs16.suite.manager.core.PackageInstaller;
import me.majhrs16.suite.manager.spi.ManifestParser;
import me.majhrs16.suite.manager.spi.PackageEntry;
import me.majhrs16.suite.manager.spi.PackageType;
import me.majhrs16.suite.manager.spi.Version;
import me.majhrs16.suite.api.spi.PluginLogger;
import me.majhrs16.suite.manager.apt.RepositoryManager.PackageCandidate;
import me.majhrs16.suite.manager.apt.DependencyResolver.PlanStep;
import me.majhrs16.suite.manager.apt.DependencyResolver.ResolvedPackage;
import me.majhrs16.suite.manager.apt.DependencyResolver.ResolutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    @TempDir
    Path tempDir;

    private PluginLogger createLogger() {
        return new PluginLogger() {
            @Override public void info(String m, Object... a) {}
            @Override public void warn(String m, Object... a) {}
            @Override public void error(String m, Object... a) {}
            @Override public void error(String m, Throwable t) {}
            @Override public void debug(String m, Object... a) {}
        };
    }

    private DependencyResolver createResolver() throws Exception {
        LocalPackageDatabase db = new LocalPackageDatabase(tempDir);
        RepositoryManager repoManager = new RepositoryManager(db, Collections.emptyList(), createLogger());
        PackageInstaller installer = new PackageInstaller(db, tempDir.resolve("install"), createLogger());
        return new DependencyResolver(repoManager, db, installer, createLogger(), "spigot", "x86_64", "linux", 21);
    }

    private PackageCandidate candidate(String name, String version, String channel, List<String> deps) {
        return new PackageCandidate(name, version, channel, "test-repo",
            "https://example.com/" + name + "-" + version + ".zip", 1000L, "sha256-" + name, 10);
    }

    @Test
    void buildInstallPlan_simpleDependency_dependencyFirst() throws Exception {
        DependencyResolver resolver = createResolver();
        
        PackageCandidate b = candidate("lib-b", "1.0.0", "stable", Collections.emptyList());
        PackageCandidate a = candidate("app-a", "1.0.0", "stable", Collections.singletonList("lib-b"));
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        selected.put("app-a", new ResolvedPackage(a, List.of("lib-b")));
        selected.put("lib-b", new ResolvedPackage(b, Collections.emptyList()));
        
        java.lang.reflect.Method method = DependencyResolver.class.getDeclaredMethod("buildInstallPlan", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlanStep> steps = (List<PlanStep>) method.invoke(resolver, selected);
        
        assertEquals(2, steps.size());
        assertEquals("lib-b", steps.get(0).candidate().name(), "Dependency should be installed first");
        assertEquals("app-a", steps.get(1).candidate().name(), "Dependent should be installed second");
    }

    @Test
    void buildInstallPlan_transitiveDependency_allDependenciesFirst() throws Exception {
        DependencyResolver resolver = createResolver();
        
        PackageCandidate c = candidate("lib-c", "1.0.0", "stable", Collections.emptyList());
        PackageCandidate b = candidate("lib-b", "1.0.0", "stable", Collections.singletonList("lib-c"));
        PackageCandidate a = candidate("app-a", "1.0.0", "stable", Collections.singletonList("lib-b"));
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        selected.put("app-a", new ResolvedPackage(a, List.of("lib-b")));
        selected.put("lib-b", new ResolvedPackage(b, List.of("lib-c")));
        selected.put("lib-c", new ResolvedPackage(c, Collections.emptyList()));
        
        java.lang.reflect.Method method = DependencyResolver.class.getDeclaredMethod("buildInstallPlan", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlanStep> steps = (List<PlanStep>) method.invoke(resolver, selected);
        
        assertEquals(3, steps.size());
        assertEquals("lib-c", steps.get(0).candidate().name());
        assertEquals("lib-b", steps.get(1).candidate().name());
        assertEquals("app-a", steps.get(2).candidate().name());
    }

    @Test
    void buildInstallPlan_diamondDependency_sharedDependencyOnce() throws Exception {
        DependencyResolver resolver = createResolver();
        
        PackageCandidate d = candidate("lib-d", "1.0.0", "stable", Collections.emptyList());
        PackageCandidate b = candidate("lib-b", "1.0.0", "stable", Collections.singletonList("lib-d"));
        PackageCandidate c = candidate("lib-c", "1.0.0", "stable", Collections.singletonList("lib-d"));
        PackageCandidate a = candidate("app-a", "1.0.0", "stable", Arrays.asList("lib-b", "lib-c"));
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        selected.put("app-a", new ResolvedPackage(a, Arrays.asList("lib-b", "lib-c")));
        selected.put("lib-b", new ResolvedPackage(b, List.of("lib-d")));
        selected.put("lib-c", new ResolvedPackage(c, List.of("lib-d")));
        selected.put("lib-d", new ResolvedPackage(d, Collections.emptyList()));
        
        java.lang.reflect.Method method = DependencyResolver.class.getDeclaredMethod("buildInstallPlan", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlanStep> steps = (List<PlanStep>) method.invoke(resolver, selected);
        
        assertEquals(4, steps.size());
        assertEquals("lib-d", steps.get(0).candidate().name(), "Shared dependency should be first");
        assertEquals("app-a", steps.get(3).candidate().name(), "Top-level app should be last");
        Set<String> middle = new HashSet<>(Arrays.asList(steps.get(1).candidate().name(), steps.get(2).candidate().name()));
        assertEquals(Set.of("lib-b", "lib-c"), middle);
    }

    @Test
    void buildInstallPlan_cycle_throws() throws Exception {
        DependencyResolver resolver = createResolver();
        
        PackageCandidate a = candidate("app-a", "1.0.0", "stable", Collections.singletonList("lib-b"));
        PackageCandidate b = candidate("lib-b", "1.0.0", "stable", Collections.singletonList("app-a"));
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        selected.put("app-a", new ResolvedPackage(a, List.of("lib-b")));
        selected.put("lib-b", new ResolvedPackage(b, List.of("app-a")));
        
        java.lang.reflect.Method method = DependencyResolver.class.getDeclaredMethod("buildInstallPlan", Map.class);
        method.setAccessible(true);
        
        Exception ex = assertThrows(Exception.class, () -> method.invoke(resolver, selected));
        assertTrue(ex.getCause() instanceof ResolutionException);
        assertTrue(ex.getCause().getMessage().contains("cycle"));
    }

    @Test
    void buildInstallPlan_independentPackages_anyOrder() throws Exception {
        DependencyResolver resolver = createResolver();
        
        PackageCandidate a = candidate("app-a", "1.0.0", "stable", Collections.emptyList());
        PackageCandidate b = candidate("app-b", "1.0.0", "stable", Collections.emptyList());
        
        Map<String, ResolvedPackage> selected = new LinkedHashMap<>();
        selected.put("app-a", new ResolvedPackage(a, Collections.emptyList()));
        selected.put("app-b", new ResolvedPackage(b, Collections.emptyList()));
        
        java.lang.reflect.Method method = DependencyResolver.class.getDeclaredMethod("buildInstallPlan", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<PlanStep> steps = (List<PlanStep>) method.invoke(resolver, selected);
        
        assertEquals(2, steps.size());
        Set<String> names = new HashSet<>();
        steps.forEach(s -> names.add(s.candidate().name()));
        assertEquals(Set.of("app-a", "app-b"), names);
    }
}