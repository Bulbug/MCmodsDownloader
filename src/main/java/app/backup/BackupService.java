package app.backup;

import app.instance.Instance;
import app.instance.InstanceException;
import app.instance.InstanceRepository;
import app.instance.InstanceService;
import app.instance.SafePaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Backups are ordinary .zip files stored outside the instances, in
 * backups/&lt;instance-id&gt;/&lt;date-time&gt;.zip, so they survive deleting an instance.
 * Each zip holds a small backup.json describing it, so it can be restored (or exported and shared).
 *
 * Safety rules:
 * - archive entry names are untrusted: anything that could escape the target folder is rejected
 * - restoring first extracts into a staging folder, makes an automatic safety copy of what will be
 *   replaced, then swaps folders in; any failure puts everything back
 * - symbolic links are never followed or restored; nothing from a backup is ever executed
 * Call everything here from a background thread.
 */
public final class BackupService {

    static final String METADATA = "backup.json";
    static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 500_000;
    private static final Logger log = LoggerFactory.getLogger("Backup");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Set<String> TOP_LEVEL_EXCLUDED =
            Set.of(".staging", ".removed", ".restore-staging", ".restore-old");

    /** Moves a file or folder. Replaceable in tests to simulate failures. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /** Where to take files from: a whole file/folder tree, or only the files directly inside a folder. */
    private record Source(String relativePath, boolean topFilesOnly) { }

    private record Entry(String name, Path file, boolean directory, long size) { }

    /** Shape of backup.json. */
    static final class Metadata {
        int formatVersion = FORMAT_VERSION;
        String appVersion = "0.1.0";
        String instanceId;
        String instanceName;
        String minecraftVersion;
        String loader;
        String loaderVersion;
        long createdAt;
        List<String> parts = new ArrayList<>();
        boolean entireInstance;
        String note = "";
        int fileCount;
    }

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    private final Mover mover;

    public BackupService(Path backupsRoot) {
        this(backupsRoot, BackupService::moveFile);
    }

    BackupService(Path backupsRoot, Mover mover) {
        this.root = backupsRoot.toAbsolutePath().normalize();
        this.mover = mover;
    }

    // ---- creating --------------------------------------------------------------------

    /**
     * @param entire true to back up everything in the instance folder (parts is then ignored)
     * @param cancelled polled during the copy; return true to stop (the partial file is removed)
     */
    public BackupInfo create(Instance instance, Path instanceDir, Set<BackupPart> parts, boolean entire,
                             String note, BooleanSupplier cancelled, ProgressListener progress) {
        if (!entire && parts.isEmpty()) throw new BackupException("Choose at least one thing to back up.");

        List<Source> sources = new ArrayList<>();
        if (entire) {
            sources.add(new Source("", false));
        } else {
            for (BackupPart part : parts) sources.addAll(sourcesFor(part));
        }
        return write(instance, instanceDir, sources, entire ? EnumSet.noneOf(BackupPart.class) : EnumSet.copyOf(parts),
                entire, note, cancelled, progress);
    }

    private static List<Source> sourcesFor(BackupPart part) {
        return switch (part) {
            case MODS -> List.of(new Source("game/mods", false), new Source("installed-content.json", false));
            case CONFIG -> List.of(new Source("game/config", false));
            case SAVES -> List.of(new Source("game/saves", false));
            case RESOURCE_PACKS -> List.of(new Source("game/resourcepacks", false));
            case SHADER_PACKS -> List.of(new Source("game/shaderpacks", false));
            case OPTIONS -> List.of(new Source("game", true));
        };
    }

