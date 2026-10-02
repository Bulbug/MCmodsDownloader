package app.mods;

import app.api.ProjectVersion.DependencyType;
import app.downloads.Checksum;
import app.downloads.DownloadException;
import app.downloads.DownloadItem;
import app.downloads.DownloadManager;
import app.downloads.DownloadRequest;
import app.downloads.DownloadSnapshot;
import app.downloads.DownloadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Carries out an {@link InstallPlan}: all-or-nothing, for new installs and for replacing a mod's version.
 * Files are downloaded (and checksum-verified) into a staging folder first. Only when every
 * download succeeded are they moved into game/mods and recorded. Any failure leaves the
 * instance exactly as it was. Runs on the calling thread, so call it from a background thread.
 */
public final class ModInstaller {

    private static final Logger log = LoggerFactory.getLogger("Mods");

    private final DownloadManager downloads;

    public ModInstaller(DownloadManager downloads) {
        this.downloads = downloads;
    }

    private record Staged(PlannedMod mod, Path stagedFile, DownloadItem item) { }

    /**
     * Installs a plan for mods that are not installed yet.
     *
     * @param cancelled polled while downloading; return true to abort and undo
     * @param progress  receives short status texts for the UI
     * @return the entries that were added to the instance
     */
    public List<InstalledMod> install(InstallPlan plan, Path instanceDir, BooleanSupplier cancelled,
                                      Consumer<String> progress) {
        return run(plan, null, instanceDir, cancelled, progress);
    }

    /**
     * Replaces {@code current} with the version in the plan (an update or a rollback). The old file is
     * moved to the instance's .removed folder, so it can be restored by hand.
     */
    public List<InstalledMod> update(InstallPlan plan, InstalledMod current, Path instanceDir,
                                     BooleanSupplier cancelled, Consumer<String> progress) {
        return run(plan, current, instanceDir, cancelled, progress);
    }

    private List<InstalledMod> run(InstallPlan plan, InstalledMod replacing, Path instanceDir,
                                   BooleanSupplier cancelled, Consumer<String> progress) {
        if (!plan.canInstall()) throw new InstallException("The plan has problems and cannot be installed.");

        Path modsDir = instanceDir.resolve("game").resolve("mods");
        Path stagingRoot = instanceDir.resolve(".staging");
        Path staging = stagingRoot.resolve(Long.toString(System.nanoTime()));
        List<Staged> staged = new ArrayList<>();
        List<Path> movedFiles = new ArrayList<>();
        Path[] oldFileMove = null; // {original location, location in .removed}
        Path removedDir = null;

        try {
            Files.createDirectories(modsDir);
            Files.createDirectories(staging);
            progress.accept("Starting downloads...");

            for (PlannedMod mod : plan.toInstall()) {
                var file = mod.file();
                Checksum checksum = file.sha512() != null ? Checksum.parse(file.sha512())
                        : file.sha1() != null ? Checksum.parse(file.sha1()) : null;
                Path target = staging.resolve(mod.targetFileName());
                DownloadItem item = downloads.enqueue(new DownloadRequest(URI.create(file.url()), target,
                        mod.title() + " " + mod.version().versionNumber(), checksum, file.size(), false));
                staged.add(new Staged(mod, target, item));
            }

            waitForDownloads(staged, cancelled, progress);

            progress.accept("Installing files...");
            if (replacing != null) {
                Path oldFile = ModManager.safeResolve(modsDir, replacing.fileName());
                if (oldFile != null && Files.isRegularFile(oldFile)) {
                    removedDir = ModManager.newRemovedFolder(instanceDir);
                    Path kept = removedDir.resolve(oldFile.getFileName().toString());
                    move(oldFile, kept);
                    oldFileMove = new Path[]{oldFile, kept};
                }
            }
            for (Staged s : staged) {
                Path destination = modsDir.resolve(s.mod().targetFileName());
                if (Files.exists(destination)) {
                    throw new InstallException("A file named \"" + destination.getFileName()
                            + "\" appeared in the mods folder in the meantime. Nothing was changed.");
                }
                move(s.stagedFile(), destination);
                movedFiles.add(destination);
            }

            List<InstalledMod> added = record(staged, replacing);
            InstalledContentStore store = new InstalledContentStore(instanceDir);
            List<InstalledMod> all = store.load();
            if (replacing != null) all.removeIf(m -> m.projectId().equals(replacing.projectId()));
            all.addAll(added);
            store.save(all);

            log.info("{} {} mod(s) in {}", replacing == null ? "Installed" : "Updated", added.size(),
                    instanceDir.getFileName());
            progress.accept("Done.");
            return added;

        } catch (DownloadException e) {
            rollback(movedFiles, oldFileMove, removedDir);
            cancelAll(staged);
            throw new InstallException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            rollback(movedFiles, oldFileMove, removedDir);
            cancelAll(staged);
            if (e instanceof InstallException ie) throw ie;
            log.error("Installation failed", e);
            throw new InstallException("The installation failed and was undone. Details were written to the log file.", e);
        } finally {
            awaitStopped(staged);
            deleteTree(staging);
            try {
                Files.deleteIfExists(stagingRoot); // only succeeds when empty
            } catch (IOException ignored) {
                // Another install may be using it; leave it.
            }
        }
    }

