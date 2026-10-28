package me.majhrs16.suite.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Isolated classloader for one installed module JAR.
 *
 * <p>API contracts and the JDK are loaded parent-first so a module and the
 * runtime share the exact same {@code ModuleActivator} type. Everything else
 * is loaded child-first, which lets two versions of the same module coexist in
 * different classloaders during a hot upgrade and lets a module ship its own
 * relocated dependency packages.</p>
 */
public final class ModuleClassLoader extends URLClassLoader {

    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "me.majhrs16.suite.api.");

    public ModuleClassLoader(Path jar, ClassLoader apiParent) throws IOException {
        this(new URL[]{jar.toUri().toURL()}, apiParent);
    }

    public ModuleClassLoader(URL[] urls, ClassLoader apiParent) {
        super("suite-module", urls, apiParent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                if (isParentFirst(name)) {
                    loaded = super.loadClass(name, false);
                } else {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException notInModule) {
                        loaded = super.loadClass(name, false);
                    }
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static boolean isParentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