    private BackupInfo write(Instance instance, Path instanceDir, List<Source> sources, Set<BackupPart> parts,
                             boolean entire, String note, BooleanSupplier cancelled, ProgressListener progress) {
        List<Entry> entries = collect(instanceDir, sources);
        long total = entries.stream().mapToLong(Entry::size).sum();

        Path dir;
        try {
            dir = Files.createDirectories(root.resolve(requireSafeId(instance.id())));
        } catch (IOException e) {
            throw new BackupException("Could not create the backup folder.", e);
        }
        String stamp = LocalDateTime.now().format(STAMP);
        Path target = dir.resolve(stamp + ".zip");
        for (int n = 2; Files.exists(target); n++) target = dir.resolve(stamp + "-" + n + ".zip");
        Path partial = target.resolveSibling(target.getFileName() + ".part");

        Metadata meta = new Metadata();
        meta.instanceId = instance.id();
        meta.instanceName = instance.name();
        meta.minecraftVersion = instance.minecraftVersion();
        meta.loader = instance.loader();
        meta.loaderVersion = instance.loaderVersion();
        meta.createdAt = System.currentTimeMillis();
        for (BackupPart p : parts) meta.parts.add(p.name());
        meta.entireInstance = entire;
        meta.note = note == null ? "" : note.trim();
        meta.fileCount = (int) entries.stream().filter(e -> !e.directory()).count();

        try {
            try (OutputStream raw = Files.newOutputStream(partial);
                 ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
                zip.setLevel(Deflater.BEST_SPEED); // jars and worlds are mostly compressed already; favour speed

                zip.putNextEntry(new ZipEntry(METADATA));
                zip.write(gson.toJson(meta).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                long done = 0;
                byte[] buffer = new byte[65536];
                progress.update(0, total);
                for (Entry entry : entries) {
                    if (cancelled.getAsBoolean()) throw new BackupException("The backup was cancelled.");
                    if (entry.directory()) {
                        zip.putNextEntry(new ZipEntry(entry.name() + "/"));
                        zip.closeEntry();
                        continue;
                    }
                    ZipEntry zipEntry = new ZipEntry(entry.name());
                    try {
                        zipEntry.setLastModifiedTime(Files.getLastModifiedTime(entry.file(), LinkOption.NOFOLLOW_LINKS));
                    } catch (IOException ignored) {
                        // Keep the default time.
                    }
                    zip.putNextEntry(zipEntry);
                    try (InputStream in = Files.newInputStream(entry.file())) {
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            if (cancelled.getAsBoolean()) throw new BackupException("The backup was cancelled.");
                            zip.write(buffer, 0, n);
                            done += n;
                            progress.update(done, total);
                        }
                    } catch (IOException e) {
                        throw new BackupException("Could not read \"" + entry.name() + "\". "
                                + "If Minecraft is running, close it and try again.", e);
                    }
                    zip.closeEntry();
                }
            }
            moveFile(partial, target);
        } catch (BackupException e) {
            deleteQuietly(partial);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
            log.error("Backup failed", e);
            throw new BackupException("The backup could not be created. Check free disk space and try again.", e);
        }
        log.info("Created backup {} ({} files)", target.getFileName(), meta.fileCount);
        return readInfo(target);
    }

