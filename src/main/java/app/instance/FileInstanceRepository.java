package app.instance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Layout on disk:
 * <pre>
 * instances/
 *   Fabric-1.21.8/
 *     instance.json
 *     game/   mods, config, resourcepacks, shaderpacks, saves, logs, screenshots
 * </pre>
 * Metadata lives outside game/ so it can never end up inside a shared modpack by accident.
 */
public final class FileInstanceRepository implements InstanceRepository {

    public static final String METADATA_FILE = "instance.json";
    public static final String GAME_DIR = "game";
    static final List<String> GAME_SUBFOLDERS = List.of(
            "mods", "config", "resourcepacks", "shaderpacks", "saves", "logs", "screenshots");

    private static final Logger log = LoggerFactory.getLogger("Instances");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;

    public FileInstanceRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Path directoryOf(String id) {
        if (!SafePaths.isSafeFolderName(id)) {
            throw new InstanceException("Invalid instance folder name.");
        }
        return SafePaths.resolveInside(root, id);
    }

    @Override
    public List<Instance> findAll() {
        List<Instance> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                read(folder).ifPresent(result::add);
            }
        } catch (IOException e) {
            throw new InstanceException("Could not read the instances folder: " + root, e);
        }
        return result;
    }

    @Override
    public Optional<Instance> find(String id) {
        if (!SafePaths.isSafeFolderName(id)) return Optional.empty();
        return read(directoryOf(id));
    }

    @Override
    public boolean exists(String id) {
        return SafePaths.isSafeFolderName(id) && Files.exists(directoryOf(id));
    }

    @Override
    public void save(Instance instance) {
        Path dir = directoryOf(instance.id());
        try {
            Files.createDirectories(dir);
            Path game = dir.resolve(GAME_DIR);
            for (String sub : GAME_SUBFOLDERS) Files.createDirectories(game.resolve(sub));
            writeAtomically(dir.resolve(METADATA_FILE), gson.toJson(instance));
        } catch (IOException e) {
            throw new InstanceException("Could not save instance \"" + instance.name() + "\".", e);
        }
    }

    @Override
    public void copyDirectory(String fromId, String toId) {
        Path source = directoryOf(fromId);
        Path target = directoryOf(toId);
        if (!Files.isDirectory(source)) throw new InstanceException("The instance to copy no longer exists.");
        if (Files.exists(target)) throw new InstanceException("The target folder already exists.");

        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // Only real files. Symbolic links are skipped so a link cannot pull in files from elsewhere.
                    if (attrs.isRegularFile()) {
                        Path dest = target.resolve(source.relativize(file).toString());
                        SafePaths.requireInside(target, dest);
                        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Do not leave a half-copied instance behind.
            deleteQuietly(target);
            throw new InstanceException("Could not copy the instance. Check free disk space and try again.", e);
        }
    }

    @Override
    public void delete(String id) {
        Path dir = directoryOf(id);
        if (!Files.isRegularFile(dir.resolve(METADATA_FILE))) {
            // Safety net: only ever delete folders that are really instances.
            throw new InstanceException("That folder is not a Minecraft Manager instance, so it was not deleted.");
        }
        try {
            deleteTree(dir);
        } catch (IOException e) {
            throw new InstanceException(
                    "Could not delete the instance. A file may be in use (is Minecraft still running?).", e);
        }
    }

    private Optional<Instance> read(Path folder) {
        Path file = folder.resolve(METADATA_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            Instance loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Instance.class);
            String folderName = folder.getFileName().toString();
            if (loaded == null || loaded.name() == null || loaded.name().isBlank()
                    || loaded.minecraftVersion() == null || !folderName.equals(loaded.id())) {
                log.warn("Ignoring instance with invalid metadata: {}", folder);
                return Optional.empty();
            }
            return Optional.of(loaded);
        } catch (IOException | JsonParseException e) {
            log.warn("Ignoring unreadable instance metadata: {}", file, e);
            return Optional.empty();
        }
    }

    private void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Deletes a folder tree without following symbolic links (a link is removed, its target is not). */
    private void deleteTree(Path dir) throws IOException {
        SafePaths.requireInside(root, dir);
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteQuietly(Path dir) {
        try {
            if (Files.exists(dir)) deleteTree(dir);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not clean up {}", dir, e);
        }
    }
}
