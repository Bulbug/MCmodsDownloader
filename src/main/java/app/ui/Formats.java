package app.ui;

import java.util.Locale;

/** Text formatting for sizes, speeds and times. No JavaFX here, so it is easy to test. */
public final class Formats {

    private Formats() { }

    public static String bytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    public static String speed(double bytesPerSecond) {
        return bytesPerSecond < 1 ? "-" : bytes((long) bytesPerSecond) + "/s";
    }

    public static String eta(long seconds) {
        if (seconds < 0) return "-";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }
}
