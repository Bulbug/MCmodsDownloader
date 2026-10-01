package app.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Central definition of every folder the application uses. */
public final class AppPaths {

    private static final String APP_DIR_NAME = "MinecraftManager";

    private final Path root;

    public AppPaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Picks the conventional per-user data folder for the current OS. */
    public static AppPaths defaultLocation() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
        } else if (os.contains("mac")) {
            base = Path.of(home, "Library", "Application Support");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".local", "share");
        }
        return new AppPaths(base.resolve(APP_DIR_NAME));
    }

    public Path root()          { return root; }
    public Path instancesDir()  { return root.resolve("instances"); }
    public Path downloadsDir()  { return root.resolve("downloads"); }
    public Path backupsDir()    { return root.resolve("backups"); }
    public Path cacheDir()      { return root.resolve("cache"); }
    public Path logsDir()       { return root.resolve("logs"); }
    public Path settingsFile()  { return root.resolve("settings.json"); }

    /** Instances folder chosen in Settings, or the default one (also if the setting is not a valid path). */
    public Path instancesDirFor(AppSettings settings) {
        String custom = settings.instanceDirectory;
        if (custom != null && !custom.isBlank()) {
            try {
                return Path.of(custom).toAbsolutePath().normalize();
            } catch (java.nio.file.InvalidPathException e) {
                return instancesDir();
            }
        }
        return instancesDir();
    }

    public void ensureDirectories() {
        try {
            for (Path dir : new Path[]{root, instancesDir(), downloadsDir(), backupsDir(), cacheDir(), logsDir()}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create application folders under " + root, e);
        }
    }
}
