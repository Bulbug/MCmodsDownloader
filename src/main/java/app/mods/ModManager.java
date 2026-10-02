package app.mods;

import app.instance.InstanceException;
import app.instance.SafePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists the mods in an instance and removes them safely.
 * Removing never deletes: files are moved to "&lt;instance&gt;/.removed/&lt;time&gt;/", so a mistake
 * can be undone by moving the file back. Call from a background thread.
 */
public final class ModManager {

    public static final String REMOVED_FOLDER = ".removed";
    private static final Logger log = LoggerFactory.getLogger("Mods");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Moves one file. Replaceable in tests to simulate failures. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private final Mover mover;

    public ModManager() {
        this(ModManager::moveFile);
    }

    ModManager(Mover mover) {
        this.mover = mover;
    }

    // ---- listing ---------------------------------------------------------------------

    public List<ManagedMod> list(Path instanceDir) {
        Path modsDir = modsDir(instanceDir);
        List<InstalledMod> tracked = new InstalledContentStore(instanceDir).load();
        List<ManagedMod> result = new ArrayList<>();
        Set<String> trackedNames = new HashSet<>();

        for (InstalledMod t : tracked) {
            trackedNames.add(t.fileName().toLowerCase(Locale.ROOT));
            Path file = safeResolve(modsDir, t.fileName());
            boolean exists = file != null && Files.isRegularFile(file);
            result.add(new ManagedMod(t.title(), t.versionNumber(), t.fileName(), true, t.explicit(),
                    t.projectId(), exists, exists ? sizeOf(file) : 0));
        }

        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (Files.isRegularFile(file) && !trackedNames.contains(name.toLowerCase(Locale.ROOT))) {
                        result.add(new ManagedMod(stripJar(name), "", name, false, false, null, true, sizeOf(file)));
                    }
                }
            } catch (IOException e) {
                throw new InstanceException("Could not read the mods folder.", e);
            }
        }
        result.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        return result;
    }

    // ---- planning --------------------------------------------------------------------

    public RemovalPlan planRemoval(Path instanceDir, ManagedMod target) {
        if (!target.tracked()) return new RemovalPlan(target, List.of(), List.of());

        List<InstalledMod> tracked = new InstalledContentStore(instanceDir).load();
        InstalledMod targetRecord = find(tracked, target.projectId());
        if (targetRecord == null) return new RemovalPlan(target, List.of(), List.of());

        // Everything that needs the target, directly or through a chain of other mods.
        Set<String> broken = new HashSet<>(Set.of(targetRecord.projectId()));
        List<InstalledMod> dependents = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (InstalledMod m : tracked) {
                if (broken.contains(m.projectId())) continue;
                if (m.requires().stream().anyMatch(broken::contains)) {
                    broken.add(m.projectId());
                    dependents.add(m);
                    changed = true;
                }
            }
        }
        return new RemovalPlan(target, dependents, unusedDependencies(tracked, Set.of(targetRecord.projectId())));
    }

    /**
     * Dependency-only mods that were needed by something being removed and that nothing remaining needs.
     * Repeats until stable, so a chain A -> B -> C is cleaned up completely.
     */
    private static List<InstalledMod> unusedDependencies(List<InstalledMod> tracked, Set<String> removing) {
        Set<String> gone = new HashSet<>(removing);
        List<InstalledMod> unused = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> neededByRemoved = new HashSet<>();
            for (InstalledMod m : tracked) if (gone.contains(m.projectId())) neededByRemoved.addAll(m.requires());

            for (InstalledMod candidate : tracked) {
                if (candidate.explicit() || gone.contains(candidate.projectId())) continue;
                if (!neededByRemoved.contains(candidate.projectId())) continue;
                boolean stillNeeded = tracked.stream().anyMatch(other -> !gone.contains(other.projectId())
                        && other != candidate && other.requires().contains(candidate.projectId()));
                if (!stillNeeded) {
                    gone.add(candidate.projectId());
                    unused.add(candidate);
                    changed = true;
                }
            }
        }
        return unused;
    }

    // ---- removing --------------------------------------------------------------------

    public RemovalResult remove(Path instanceDir, RemovalPlan plan, boolean includeUnusedDependencies) {
        Path modsDir = modsDir(instanceDir);
        List<String> fileNames = new ArrayList<>();
        Set<String> projectIds = new HashSet<>();

        fileNames.add(plan.target().fileName());
        if (plan.target().tracked() && plan.target().projectId() != null) projectIds.add(plan.target().projectId());
        if (includeUnusedDependencies) {
            for (InstalledMod m : plan.unusedDependencies()) {
                fileNames.add(m.fileName());
                projectIds.add(m.projectId());
            }
        }

        // Check every file name BEFORE moving anything, so a bad record cannot cause a half-done removal.
        List<Path> sources = new ArrayList<>();
        for (String name : fileNames) {
            Path source = safeResolve(modsDir, name);
            if (source == null) {
                throw new InstallException("The record contains an unsafe file name (" + name + "). Nothing was changed.");
            }
            sources.add(source);
        }

        Path removedDir = null;
        List<Path[]> moved = new ArrayList<>();
        try {
            for (Path source : sources) {
                if (!Files.isRegularFile(source)) continue; // already gone: only the record is cleared
                if (removedDir == null) removedDir = newRemovedFolder(instanceDir);
                Path destination = removedDir.resolve(source.getFileName().toString());
                mover.move(source, destination);
                moved.add(new Path[]{source, destination});
            }

            if (!projectIds.isEmpty()) {
                InstalledContentStore store = new InstalledContentStore(instanceDir);
                List<InstalledMod> all = store.load();
                all.removeIf(m -> projectIds.contains(m.projectId()));
                store.save(all);
            }
        } catch (IOException | RuntimeException e) {
            undo(moved);
            deleteIfEmpty(removedDir);
            log.error("Removal failed and was undone", e);
            throw new InstallException("Could not remove the mod. A file may be in use (is Minecraft running?). "
                    + "Nothing was changed.", e);
        }

        List<String> movedNames = moved.stream().map(p -> p[0].getFileName().toString()).toList();
        log.info("Removed {} file(s) from {}", movedNames.size(), instanceDir.getFileName());
        return new RemovalResult(movedNames, removedDir);
    }

    // ---- helpers ---------------------------------------------------------------------

    private static Path modsDir(Path instanceDir) {
        return instanceDir.resolve("game").resolve("mods");
    }

    /** Resolves a file name from a record. Returns null unless it is a plain name that stays inside the folder. */
    static Path safeResolve(Path modsDir, String fileName) {
        try {
            if (fileName == null || fileName.isBlank()) return null;
            // Names this app writes never contain separators or colons. Reject them on every platform,
            // because an instance folder can be copied between Windows, Linux and macOS.
            for (int i = 0; i < fileName.length(); i++) {
                char c = fileName.charAt(i);
                if (c == '/' || c == '\\' || c == ':' || Character.isISOControl(c)) return null;
            }
            if (fileName.equals(".") || fileName.equals("..")) return null;
            if (!fileName.equals(Path.of(fileName).getFileName().toString())) return null;
            return SafePaths.resolveInside(modsDir, fileName);
        } catch (InvalidPathException | InstanceException e) {
            return null;
        }
    }

    private static InstalledMod find(List<InstalledMod> list, String projectId) {
        for (InstalledMod m : list) if (m.projectId().equals(projectId)) return m;
        return null;
    }

    private Path newRemovedFolder(Path instanceDir) throws IOException {
        Path base = instanceDir.resolve(REMOVED_FOLDER);
        String stamp = LocalDateTime.now().format(STAMP);
        Path dir = base.resolve(stamp);
        for (int n = 2; Files.exists(dir); n++) dir = base.resolve(stamp + "-" + n);
        return Files.createDirectories(dir);
    }

    private void undo(List<Path[]> moved) {
        for (int i = moved.size() - 1; i >= 0; i--) {
            try {
                mover.move(moved.get(i)[1], moved.get(i)[0]);
            } catch (IOException | RuntimeException e) {
                log.error("Could not put {} back during undo", moved.get(i)[0], e);
            }
        }
    }

    private static void deleteIfEmpty(Path dir) {
        if (dir == null) return;
        try {
            Files.deleteIfExists(dir);
            Files.deleteIfExists(dir.getParent());
        } catch (IOException ignored) {
            // Not empty or in use: leave it.
        }
    }

    private static void moveFile(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String stripJar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
    }
}
