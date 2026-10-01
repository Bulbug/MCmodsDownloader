package app.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves {@link AppSettings}. Never throws on a missing or corrupt file. */
public final class SettingsService {

    private static final Logger log = LoggerFactory.getLogger("Settings");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public SettingsService(Path file) {
        this.file = file;
    }

    public AppSettings load() {
        if (!Files.exists(file)) {
            log.info("No settings file found, using defaults");
            return new AppSettings();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            AppSettings loaded = gson.fromJson(json, AppSettings.class);
            if (loaded == null) {
                throw new JsonParseException("Settings file is empty");
            }
            loaded.normalize();
            return loaded;
        } catch (IOException | JsonParseException e) {
            log.error("Settings file unreadable, keeping a copy and using defaults", e);
            preserveCorruptFile();
            return new AppSettings();
        }
    }

    /** Writes to a temp file first, then moves it into place, so a crash cannot leave half a file. */
    public void save(AppSettings settings) {
        settings.normalize();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, gson.toJson(settings), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Settings saved");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save settings to " + file, e);
        }
    }

    private void preserveCorruptFile() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not preserve corrupt settings file", e);
        }
    }
}
