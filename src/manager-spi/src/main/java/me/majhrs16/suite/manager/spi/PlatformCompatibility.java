package me.majhrs16.suite.manager.spi;

import java.util.Locale;

/**
 * Platform compatibility rules shared by the package resolver and the runtime.
 *
 * <p>A package declares its platform in {@code package.list}. {@code common},
 * {@code any} and blank values match every host. Bukkit-derived servers report
 * the neutral adapter name {@code spigot}, so {@code bukkit}, {@code paper} and
 * {@code purpur} are treated as the same family.</p>
 */
public final class PlatformCompatibility {

    private PlatformCompatibility() {
    }

    /**
     * @param declared platform declared by the package
     * @param current  platform reported by the host
     * @return {@code true} when the package may run on this host
     */
    public static boolean matches(String declared, String current) {
        String packagePlatform = normalize(declared);
        if (packagePlatform.isEmpty() || "common".equals(packagePlatform) || "any".equals(packagePlatform)) {
            return true;
        }
        return packagePlatform.equals(normalize(current));
    }

    /** @return canonical lowercase platform family name. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "bukkit", "paper", "purpur", "spigot" -> "spigot";
            default -> normalized;
        };
    }
}
