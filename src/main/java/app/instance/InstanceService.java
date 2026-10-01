package app.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** All instance operations. Runs file work, so call it from a background thread. */
public final class InstanceService {

    public static final List<String> LOADERS = List.of("Vanilla", "Fabric", "Quilt", "Forge", "NeoForge");
    private static final int MAX_NAME_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger("Instances");
    private final InstanceRepository repository;

    public InstanceService(InstanceRepository repository) {
        this.repository = repository;
    }

    public List<Instance> list() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(Instance::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Instance create(String rawName, String minecraftVersion, String loader, String loaderVersion) {
        String name = validateName(rawName);
        String version = validateVersion(minecraftVersion);
        String chosenLoader = validateLoader(loader);
        requireUniqueName(name, null);

        String id = uniqueFolderName(SafePaths.toFolderName(name));
        String loaderVer = (loaderVersion == null || loaderVersion.isBlank()) ? null : loaderVersion.trim();
        Instance instance = new Instance(id, name, version, chosenLoader, loaderVer,
                System.currentTimeMillis(), 0L, null, Instance.DEFAULT_MEMORY_MB);
        repository.save(instance);
        log.info("Created instance '{}' ({} {}) in folder {}", name, chosenLoader, version, id);
        return instance;
    }

    public Instance rename(String id, String rawName) {
        Instance current = require(id);
        String name = validateName(rawName);
        requireUniqueName(name, id);
        Instance renamed = current.withName(name);
        repository.save(renamed);
        log.info("Renamed instance {} to '{}'", id, name);
        return renamed;
    }

    public Instance duplicate(String id) {
        Instance original = require(id);
        String newName = uniqueCopyName(original.name());
        String newId = uniqueFolderName(SafePaths.toFolderName(newName));

        repository.copyDirectory(id, newId);
        Instance copy = original.withIdentity(newId, newName, System.currentTimeMillis());
        repository.save(copy); // rewrites instance.json with the new id and name
        log.info("Duplicated instance {} as {}", id, newId);
        return copy;
    }

    /** Number of worlds (folders with level.dat) inside the instance. Used for the delete warning. */
    public int countWorlds(String id) {
        Path saves = repository.directoryOf(id).resolve(FileInstanceRepository.GAME_DIR).resolve("saves");
        if (!Files.isDirectory(saves)) return 0;
        int count = 0;
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(saves)) {
            for (Path world : worlds) {
                if (Files.isRegularFile(world.resolve("level.dat"))) count++;
            }
        } catch (IOException e) {
            log.warn("Could not count worlds in {}", saves, e);
        }
        return count;
    }

    /** Deletes the instance. The UI must ask for confirmation first (see countWorlds). */
    public void delete(String id) {
        require(id);
        repository.delete(id);
        log.info("Deleted instance {}", id);
    }

    public Path folderOf(String id) {
        require(id);
        return repository.directoryOf(id);
    }

    // ---- helpers -------------------------------------------------------------------

    private Instance require(String id) {
        return repository.find(id).orElseThrow(() -> new InstanceException("That instance no longer exists."));
    }

    static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) throw new InstanceException("Please enter a name for the instance.");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InstanceException("The name is too long (maximum " + MAX_NAME_LENGTH + " characters).");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new InstanceException("The name contains invalid characters.");
            }
        }
        return name;
    }

    static String validateVersion(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new InstanceException("Please choose a Minecraft version.");
        if (!v.matches("[A-Za-z0-9._+\\- ]{1,48}")) {
            throw new InstanceException("The Minecraft version contains unsupported characters.");
        }
        return v;
    }

    static String validateLoader(String raw) {
        for (String known : LOADERS) {
            if (known.equalsIgnoreCase(raw == null ? "" : raw.trim())) return known;
        }
        throw new InstanceException("Unknown mod loader: " + raw);
    }

    private void requireUniqueName(String name, String ignoreId) {
        for (Instance other : repository.findAll()) {
            if (other.id().equals(ignoreId)) continue;
            if (other.name().equalsIgnoreCase(name)) {
                throw new InstanceException("An instance named \"" + name + "\" already exists.");
            }
        }
    }

    private String uniqueFolderName(String base) {
        String candidate = base;
        int n = 2;
        while (repository.exists(candidate)) {
            candidate = SafePaths.toFolderName(base + "-" + n);
            n++;
        }
        return candidate;
    }

    private String uniqueCopyName(String original) {
        Set<String> taken = new java.util.HashSet<>();
        for (Instance i : repository.findAll()) taken.add(i.name().toLowerCase(Locale.ROOT));
        String candidate = original + " (copy)";
        int n = 2;
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = original + " (copy " + n + ")";
            n++;
        }
        return candidate.length() > MAX_NAME_LENGTH ? candidate.substring(candidate.length() - MAX_NAME_LENGTH) : candidate;
    }
}
