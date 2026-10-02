package app.backup;

import app.instance.FileInstanceRepository;
import app.instance.Instance;
import app.instance.InstanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {

    @TempDir
    Path tempDir;

    private Path instancesRoot;
    private Path backupsRoot;
    private Path instanceDir;
    private Path game;
    private Instance instance;
    private BackupService service;
    private InstanceService instances;
    private FileInstanceRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        instancesRoot = tempDir.resolve("instances");
        backupsRoot = tempDir.resolve("backups");
        repository = new FileInstanceRepository(instancesRoot);
        instances = new InstanceService(repository);
        instance = instances.create("My Pack", "1.21.8", "Fabric", "0.16.0");
        instanceDir = repository.directoryOf(instance.id());
        game = instanceDir.resolve("game");
        service = new BackupService(backupsRoot);

        write(game.resolve("mods/sodium.jar"), "sodium");
        write(game.resolve("config/sodium.json"), "{\"a\":1}");
        write(game.resolve("saves/World1/level.dat"), "world data");
        write(game.resolve("saves/World1/region/r.0.0.mca"), "region");
        write(game.resolve("resourcepacks/pack.zip"), "pack");
        write(game.resolve("options.txt"), "fov:70");
        write(game.resolve("servers.dat"), "servers");
        write(instanceDir.resolve("installed-content.json"), "{\"mods\":[]}");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private BackupInfo backup(Set<BackupPart> parts) {
        return service.create(instance, instanceDir, parts, false, "", () -> false, (d, t) -> { });
    }

    private BackupInfo backupEntire() {
        return service.create(instance, instanceDir, EnumSet.noneOf(BackupPart.class), true, "", () -> false, (d, t) -> { });
    }

    private static List<String> entryNames(Path zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    private RestoreResult restore(BackupInfo info) {
        return service.restoreInto(info, instance, instanceDir, () -> false, (d, t) -> { });
    }

    // ---- creating --------------------------------------------------------------------

    @Test
    void backsUpOnlyTheChosenParts() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS, BackupPart.CONFIG));

        List<String> names = entryNames(info.file());
        assertTrue(names.contains("game/mods/sodium.jar"));
        assertTrue(names.contains("installed-content.json"), "the record travels with the mods");
        assertTrue(names.contains("game/config/sodium.json"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("game/saves")));
        assertFalse(names.contains("game/options.txt"));
        assertTrue(info.valid());
        assertEquals(List.of(BackupPart.MODS, BackupPart.CONFIG), info.parts().stream().sorted().toList());
        assertEquals("Mods, Config files", info.contents());
        assertTrue(info.sizeBytes() > 0);
        assertEquals(instance.id(), info.instanceId());
        assertEquals("My Pack", info.instanceName());
    }

    @Test
    void gameOptionsMeansOnlyTheFilesDirectlyInTheGameFolder() throws IOException {
        List<String> names = entryNames(backup(EnumSet.of(BackupPart.OPTIONS)).file());
        assertTrue(names.contains("game/options.txt"));
        assertTrue(names.contains("game/servers.dat"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("game/mods")));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("game/saves")));
    }

    @Test
    void entireInstanceIncludesEverythingExceptTemporaryFolders() throws IOException {
        write(instanceDir.resolve(".staging/123/partial.jar"), "temp");
        write(instanceDir.resolve(".removed/20260101-000000/old.jar"), "removed");

        BackupInfo info = backupEntire();
        List<String> names = entryNames(info.file());

        assertTrue(info.entireInstance());
        assertEquals("Entire instance", info.contents());
        assertTrue(names.contains("instance.json"));
        assertTrue(names.contains("game/saves/World1/level.dat"));
        assertTrue(names.contains("game/options.txt"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith(".staging")));
        assertFalse(names.stream().anyMatch(n -> n.startsWith(".removed")));
    }

    @Test
    void sessionLockFilesAreSkipped() throws IOException {
        write(game.resolve("saves/World1/session.lock"), "locked by minecraft");
        assertFalse(entryNames(backup(EnumSet.of(BackupPart.SAVES)).file()).stream().anyMatch(n -> n.endsWith("session.lock")));
    }

    @Test
    void symbolicLinksAreNeverFollowed() throws IOException {
        Path outside = tempDir.resolve("outside");
        write(outside.resolve("secret.txt"), "private");
        try {
            Files.createSymbolicLink(game.resolve("config/link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            return; // the platform does not allow creating links: nothing to test
        }
        List<String> names = entryNames(backup(EnumSet.of(BackupPart.CONFIG)).file());
        assertFalse(names.stream().anyMatch(n -> n.contains("secret.txt")));
    }

    @Test
    void emptyChosenFoldersStillGetAnEntryAndNothingChosenIsRejected() throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        assertTrue(entryNames(backup(EnumSet.of(BackupPart.SHADER_PACKS)).file()).contains("game/shaderpacks/"));
        assertThrows(BackupException.class, () -> backup(EnumSet.noneOf(BackupPart.class)));
    }

    @Test
    void progressIsReportedAndCancellingLeavesNothingBehind() throws IOException {
        write(game.resolve("saves/Big/data.bin"), "x".repeat(300_000));
        List<Long> seen = new ArrayList<>();
        backup(EnumSet.of(BackupPart.SAVES));
        service.create(instance, instanceDir, EnumSet.of(BackupPart.SAVES), false, "", () -> false,
                (done, total) -> seen.add(done));
        assertTrue(seen.size() > 1);
        assertTrue(seen.get(seen.size() - 1) > 300_000);

        AtomicBoolean cancel = new AtomicBoolean(false);
        int before = service.list(instance.id()).size();
        assertThrows(BackupException.class, () -> service.create(instance, instanceDir,
                EnumSet.of(BackupPart.SAVES), false, "", cancel::get, (done, total) -> cancel.set(done > 1000)));

        assertEquals(before, service.list(instance.id()).size());
        try (var files = Files.list(backupsRoot.resolve(instance.id()))) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".part")), "no partial file may remain");
        }
    }

    @Test
    void twoBackupsInTheSameSecondDoNotCollide() {
        BackupInfo a = backup(EnumSet.of(BackupPart.MODS));
        BackupInfo b = backup(EnumSet.of(BackupPart.MODS));
        assertNotEquals(a.file(), b.file());
        assertEquals(2, service.list(instance.id()).size());
    }

    // ---- listing, deleting, exporting ----------------------------------------------------

    @Test
    void listAllCoversEveryInstanceNewestFirstAndFlagsDamagedFiles() throws IOException {
        BackupInfo first = backup(EnumSet.of(BackupPart.MODS));
        Instance other = instances.create("Other", "1.20.1", "Forge", null);
        BackupInfo second = service.create(other, repository.directoryOf(other.id()), EnumSet.of(BackupPart.CONFIG),
                false, "", () -> false, (d, t) -> { });
        Files.writeString(backupsRoot.resolve(other.id()).resolve("broken.zip"), "not a zip");

        List<BackupInfo> all = service.listAll();

        assertEquals(3, all.size());
        assertEquals(1, all.stream().filter(i -> !i.valid()).count());
        List<BackupInfo> valid = all.stream().filter(BackupInfo::valid).toList();
        assertTrue(valid.get(0).createdAt() >= valid.get(1).createdAt());
        assertTrue(all.stream().anyMatch(i -> i.file().equals(first.file())));
        assertTrue(all.stream().anyMatch(i -> i.file().equals(second.file())));
    }

    @Test
    void deleteRemovesTheFileAndEmptyFolderButRefusesOutsideFiles() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS));
        service.delete(info);
        assertFalse(Files.exists(info.file()));
        assertFalse(Files.exists(backupsRoot.resolve(instance.id())));

        Path outside = tempDir.resolve("precious.zip");
        Files.writeString(outside, "keep");
        BackupInfo fake = new BackupInfo(outside, true, "x", "x", "1", "Fabric", null, 0, 4, List.of(), false, "");
        assertThrows(RuntimeException.class, () -> service.delete(fake));
        assertTrue(Files.exists(outside));
    }

    @Test
    void exportProducesAnIdenticalZip() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS));
        Path target = tempDir.resolve("exported.zip");
        service.export(info, target);
        assertArrayEquals(Files.readAllBytes(info.file()), Files.readAllBytes(target));
    }

    // ---- restoring into an instance -----------------------------------------------------

    @Test
    void restoreReplacesTheChosenFoldersAndLeavesTheRestAlone() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS, BackupPart.OPTIONS));

        write(game.resolve("mods/sodium.jar"), "CHANGED");
        write(game.resolve("mods/extra.jar"), "added later");
        write(game.resolve("options.txt"), "fov:110");
        write(game.resolve("saves/World1/level.dat"), "progress made after the backup");

        RestoreResult result = restore(info);

        assertEquals("sodium", Files.readString(game.resolve("mods/sodium.jar")));
        assertFalse(Files.exists(game.resolve("mods/extra.jar")), "files added after the backup disappear from restored folders");
        assertEquals("fov:70", Files.readString(game.resolve("options.txt")));
        assertEquals("progress made after the backup", Files.readString(game.resolve("saves/World1/level.dat")),
                "worlds were not part of this backup and must stay untouched");
        assertTrue(result.unitsRestored() >= 2);
        assertFalse(Files.exists(instanceDir.resolve(".restore-staging")));
        assertFalse(Files.exists(instanceDir.resolve(".restore-old")));
    }

    @Test
    void restoreFirstMakesAnAutomaticSafetyCopyOfWhatItReplaces() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.SAVES));
        write(game.resolve("saves/World1/level.dat"), "precious progress");

        RestoreResult result = restore(info);

        assertEquals("world data", Files.readString(game.resolve("saves/World1/level.dat")));
        assertNotNull(result.safetyBackup());
        assertTrue(result.safetyBackup().note().contains("Automatic"));
        try (ZipFile zip = new ZipFile(result.safetyBackup().file().toFile())) {
            String saved = new String(zip.getInputStream(zip.getEntry("game/saves/World1/level.dat")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals("precious progress", saved, "the state before the restore must be recoverable");
        }
    }

    @Test
    void restoringAnEmptyFolderBackupEmptiesTheFolder() throws IOException {
        Files.createDirectories(game.resolve("shaderpacks"));
        BackupInfo info = backup(EnumSet.of(BackupPart.SHADER_PACKS));
        write(game.resolve("shaderpacks/new.zip"), "added later");

        restore(info);

        assertTrue(Files.isDirectory(game.resolve("shaderpacks")));
        try (var files = Files.list(game.resolve("shaderpacks"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void restoreNeverChangesTheInstanceIdentity() throws IOException {
        BackupInfo info = backupEntire();
        String before = Files.readString(instanceDir.resolve("instance.json"));
        instances.rename(instance.id(), "Renamed Since");

        restore(info);

        assertEquals("Renamed Since", repository.find(instance.id()).orElseThrow().name());
        assertNotEquals(before, Files.readString(instanceDir.resolve("instance.json")));
    }

    @Test
    void aFailureHalfwayThroughTheSwapPutsEverythingBack() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS, BackupPart.CONFIG, BackupPart.SAVES));
        write(game.resolve("mods/sodium.jar"), "NEWER MOD");
        write(game.resolve("config/sodium.json"), "NEWER CONFIG");
        write(game.resolve("saves/World1/level.dat"), "NEWER WORLD");

        AtomicInteger moves = new AtomicInteger();
        BackupService flaky = new BackupService(backupsRoot, (from, to) -> {
            // Fail on the 4th move of the restore (after two units were already swapped).
            if (moves.incrementAndGet() == 4) throw new IOException("file is in use");
            Files.move(from, to);
        });

        BackupException e = assertThrows(BackupException.class,
                () -> flaky.restoreInto(info, instance, instanceDir, () -> false, (d, t) -> { }));

        assertTrue(e.getMessage().contains("put back"), e.getMessage());
        assertEquals("NEWER MOD", Files.readString(game.resolve("mods/sodium.jar")));
        assertEquals("NEWER CONFIG", Files.readString(game.resolve("config/sodium.json")));
        assertEquals("NEWER WORLD", Files.readString(game.resolve("saves/World1/level.dat")));
        assertFalse(Files.exists(instanceDir.resolve(".restore-staging")));
        assertFalse(Files.exists(instanceDir.resolve(".restore-old")));
    }

    @Test
    void cancellingARestoreChangesNothing() throws IOException {
        BackupInfo info = backup(EnumSet.of(BackupPart.SAVES));
        write(game.resolve("saves/World1/level.dat"), "current");

        assertThrows(BackupException.class, () -> service.restoreInto(info, instance, instanceDir, () -> true, (d, t) -> { }));

        assertEquals("current", Files.readString(game.resolve("saves/World1/level.dat")));
        assertFalse(Files.exists(instanceDir.resolve(".restore-staging")));
    }

    @Test
    void unreadableBackupsAreRefused() throws IOException {
        Path junk = backupsRoot.resolve(instance.id()).resolve("junk.zip");
        Files.createDirectories(junk.getParent());
        Files.writeString(junk, "not a zip");
        BackupInfo info = service.readInfo(junk);

        assertFalse(info.valid());
        assertThrows(BackupException.class, () -> restore(info));
    }

    // ---- restoring as a new instance ----------------------------------------------------

    @Test
    void restoreAsNewWorksEvenAfterTheOriginalWasDeleted() throws IOException {
        BackupInfo info = backupEntire();
        instances.delete(instance.id());
        assertTrue(instances.list().isEmpty());

        Instance restored = service.restoreAsNew(info, instances, repository, () -> false, (d, t) -> { });

        assertEquals("My Pack (restored)", restored.name());
        assertEquals("1.21.8", restored.minecraftVersion());
        assertEquals("Fabric", restored.loader());
        assertEquals("0.16.0", restored.loaderVersion());
        Path dir = repository.directoryOf(restored.id());
        assertEquals("world data", Files.readString(dir.resolve("game/saves/World1/level.dat")));
        assertEquals("sodium", Files.readString(dir.resolve("game/mods/sodium.jar")));
        assertEquals("fov:70", Files.readString(dir.resolve("game/options.txt")));
        // The instance identity comes from the new instance, not from the backup's instance.json.
        assertEquals(restored.id(), repository.find(restored.id()).orElseThrow().id());
        assertEquals(1, instances.list().size());
    }

    @Test
    void restoreAsNewPicksAFreeName() {
        BackupInfo info = backup(EnumSet.of(BackupPart.MODS));
        Instance first = service.restoreAsNew(info, instances, repository, () -> false, (d, t) -> { });
        Instance second = service.restoreAsNew(info, instances, repository, () -> false, (d, t) -> { });
        assertEquals("My Pack (restored)", first.name());
        assertEquals("My Pack (restored 2)", second.name());
    }

    @Test
    void aFailedRestoreAsNewLeavesNoHalfBuiltInstance() throws IOException {
        Path evil = backupsRoot.resolve(instance.id()).resolve("evil.zip");
        buildZip(evil, validMetadata(), new String[][]{{"game/mods/ok.jar", "fine"}, {"game/../../escape.txt", "bad"}});
        BackupInfo info = service.readInfo(evil);
        int before = instances.list().size();

        assertThrows(BackupException.class,
                () -> service.restoreAsNew(info, instances, repository, () -> false, (d, t) -> { }));

        assertEquals(before, instances.list().size());
    }

    // ---- hostile archives ---------------------------------------------------------------

    private String validMetadata() {
        return "{\"formatVersion\":1,\"instanceId\":\"" + instance.id() + "\",\"instanceName\":\"My Pack\","
                + "\"minecraftVersion\":\"1.21.8\",\"loader\":\"Fabric\",\"createdAt\":1,\"parts\":[\"MODS\"],"
                + "\"entireInstance\":false,\"note\":\"\"}";
    }

    private static void buildZip(Path file, String metadataJson, String[][] entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("backup.json"));
            zip.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String[] e : entries) {
                zip.putNextEntry(new ZipEntry(e[0]));
                zip.write(e[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void entriesThatTryToEscapeAreRejectedAndNothingIsWrittenOutside() throws IOException {
        String[] hostile = {"game/../../../evil.txt", "/etc/evil.txt", "game\\..\\..\\evil.txt",
                "game/saves/CON/level.dat", "game/mods/C:evil.jar", "game/mods/trailing./x.jar"};
        for (int i = 0; i < hostile.length; i++) {
            Path zip = backupsRoot.resolve(instance.id()).resolve("hostile" + i + ".zip");
            buildZip(zip, validMetadata(), new String[][]{{"game/mods/good.jar", "ok"}, {hostile[i], "bad"}});
            BackupInfo info = service.readInfo(zip);
            write(game.resolve("mods/sodium.jar"), "ORIGINAL");

            assertThrows(BackupException.class, () -> restore(info), hostile[i]);

            assertEquals("ORIGINAL", Files.readString(game.resolve("mods/sodium.jar")), hostile[i]);
            assertFalse(Files.exists(tempDir.resolve("evil.txt")), hostile[i]);
            assertFalse(Files.exists(instancesRoot.resolve("evil.txt")), hostile[i]);
            assertFalse(Files.exists(instanceDir.resolve(".restore-staging")), hostile[i]);
        }
    }

    @Test
    void unknownTopLevelEntriesAreIgnoredAndInstanceJsonIsNeverOverwritten() throws IOException {
        Path zip = backupsRoot.resolve(instance.id()).resolve("odd.zip");
        buildZip(zip, validMetadata(), new String[][]{
                {"game/mods/new.jar", "new"}, {"versions/1.21.8/1.21.8.jar", "ignored"},
                {"instance.json", "{\"id\":\"hacked\"}"}, {"readme.txt", "ignored"}});
        String identityBefore = Files.readString(instanceDir.resolve("instance.json"));

        restore(service.readInfo(zip));

        assertEquals("new", Files.readString(game.resolve("mods/new.jar")));
        assertEquals(identityBefore, Files.readString(instanceDir.resolve("instance.json")));
        assertFalse(Files.exists(instanceDir.resolve("versions")));
        assertFalse(Files.exists(instanceDir.resolve("readme.txt")));
    }

    @Test
    void anArchiveWithNothingRestorableIsRefused() throws IOException {
        Path zip = backupsRoot.resolve(instance.id()).resolve("empty.zip");
        buildZip(zip, validMetadata(), new String[][]{{"versions/x.jar", "ignored"}});
        assertThrows(BackupException.class, () -> restore(service.readInfo(zip)));
    }

    @Test
    void anArchiveWithoutDescriptionIsNotARestorableBackup() throws IOException {
        Path zip = backupsRoot.resolve(instance.id()).resolve("nometa.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("game/mods/a.jar"));
            z.write(1);
            z.closeEntry();
        }
        assertFalse(service.readInfo(zip).valid());
    }

    @Test
    void entryNameRulesCoverTheUsualTricks() {
        assertEquals("game/mods/a.jar", SafeEntryName.normalize("game/mods/a.jar"));
        assertEquals("game/saves", SafeEntryName.normalize("game/saves/"));
        for (String bad : new String[]{"", "/abs", "a/../b", "..", ".", "a//b", "a\\b", "C:/x", "a/\u0000b",
                "aux", "game/NUL.txt", "a/b.", "a/b ", "x".repeat(1001)}) {
            assertThrows(BackupException.class, () -> SafeEntryName.normalize(bad), bad);
        }
        assertThrows(BackupException.class, () -> SafeEntryName.normalize(null));
    }
}
