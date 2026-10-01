package app.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileGivesDefaults() {
        SettingsService service = new SettingsService(tempDir.resolve("settings.json"));
        AppSettings settings = service.load();
        assertEquals("dark", settings.theme);
        assertEquals(3, settings.maxConcurrentDownloads);
    }

    @Test
    void saveThenLoadRoundTrips() {
        SettingsService service = new SettingsService(tempDir.resolve("settings.json"));
        AppSettings settings = new AppSettings();
        settings.maxConcurrentDownloads = 5;
        settings.language = "fil";
        service.save(settings);

        AppSettings loaded = service.load();
        assertEquals(5, loaded.maxConcurrentDownloads);
        assertEquals("fil", loaded.language);
    }

    @Test
    void corruptFileFallsBackToDefaultsAndIsPreserved() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{ this is not json");

        AppSettings loaded = new SettingsService(file).load();

        assertEquals(3, loaded.maxConcurrentDownloads);
        assertTrue(Files.exists(tempDir.resolve("settings.json.corrupt")));
    }

    @Test
    void outOfRangeValuesAreClamped() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, "{\"maxConcurrentDownloads\": 999}");

        assertEquals(10, new SettingsService(file).load().maxConcurrentDownloads);
    }

    @Test
    void appPathsCreatesExpectedFolders() {
        AppPaths paths = new AppPaths(tempDir.resolve("data"));
        paths.ensureDirectories();
        assertTrue(Files.isDirectory(paths.instancesDir()));
        assertTrue(Files.isDirectory(paths.logsDir()));
        assertTrue(Files.isDirectory(paths.backupsDir()));
    }
}
