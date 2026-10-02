package app.mods;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ModManagerTest {

    @TempDir
    Path tempDir;

    private Path instanceDir;
    private Path mods;
    private InstalledContentStore store;
    private ModManager manager;

    @BeforeEach
    void setUp() throws IOException {
        instanceDir = tempDir.resolve("instances").resolve("Test");
        mods = instanceDir.resolve("game").resolve("mods");
        Files.createDirectories(mods);
        store = new InstalledContentStore(instanceDir);
        manager = new ModManager();
    }

    private static InstalledMod mod(String id, boolean explicit, String... requires) {
        return new InstalledMod(id, "Mod " + id, "v-" + id, "1.0", id + ".jar", null, explicit, 0,
                List.of(requires), List.of());
    }

    /** Writes the jar files and the record for the given mods. */
    private void install(InstalledMod... installed) throws IOException {
        for (InstalledMod m : installed) Files.writeString(mods.resolve(m.fileName()), "content of " + m.projectId());
        store.save(List.of(installed));
    }

    private ManagedMod managed(String projectId) {
        return manager.list(instanceDir).stream().filter(m -> projectId.equals(m.projectId())).findFirst().orElseThrow();
    }

    private static List<String> ids(List<InstalledMod> mods) {
        return mods.stream().map(InstalledMod::projectId).toList();
    }

    // ---- listing ---------------------------------------------------------------------

    @Test
    void listShowsTrackedMissingAndUnmanagedFilesAndIgnoresOtherFiles() throws IOException {
        install(mod("A", true), mod("B", false));
        Files.delete(mods.resolve("B.jar"));                       // tracked but file removed by hand
        Files.writeString(mods.resolve("handmade.jar"), "x");      // not installed by this app
        Files.writeString(mods.resolve("notes.txt"), "ignored");

        List<ManagedMod> list = manager.list(instanceDir);

        assertEquals(3, list.size());
        ManagedMod a = list.stream().filter(m -> "A".equals(m.projectId())).findFirst().orElseThrow();
        assertTrue(a.tracked() && a.fileExists() && a.explicit());
        ManagedMod b = list.stream().filter(m -> "B".equals(m.projectId())).findFirst().orElseThrow();
        assertTrue(b.tracked() && !b.fileExists() && !b.explicit());
        ManagedMod handmade = list.stream().filter(m -> !m.tracked()).findFirst().orElseThrow();
        assertEquals("handmade", handmade.title());
        assertEquals("handmade.jar", handmade.fileName());
    }

    @Test
    void listWorksOnAnInstanceWithoutAModsFolder() throws IOException {
        Files.delete(mods);
        assertTrue(manager.list(instanceDir).isEmpty());
    }

    // ---- planning --------------------------------------------------------------------

    @Test
    void dependentsIncludeModsThatNeedTheTargetThroughAChain() throws IOException {
        // TOP needs MID, MID needs LIB. Removing LIB breaks MID directly and TOP indirectly.
        install(mod("LIB", false), mod("MID", false, "LIB"), mod("TOP", true, "MID"), mod("SOLO", true));

        RemovalPlan plan = manager.planRemoval(instanceDir, managed("LIB"));

        assertTrue(plan.hasDependents());
        assertEquals(List.of("MID", "TOP"), ids(plan.dependents()));
    }

    @Test
    void removingAModNobodyNeedsHasNoWarnings() throws IOException {
        install(mod("A", true), mod("B", true));
        RemovalPlan plan = manager.planRemoval(instanceDir, managed("A"));
        assertFalse(plan.hasDependents());
        assertTrue(plan.unusedDependencies().isEmpty());
    }

    @Test
    void unusedDependenciesAreFoundThroughChains() throws IOException {
        // A (chosen) needs B, B needs C. Both are dependency-only, so removing A frees both.
        install(mod("A", true, "B"), mod("B", false, "C"), mod("C", false));

        RemovalPlan plan = manager.planRemoval(instanceDir, managed("A"));

        assertEquals(List.of("B", "C"), ids(plan.unusedDependencies()));
    }

    @Test
    void dependenciesStillNeededByOtherModsAreKept() throws IOException {
        // A and D both need C. Removing A frees B but must keep C.
        install(mod("A", true, "B", "C"), mod("B", false), mod("C", false), mod("D", true, "C"));

        RemovalPlan plan = manager.planRemoval(instanceDir, managed("A"));

        assertEquals(List.of("B"), ids(plan.unusedDependencies()));
    }

    @Test
    void modsTheUserChoseThemselvesAreNeverOfferedAsUnused() throws IOException {
        install(mod("A", true, "B"), mod("B", true)); // the user installed B on purpose too
        assertTrue(manager.planRemoval(instanceDir, managed("A")).unusedDependencies().isEmpty());
    }

    @Test
    void unrelatedDependencyOnlyModsAreNotTouched() throws IOException {
        install(mod("A", true), mod("ORPHAN", false)); // nothing required it before either
        assertTrue(manager.planRemoval(instanceDir, managed("A")).unusedDependencies().isEmpty());
    }

    // ---- removing --------------------------------------------------------------------

    @Test
    void removeMovesTheFileToTheRemovedFolderInsteadOfDeletingIt() throws IOException {
        install(mod("A", true), mod("B", true));

        RemovalResult result = manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("A")), false);

        assertFalse(Files.exists(mods.resolve("A.jar")));
        assertTrue(Files.exists(mods.resolve("B.jar")));
        assertEquals(List.of("A.jar"), result.movedFiles());
        assertEquals("content of A", Files.readString(result.removedFolder().resolve("A.jar")));
        assertTrue(result.removedFolder().startsWith(instanceDir.resolve(ModManager.REMOVED_FOLDER)));
        assertEquals(List.of("B"), ids(store.load()));
    }

    @Test
    void removeCanAlsoTakeTheUnusedDependencies() throws IOException {
        install(mod("A", true, "B"), mod("B", false), mod("KEEP", true));

        RemovalResult result = manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("A")), true);

        assertEquals(2, result.movedFiles().size());
        assertFalse(Files.exists(mods.resolve("B.jar")));
        assertEquals(List.of("KEEP"), ids(store.load()));
    }

    @Test
    void dependenciesAreKeptWhenNotRequested() throws IOException {
        install(mod("A", true, "B"), mod("B", false));

        manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("A")), false);

        assertTrue(Files.exists(mods.resolve("B.jar")));
        assertEquals(List.of("B"), ids(store.load()));
    }

    @Test
    void forgettingAModWhoseFileIsAlreadyGoneJustClearsTheRecord() throws IOException {
        install(mod("A", true));
        Files.delete(mods.resolve("A.jar"));

        RemovalResult result = manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("A")), false);

        assertTrue(result.movedFiles().isEmpty());
        assertNull(result.removedFolder());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void unmanagedFilesCanBeRemovedWithoutTouchingTheRecord() throws IOException {
        install(mod("A", true));
        Files.writeString(mods.resolve("handmade.jar"), "mine");
        ManagedMod handmade = manager.list(instanceDir).stream().filter(m -> !m.tracked()).findFirst().orElseThrow();

        RemovalResult result = manager.remove(instanceDir, manager.planRemoval(instanceDir, handmade), false);

        assertEquals("mine", Files.readString(result.removedFolder().resolve("handmade.jar")));
        assertFalse(Files.exists(mods.resolve("handmade.jar")));
        assertEquals(List.of("A"), ids(store.load()));
    }

    @Test
    void twoRemovalsInTheSameSecondDoNotCollide() throws IOException {
        install(mod("A", true), mod("B", true));

        RemovalResult first = manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("A")), false);
        RemovalResult second = manager.remove(instanceDir, manager.planRemoval(instanceDir, managed("B")), false);

        assertNotEquals(first.removedFolder(), second.removedFolder());
        assertTrue(Files.exists(first.removedFolder().resolve("A.jar")));
        assertTrue(Files.exists(second.removedFolder().resolve("B.jar")));
    }

    // ---- safety ----------------------------------------------------------------------

    @Test
    void aTamperedRecordCannotMakeTheAppMoveFilesOutsideTheModsFolder() throws IOException {
        Path outside = tempDir.resolve("precious.jar");
        Files.writeString(outside, "do not touch");
        store.save(List.of(new InstalledMod("EVIL", "Evil", "v", "1", "../../../../precious.jar", null,
                true, 0, List.of(), List.of())));

        ManagedMod evil = manager.list(instanceDir).get(0);
        assertFalse(evil.fileExists()); // listed, but not treated as a real file
        RemovalPlan plan = manager.planRemoval(instanceDir, evil);

        assertThrows(InstallException.class, () -> manager.remove(instanceDir, plan, false));
        assertEquals("do not touch", Files.readString(outside));
        assertEquals(1, store.load().size()); // nothing changed
    }

    @Test
    void unsafeFileNamesInTheRecordAreRejectedBeforeAnythingIsMoved() throws IOException {
        install(mod("A", true, "B"), mod("B", false));
        // Corrupt B's entry so its file name points outside, then ask to remove A together with B.
        List<InstalledMod> records = store.load();
        records.set(1, new InstalledMod("B", "Mod B", "v-B", "1.0", "..\\..\\x.jar", null, false, 0, List.of(), List.of()));
        store.save(records);

        RemovalPlan plan = manager.planRemoval(instanceDir, managed("A"));
        assertEquals(List.of("B"), ids(plan.unusedDependencies()));

        assertThrows(InstallException.class, () -> manager.remove(instanceDir, plan, true));
        assertTrue(Files.exists(mods.resolve("A.jar")), "A must not be moved when B's record is unsafe");
        assertEquals(2, store.load().size());
    }

    @Test
    void aFailureHalfwayUndoesTheMovesAndLeavesTheRecordUntouched() throws IOException {
        install(mod("A", true, "B"), mod("B", false));
        List<Path[]> calls = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        ModManager flaky = new ModManager((from, to) -> {
            calls.add(new Path[]{from, to});
            // Second forward move fails (as if the file were locked by a running game).
            if (count.incrementAndGet() == 2) throw new IOException("file is in use");
            Files.move(from, to);
        });

        RemovalPlan plan = flaky.planRemoval(instanceDir, managed("A"));
        InstallException e = assertThrows(InstallException.class, () -> flaky.remove(instanceDir, plan, true));

        assertTrue(e.getMessage().contains("Nothing was changed"), e.getMessage());
        assertTrue(Files.exists(mods.resolve("A.jar")), "first file must be put back");
        assertTrue(Files.exists(mods.resolve("B.jar")));
        assertEquals(2, store.load().size());
        assertFalse(Files.exists(instanceDir.resolve(ModManager.REMOVED_FOLDER)), "empty .removed folder is cleaned up");
    }

    @Test
    void aFailureWhileSavingTheRecordUndoesTheMove() throws IOException {
        install(mod("A", true));
        // Make saving fail: a directory occupies the temp-file name the store writes to.
        Files.createDirectories(instanceDir.resolve("installed-content.json.tmp"));

        RemovalPlan plan = manager.planRemoval(instanceDir, managed("A"));
        assertThrows(InstallException.class, () -> manager.remove(instanceDir, plan, false));

        assertTrue(Files.exists(mods.resolve("A.jar")));
        assertEquals(1, store.load().size());
    }
}