    private void waitForDownloads(List<Staged> staged, BooleanSupplier cancelled, Consumer<String> progress) {
        while (true) {
            if (cancelled.getAsBoolean()) throw new InstallException("Installation was cancelled. Nothing was changed.");

            int done = 0;
            for (Staged s : staged) {
                DownloadSnapshot snap = s.item().snapshot();
                if (snap.state() == DownloadState.FAILED || snap.state() == DownloadState.CANCELLED) {
                    throw new InstallException("Could not download " + snap.name() + ": "
                            + (snap.message().isBlank() ? "the download was cancelled." : snap.message())
                            + " Nothing was changed.");
                }
                if (snap.state() == DownloadState.COMPLETED) done++;
            }
            if (done == staged.size()) return;

            progress.accept("Downloading " + (done + 1) + " of " + staged.size() + "...");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InstallException("Installation was interrupted. Nothing was changed.");
            }
        }
    }

    private List<InstalledMod> record(List<Staged> staged, InstalledMod replacing) {
        List<InstalledMod> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Staged s : staged) {
            PlannedMod m = s.mod();
            List<String> requires = new ArrayList<>();
            List<String> incompatible = new ArrayList<>();
            for (var dep : m.version().dependencies()) {
                if (dep.projectId() == null) continue;
                if (dep.type() == DependencyType.REQUIRED) requires.add(dep.projectId());
                if (dep.type() == DependencyType.INCOMPATIBLE) incompatible.add(dep.projectId());
            }
            // An updated mod keeps the choice the user made before (chosen vs. pulled in as a dependency).
            boolean explicit = replacing != null && m.projectId().equals(replacing.projectId())
                    ? replacing.explicit() : m.chosen();
            result.add(new InstalledMod(m.projectId(), m.title(), m.version().id(), m.version().versionNumber(),
                    m.targetFileName(), m.file().sha512(), explicit, now, requires, incompatible,
                    m.version().datePublished()));
        }
        return result;
    }

    private void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /** Deletes the new files that were moved in, then puts the old file back (if one was moved away). */
    private void rollback(List<Path> movedFiles, Path[] oldFileMove, Path removedDir) {
        for (Path p : movedFiles) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.error("Could not undo {} during rollback", p, e);
            }
        }
        if (oldFileMove != null) {
            try {
                move(oldFileMove[1], oldFileMove[0]);
            } catch (IOException e) {
                log.error("Could not put {} back during rollback; it is in {}", oldFileMove[0], oldFileMove[1], e);
            }
        }
        if (removedDir != null) {
            try {
                Files.deleteIfExists(removedDir);
                Files.deleteIfExists(removedDir.getParent());
            } catch (IOException ignored) {
                // Not empty: leave it.
            }
        }
    }

    private void cancelAll(List<Staged> staged) {
        for (Staged s : staged) downloads.cancel(s.item().id());
    }

    /** Gives cancelled downloads a moment to release their files before the staging folder is deleted. */
    private void awaitStopped(List<Staged> staged) {
        long deadline = System.currentTimeMillis() + 5000;
        for (Staged s : staged) {
            while (s.item().snapshot().state() == DownloadState.DOWNLOADING
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Could not remove the staging folder {}", dir, e);
        }
    }
}
