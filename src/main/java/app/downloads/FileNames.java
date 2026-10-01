package app.downloads;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Safe file names for downloaded files. */
public final class FileNames {

    private static final int MAX_LENGTH = 120;

    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** Programs and scripts are never written by the downloader. (.jar is allowed: mods are jars.) */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "com", "scr", "msi", "ps1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "lnk", "sh", "reg", "dll", "cpl", "hta", "pif");

    private FileNames() { }

    /** Keeps letters, digits and . _ - + ( ) space; everything else becomes an underscore. */
    public static String sanitize(String raw) {
        String input = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'
                    || c == '+' || c == '(' || c == ')' || c == ' ';
            sb.append(ok ? c : '_');
        }
        String name = trimEdges(sb.toString());
        if (name.isEmpty()) return "download";

        if (name.length() > MAX_LENGTH) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 12) ? name.substring(dot) : "";
            name = trimEdges(name.substring(0, MAX_LENGTH - ext.length())) + ext;
        }
        int firstDot = name.indexOf('.');
        String base = firstDot >= 0 ? name.substring(0, firstDot) : name;
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) name = "_" + name;
        return name;
    }

    /** Last part of the URL path, made safe. */
    public static String fromUrl(URI url) {
        String path = url.getPath();
        if (path == null || path.isBlank() || path.equals("/")) return "download";
        String last = path.substring(path.lastIndexOf('/') + 1);
        return sanitize(last);
    }

    public static boolean hasBlockedExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return false;
        return BLOCKED_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String trimEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '.' || s.charAt(start) == ' ')) start++;
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        return s.substring(start, end);
    }
}
