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

    /**
     * @param jar             module JAR, searched first
     * @param dependencyJars  shared dependency JARs, searched after the module
     * @param apiParent       parent that owns the neutral API contracts
     * @throws IOException if a path cannot be converted to a URL
     */
    public ModuleClassLoader(Path jar, List<Path> dependencyJars, ClassLoader apiParent) throws IOException {
        this(toUrls(jar, dependencyJars), apiParent);
    }

    private static URL[] toUrls(Path jar, List<Path> dependencyJars) throws IOException {
        URL[] urls = new URL[dependencyJars.size() + 1];
        urls[0] = jar.toUri().toURL();
        for (int i = 0; i < dependencyJars.size(); i++) {
            urls[i + 1] = dependencyJars.get(i).toUri().toURL();
        }
        return urls;
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
