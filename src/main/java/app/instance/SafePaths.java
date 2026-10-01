package app.instance;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Path safety helpers. Every write/delete inside an instance goes through these. */
public final class SafePaths {

    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final int MAX_LENGTH = 48;

    private SafePaths() { }

    /**
     * Turns a display name into a safe single folder name:
     * "Fabric 1.21.8" -> "Fabric-1.21.8". Never contains separators, never empty,
     * never a Windows reserved device name, never starts or ends with a dot.
     */
    public static String toFolderName(String name) {
        StringBuilder sb = new StringBuilder();
        boolean lastWasDash = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            char out;
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_') {
                out = c;
            } else {
                out = '-'; // spaces, slashes, colons, quotes... all become a dash
            }
            if (out == '-') {
                if (lastWasDash) continue;
                lastWasDash = true;
            } else {
                lastWasDash = false;
            }
            sb.append(out);
        }
        String result = trimEdges(sb.toString());
        if (result.length() > MAX_LENGTH) result = trimEdges(result.substring(0, MAX_LENGTH));
        if (result.isEmpty()) result = "instance";

        String base = result;
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) result = result + "-instance";
        return result;
    }

    /** True if the text is exactly what {@link #toFolderName} would produce, i.e. a safe folder name. */
    public static boolean isSafeFolderName(String id) {
        return id != null && !id.isEmpty() && id.equals(toFolderName(id));
    }

    /**
     * Resolves {@code child} against {@code root} and makes sure the result is still
     * inside {@code root} (protects against "..", absolute paths, and similar tricks).
     */
    public static Path resolveInside(Path root, String child) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(child).normalize();
        requireInside(normalizedRoot, resolved);
        return resolved;
    }

    public static void requireInside(Path root, Path target) {
        Path r = root.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (!t.startsWith(r) || t.equals(r)) {
            throw new InstanceException("Refusing to touch a path outside the instances folder.");
        }
    }

    private static String trimEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isEdgeChar(s.charAt(start))) start++;
        while (end > start && isEdgeChar(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isEdgeChar(char c) {
        return c == '.' || c == '-' || c == ' ';
    }
}