    private List<Entry> collect(Path instanceDir, List<Source> sources) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try {
            for (Source source : sources) {
                Path base = source.relativePath().isEmpty() ? instanceDir : instanceDir.resolve(source.relativePath());
                if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) continue;

                if (source.topFilesOnly()) {
                    if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (DirectoryStream<Path> children = Files.newDirectoryStream(base)) {
                        for (Path child : children) {
                            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (attrs.isRegularFile() && !isSkippedFile(child)) addFile(entries, instanceDir, child, attrs);
                        }
                    }
                } else if (Files.isRegularFile(base, LinkOption.NOFOLLOW_LINKS)) {
                    addFile(entries, instanceDir, base, Files.readAttributes(base, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
                } else {
                    Files.walkFileTree(base, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            Path rel = instanceDir.relativize(dir);
                            if (rel.getNameCount() == 1 && TOP_LEVEL_EXCLUDED.contains(rel.getName(0).toString())
                                    && !dir.equals(base)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(instanceDir)) addDirectory(entries, instanceDir, dir);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            // Links and special files are skipped, so a link cannot pull in files from elsewhere.
                            if (attrs.isRegularFile() && !attrs.isSymbolicLink() && !isSkippedFile(file)) {
                                addFile(entries, instanceDir, file, attrs);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        } catch (IOException e) {
            throw new BackupException("Could not read the instance folder.", e);
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new BackupException("This instance has too many files to back up (more than " + MAX_ENTRIES + ").");
        }
        return new ArrayList<>(entries.values());
    }

    /** Minecraft holds session.lock open while a world is loaded; it is never needed in a backup. */
    private static boolean isSkippedFile(Path file) {
        return file.getFileName().toString().equals("session.lock");
    }

    private static String entryName(Path instanceDir, Path path) {
        List<String> parts = new ArrayList<>();
        for (Path p : instanceDir.relativize(path)) parts.add(p.toString());
        return SafeEntryName.normalize(String.join("/", parts));
    }

    private static void addFile(Map<String, Entry> entries, Path instanceDir, Path file, BasicFileAttributes attrs) {
        String name = entryName(instanceDir, file);
        entries.putIfAbsent(name, new Entry(name, file, false, attrs.size()));
    }

    private static void addDirectory(Map<String, Entry> entries, Path instanceDir, Path dir) {
        String name = entryName(instanceDir, dir);
        entries.putIfAbsent(name, new Entry(name, dir, true, 0));
    }

    // ---- listing, deleting, exporting --------------------------------------------------

    public List<BackupInfo> listAll() {
        List<BackupInfo> result = new ArrayList<>();
        if (!Files.isDirectory(root)) return result;
        try (DirectoryStream<Path> instances = Files.newDirectoryStream(root)) {
            for (Path instanceFolder : instances) {
                if (Files.isDirectory(instanceFolder, LinkOption.NOFOLLOW_LINKS)) result.addAll(listFolder(instanceFolder));
            }
        } catch (IOException e) {
            throw new BackupException("Could not read the backups folder.", e);
        }
        result.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed());
        return result;
    }

    public List<BackupInfo> list(String instanceId) {
        Path folder = root.resolve(requireSafeId(instanceId));
        List<BackupInfo> result = Files.isDirectory(folder) ? listFolder(folder) : new ArrayList<>();
        result.sort(Comparator.comparingLong(BackupInfo::createdAt).reversed());
        return result;
    }

    private List<BackupInfo> listFolder(Path folder) {
        List<BackupInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.zip")) {
            for (Path file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) result.add(readInfo(file));
            }
        } catch (IOException e) {
            log.warn("Could not list {}", folder, e);
        }
        return result;
    }

    public void delete(BackupInfo info) {
        Path file = info.file().toAbsolutePath().normalize();
        SafePaths.requireInside(root, file);
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BackupException("That is not a backup file, so it was not deleted.");
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new BackupException("Could not delete the backup. Is the file open in another program?", e);
        }
        try {
            Files.deleteIfExists(file.getParent()); // removes the instance folder only when it is empty
        } catch (IOException ignored) {
            // Other backups remain.
        }
    }

