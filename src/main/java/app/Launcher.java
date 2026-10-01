package app;

import app.api.ModrinthProvider;
import app.configuration.AppContext;
import app.configuration.AppPaths;
import app.configuration.AppSettings;
import app.configuration.SettingsService;
import app.downloads.DownloadManager;
import app.ui.MainApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/** Real entry point. Kept separate from the JavaFX Application class on purpose. */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        AppPaths paths = AppPaths.defaultLocation();
        paths.ensureDirectories();

        // Must be set BEFORE the first logger is created; logback.xml reads it.
        System.setProperty("mcmanager.logdir", paths.logsDir().toString());
        Logger log = LoggerFactory.getLogger("Bootstrap");
        log.info("Starting Minecraft Manager, data directory: {}", paths.root());

        SettingsService settingsService = new SettingsService(paths.settingsFile());
        AppSettings settings = settingsService.load();

        // Downloads may only be written into the downloads folder or the instances folder.
        DownloadManager downloads = new DownloadManager(settings.maxConcurrentDownloads,
                () -> List.of(paths.downloadsDir(), paths.instancesDirFor(settings)));

        ModrinthProvider modrinth = new ModrinthProvider(
                () -> settings.apiContact,
                () -> Duration.ofMinutes(settings.cacheMinutes));

        MainApp.launchApp(new AppContext(paths, settingsService, settings, downloads, modrinth), args);
    }
}
