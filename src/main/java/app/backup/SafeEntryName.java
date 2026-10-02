package app.backup;

import java.util.Locale;
import java.util.Set;

/** Rules for names of entries inside a backup archive. Archive names are untrusted input. */
final class SafeEntryName {

    private static final int MAX_LENGTH = 1000;
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafeEntryName() { }

    /**
     * Returns the entry name without a trailing slash, or throws if it could escape the target folder
     * or confuse the file system (absolute paths, "..", backslashes, drive letters, device names).
     */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) throw unsafe(raw);
        String name = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        if (name.isEmpty() || name.startsWith("/")) throw unsafe(raw);

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\\' || c == ':' || Character.isISOControl(c)) throw unsafe(raw);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw unsafe(raw);
            if (segment.endsWith(".") || segment.endsWith(" ")) throw unsafe(raw);
            int dot = segment.indexOf('.');
            String base = dot >= 0 ? segment.substring(0, dot) : segment;
            if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) throw unsafe(raw);
        }
        return name;
    }

    private static BackupException unsafe(String raw) {
        String shown = raw == null ? "" : raw.length() > 60 ? raw.substring(0, 60) + "..." : raw;
        return new BackupException("The backup contains an unsafe file name (\"" + shown + "\"). "
                + "It was not used and nothing was changed.");
    }
}
