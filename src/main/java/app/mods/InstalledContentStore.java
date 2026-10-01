package app.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes the list of mods this app installed into one instance. */
public final class InstalledContentStore {

    public static final String FILE_NAME = "installed-content.json";
    private static final Logger log = LoggerFactory.getLogger("Mods");

    /** Shape of the JSON file. A class (not a record) keeps Gson simple and the format easy to extend. */
    static final class Document {
        List<InstalledMod> mods = new ArrayList<>();
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public InstalledContentStore(Path instanceDirectory) {
        this.file = instanceDirectory.resolve(FILE_NAME);
    }

    public List<InstalledMod> load() {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            Document doc = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (doc == null || doc.mods == null) throw new JsonParseException("empty file");
            List<InstalledMod> result = new ArrayList<>();
            for (InstalledMod m : doc.mods) {
                if (m != null && m.projectId() != null && m.fileName() != null) result.add(m);
            }
            return result;
        } catch (IOException | JsonParseException e) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            log.error("Could not read {}, keeping a copy and starting with an empty list", file, e);
            try {
                Files.move(file, file.resolveSibling(FILE_NAME + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Nothing more we can do.
            }
            return new ArrayList<>();
        }
    }

    public void save(List<InstalledMod> mods) {
        Document doc = new Document();
        doc.mods = new ArrayList<>(mods);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.writeString(tmp, gson.toJson(doc), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new InstallException("Could not save the list of installed mods.", e);
        }
    }
}