    /** Copies the backup (a normal .zip) to a place the user chose. */
    public void export(BackupInfo info, Path target) {
        Path source = info.file().toAbsolutePath().normalize();
        SafePaths.requireInside(root, source);
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BackupException("Could not export the backup. Check the destination and free disk space.", e);
        }
    }

    BackupInfo readInfo(Path file) {
        long size = 0;
        try {
            size = Files.size(file);
        } catch (IOException ignored) {
            // Shown as 0 bytes.
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(METADATA);
            if (entry == null || entry.getSize() > 65536) throw new JsonParseException("no metadata");
            Metadata meta;
            try (InputStream in = zip.getInputStream(entry)) {
                meta = gson.fromJson(new String(in.readNBytes(65537), StandardCharsets.UTF_8), Metadata.class);
            }
            if (meta == null || meta.formatVersion != FORMAT_VERSION || meta.instanceId == null
                    || meta.instanceName == null || meta.minecraftVersion == null || meta.loader == null) {
                throw new JsonParseException("incomplete metadata");
            }
            List<BackupPart> parts = new ArrayList<>();
            for (String name : meta.parts == null ? List.<String>of() : meta.parts) {
                try {
                    parts.add(BackupPart.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // A part from a newer version of the app: ignore the label.
                }
            }
            return new BackupInfo(file, true, meta.instanceId, meta.instanceName, meta.minecraftVersion,
                    meta.loader, meta.loaderVersion, meta.createdAt, size, parts, meta.entireInstance,
                    meta.note == null ? "" : meta.note);
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable backup {}: {}", file, e.getMessage());
            long modified = 0;
            try {
                modified = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException ignored) {
                // Shown without a date.
            }
            return new BackupInfo(file, false, "", "(unreadable backup)", "", "", null, modified, size,
                    List.of(), false, file.getFileName().toString());
        }
    }

    // ---- restoring -------------------------------------------------------------------

    /**
     * Puts the backup's contents back into an existing instance, replacing the matching folders/files.
     * An automatic safety copy of what gets replaced is made first. The instance's name and identity
     * are never changed.
     */
    public RestoreResult restoreInto(BackupInfo info, Instance instance, Path instanceDir,
                                     BooleanSupplier cancelled, ProgressListener progress) {
        return restoreCore(info, instance, instanceDir, true, cancelled, progress);
    }

    /** Creates a new instance from the backup (works even if the original instance was deleted). */
    public Instance restoreAsNew(BackupInfo info, InstanceService instances, InstanceRepository repository,
                                 BooleanSupplier cancelled, ProgressListener progress) {
        if (!info.valid()) throw new BackupException("This file is not a readable backup.");

        String name = uniqueName(info.instanceName(), instances);
        Instance created;
        try {
            created = instances.create(name, info.minecraftVersion(), info.loader(), info.loaderVersion());
        } catch (InstanceException e) {
            throw new BackupException("Could not create the new instance: " + e.getMessage(), e);
        }
        try {
            restoreCore(info, created, repository.directoryOf(created.id()), false, cancelled, progress);
            return created;
        } catch (RuntimeException e) {
            try {
                repository.delete(created.id()); // do not leave an empty instance behind
            } catch (RuntimeException cleanupFailure) {
                log.warn("Could not remove the unfinished instance {}", created.id(), cleanupFailure);
            }
            throw e;
        }
    }

    private static String uniqueName(String base, InstanceService instances) {
        Set<String> taken = new LinkedHashSet<>();
        for (Instance i : instances.list()) taken.add(i.name().toLowerCase(Locale.ROOT));
        String trimmed = base.length() > 40 ? base.substring(0, 40) : base;
        String candidate = trimmed + " (restored)";
        for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); n++) candidate = trimmed + " (restored " + n + ")";
        return candidate;
    }

    private RestoreResult restoreCore(BackupInfo info, Instance instance, Path instanceDir, boolean safetyCopy,
                                      BooleanSupplier cancelled, ProgressListener progress) {
        if (!info.valid()) throw new BackupException("This file is not a readable backup.");

        Path staging = instanceDir.resolve(".restore-staging").resolve(Long.toString(System.nanoTime()));
        Path oldRoot = instanceDir.resolve(".restore-old").resolve(Long.toString(System.nanoTime()));
        boolean[] keepOld = {false};
        try (ZipFile zip = new ZipFile(info.file().toFile())) {
            List<String> units = extract(zip, staging, cancelled, progress);

            BackupInfo safety = null;
            if (safetyCopy) {
                List<Source> existing = new ArrayList<>();
                for (String unit : units) {
                    if (Files.exists(instanceDir.resolve(unit), LinkOption.NOFOLLOW_LINKS)) existing.add(new Source(unit, false));
                }
                if (!existing.isEmpty()) {
                    safety = write(instance, instanceDir, existing, EnumSet.noneOf(BackupPart.class), false,
                            "Automatic copy before restoring a backup", () -> false, (d, t) -> { });
                }
            }
            swap(units, staging, oldRoot, instanceDir, keepOld);
            log.info("Restored {} item(s) from {} into {}", units.size(), info.file().getFileName(), instanceDir.getFileName());
            return new RestoreResult(units.size(), safety);
        } catch (IOException e) {
            throw new BackupException("The backup could not be read. The file may be damaged. Nothing was changed.", e);
        } finally {
            deleteQuietly(staging);
            if (!keepOld[0]) deleteQuietly(oldRoot);
            deleteIfEmpty(instanceDir.resolve(".restore-staging"));
            deleteIfEmpty(instanceDir.resolve(".restore-old"));
        }
    }

    /**
     * Extracts the usable entries into a staging folder, checking every name and size.
     * @return the "units" to swap in: game/&lt;folder&gt;, game/&lt;file&gt; or installed-content.json
     */
    private List<String> extract(ZipFile zip, Path staging, BooleanSupplier cancelled, ProgressListener progress)
            throws IOException {
        // Pass 1: validate every name and add up the declared sizes before writing anything.
        List<ZipEntry> usable = new ArrayList<>();
        Set<String> units = new LinkedHashSet<>();
        long declared = 0;
        int count = 0;
        for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
            ZipEntry entry = it.nextElement();
            if (++count > MAX_ENTRIES) throw new BackupException("The backup has too many files. Nothing was changed.");
            String name = SafeEntryName.normalize(entry.getName()); // throws on anything unsafe
            String[] segments = name.split("/");
            String top = segments[0];

            // The backup's own description and the instance identity are never restored.
            if (name.equals(METADATA) || name.equals("instance.json")) continue;
            boolean known = top.equals("game") || name.equals("installed-content.json");
            if (!known) continue; // anything else in the archive is ignored

            if (segments.length == 1 && top.equals("game")) continue; // the game folder itself
            if (top.equals("game")) {
                boolean fileDirectlyInGame = segments.length == 2 && !entry.isDirectory();
                units.add(fileDirectlyInGame ? name : "game/" + segments[1]);
            } else {
                units.add(name);
            }
            if (!entry.isDirectory()) {
                if (entry.getSize() < 0) throw new BackupException("The backup is damaged (unknown file size). Nothing was changed.");
                declared += entry.getSize();
            }
            usable.add(entry);
        }
        if (usable.isEmpty()) throw new BackupException("The backup contains nothing that can be restored.");

        Files.createDirectories(staging);
        long free = Files.getFileStore(staging).getUsableSpace();
        if (declared > free * 0.95) {
            throw new BackupException("Not enough free disk space to restore this backup. "
                    + "It needs about " + declared / (1024 * 1024) + " MB. Nothing was changed.");
        }

        // Pass 2: write the files, exactly as many bytes as the archive declares for each one.
        long done = 0;
        byte[] buffer = new byte[65536];
        progress.update(0, declared);
        for (ZipEntry entry : usable) {
            if (cancelled.getAsBoolean()) throw new BackupException("The restore was cancelled. Nothing was changed.");
            Path target = SafePaths.resolveInside(staging, SafeEntryName.normalize(entry.getName()));
            if (entry.isDirectory()) {
                Files.createDirectories(target);
                continue;
            }
            Files.createDirectories(target.getParent());
            long written = 0;
            try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    written += n;
                    if (written > entry.getSize()) {
                        throw new BackupException("The backup is damaged (a file is larger than declared). Nothing was changed.");
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    progress.update(done, declared);
                    if (cancelled.getAsBoolean()) throw new BackupException("The restore was cancelled. Nothing was changed.");
                }
            }
            if (written != entry.getSize()) {
                throw new BackupException("The backup is damaged (a file is incomplete). Nothing was changed.");
            }
            if (entry.getLastModifiedTime() != null) {
                try {
                    Files.setLastModifiedTime(target, FileTime.fromMillis(entry.getLastModifiedTime().toMillis()));
                } catch (IOException ignored) {
                    // Cosmetic.
                }
            }
        }
        return new ArrayList<>(units);
    }

    /** Moves each existing unit aside, then moves the new one in. Undoes everything if any step fails. */
    private void swap(List<String> units, Path staging, Path oldRoot, Path instanceDir, boolean[] keepOld)
            throws IOException {
        List<String> swapped = new ArrayList<>();
        String inProgress = null;
        try {
            for (String unit : units) {
                inProgress = unit;
                Path staged = staging.resolve(unit);
                Path target = SafePaths.resolveInside(instanceDir, unit);
                if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) continue;

                Path aside = oldRoot.resolve(unit);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(aside.getParent());
                    mover.move(target, aside);
                }
                Files.createDirectories(target.getParent());
                mover.move(staged, target);
                swapped.add(unit);
                inProgress = null;
            }
        } catch (IOException | RuntimeException e) {
            List<String> toUndo = new ArrayList<>(swapped);
            if (inProgress != null) toUndo.add(inProgress);
            Collections.reverse(toUndo);
            boolean complete = true;
            for (String unit : toUndo) {
                try {
                    Path target = instanceDir.resolve(unit);
                    Path aside = oldRoot.resolve(unit);
                    if (swapped.contains(unit)) deleteTreeStrict(target); // the new content that was moved in
                    if (Files.exists(aside, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) deleteTreeStrict(target);
                        mover.move(aside, target);
                    }
                } catch (IOException | RuntimeException undoFailure) {
                    complete = false;
                    log.error("Could not put {} back", unit, undoFailure);
                }
            }
            if (!complete) {
                keepOld[0] = true;
                throw new BackupException("The restore failed and some items could not be put back automatically. "
                        + "Your previous files are kept in: " + oldRoot, e);
            }
            throw new BackupException("The restore failed (is Minecraft running?). Your files were put back "
                    + "and nothing was changed.", e);
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private static String requireSafeId(String id) {
        if (!SafePaths.isSafeFolderName(id)) throw new BackupException("Invalid instance name for a backup.");
        return id;
    }

    private static void moveFile(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteTreeStrict(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteTreeStrict(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not remove {}", path, e);
        }
    }

    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // Not empty: leave it.
        }
    }
}
