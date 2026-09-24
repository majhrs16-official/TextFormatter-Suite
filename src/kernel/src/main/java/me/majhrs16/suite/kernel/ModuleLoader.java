package me.majhrs16.suite.kernel;

import me.majhrs16.suite.api.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers candidate modules via {@link ServiceLoader} on a class loader.
 * Every module jar registers {@code META-INF/services/me.majhrs16.suite.api.Module}
 * with at least one {@link Module} implementation.
 */
public final class ModuleLoader {

    private ModuleLoader() {
    }

    public static List<Module> discover(ClassLoader loader) {
        List<Module> modules = new ArrayList<>();
        ServiceLoader.load(Module.class, loader).forEach(modules::add);
        return modules;
    }

    public static List<Module> discover() {
        return discover(ModuleLoader.class.getClassLoader());
    }
}