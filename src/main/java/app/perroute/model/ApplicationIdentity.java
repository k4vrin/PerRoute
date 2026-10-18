package app.perroute.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Persistent macOS identities retain the exact executable path, independent of the host reading them. */
public final class ApplicationIdentity {
    public static final String MACOS_EXECUTABLE = "macos-executable:";
    private ApplicationIdentity() {}

    public static boolean isMac(String value) { return value != null && value.startsWith(MACOS_EXECUTABLE); }

    public static String macExecutable(String path) {
        if (path == null || !path.startsWith("/") || path.endsWith("/") || path.contains("\\") ||
                !StandardCharsets.UTF_8.newEncoder().canEncode(path) || path.getBytes(StandardCharsets.UTF_8).length > 8192 ||
                path.chars().anyMatch(Character::isISOControl) ||
                Arrays.stream(path.substring(1).split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))
            throw new IllegalArgumentException("Expected an exact macOS executable path");
        return MACOS_EXECUTABLE + path;
    }

    public static String path(String identity) {
        if (!isMac(identity)) return identity;
        String path = identity.substring(MACOS_EXECUTABLE.length());
        macExecutable(path);
        return path;
    }

    public static String comparisonKey(String identity) {
        return isMac(identity) ? identity : identity.toLowerCase(Locale.ROOT);
    }

    public static boolean same(String first, String second) {
        if (first == null || second == null) return first == second;
        return isMac(first) || isMac(second) ? first.equals(second) : first.equalsIgnoreCase(second);
    }
}
