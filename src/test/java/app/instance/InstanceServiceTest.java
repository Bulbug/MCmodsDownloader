package app.instance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstanceServiceTest {

    @TempDir
    Path tempDir;

    private Path root;
    private InstanceService service;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("instances");
        service = new InstanceService(new FileInstanceRepository(root));
    }

    @Test
    void createBuildsFolderLayoutAndMetadata() {
        Instance created = service.create("Fabric 1.21.8", "1.21.8", "fabric", null);

        assertEquals("Fabric-1.21.8", created.id());
        assertEquals("Fabric", created.loader());
        Path dir = root.resolve("Fabric-1.21.8");
        assertTrue(Files.isRegularFile(dir.resolve("instance.json")));
        for (String sub : new String[]{"mods", "config", "resourcepacks", "shaderpacks", "saves", "logs", "screenshots"}) {
            assertTrue(Files.isDirectory(dir.resolve("game").resolve(sub)), sub);
        }
        assertEquals(1, service.list().size());
    }

    @Test
    void invalidInputIsRejectedWithFriendlyMessages() {
        assertThrows(InstanceException.class, () -> service.create("  ", "1.21.8", "Vanilla", null));
        assertThrows(InstanceException.class, () -> service.create("x".repeat(65), "1.21.8", "Vanilla", null));
        assertThrows(InstanceException.class, () -> service.create("Ok", "", "Vanilla", null));
        assertThrows(InstanceException.class, () -> service.create("Ok", "1.21.8; rm -rf", "Vanilla", null));
        assertThrows(InstanceException.class, () -> service.create("Ok", "1.21.8", "Bukkit", null));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void duplicateNamesAreRejectedIgnoringCase() {
        service.create("My Pack", "1.20.1", "Forge", null);
        assertThrows(InstanceException.class, () -> service.create("my pack", "1.20.1", "Forge", null));
    }

    @Test
    void differentNamesWithSameFolderNameGetDistinctFolders() {
        Instance a = service.create("A B", "1.21", "Vanilla", null);
        Instance b = service.create("A-B", "1.21", "Vanilla", null);
        assertNotEquals(a.id(), b.id());
        assertEquals(2, service.list().size());
    }

    @Test
    void renameChangesNameButNotFolder() {
        Instance created = service.create("Old", "1.21", "Vanilla", null);
        Instance renamed = service.rename(created.id(), "New");
        assertEquals("New", renamed.name());
        assertEquals(created.id(), renamed.id());
        assertTrue(Files.isDirectory(root.resolve(created.id())));
        assertEquals("New", service.list().get(0).name());
    }

    @Test
    void duplicateCopiesFilesAndResetsLastPlayed() throws IOException {
        Instance original = service.create("Base", "1.21", "Fabric", "0.16.0");
        Files.writeString(root.resolve(original.id()).resolve("game/mods/sodium.jar"), "jar-bytes");

        Instance copy = service.duplicate(original.id());

        assertEquals("Base (copy)", copy.name());
        assertNotEquals(original.id(), copy.id());
        assertEquals("jar-bytes", Files.readString(root.resolve(copy.id()).resolve("game/mods/sodium.jar")));
        assertEquals(0L, copy.lastPlayed());
        assertEquals(copy.id(), service.list().stream()
                .filter(i -> i.name().equals("Base (copy)")).findFirst().orElseThrow().id());

        Instance second = service.duplicate(original.id());
        assertEquals("Base (copy 2)", second.name());
    }

    @Test
    void countWorldsCountsOnlyFoldersWithLevelDat() throws IOException {
        Instance created = service.create("Worlds", "1.21", "Vanilla", null);
        Path saves = root.resolve(created.id()).resolve("game/saves");
        Files.createDirectories(saves.resolve("World1"));
        Files.writeString(saves.resolve("World1/level.dat"), "x");
        Files.createDirectories(saves.resolve("NotAWorld"));

        assertEquals(1, service.countWorlds(created.id()));
    }

    @Test
    void deleteRemovesTheInstanceFolder() throws IOException {
        Instance created = service.create("Temp", "1.21", "Vanilla", null);
        Files.writeString(root.resolve(created.id()).resolve("game/mods/a.jar"), "x");

        service.delete(created.id());

        assertFalse(Files.exists(root.resolve(created.id())));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void deleteRefusesUnsafeIdsAndNonInstanceFolders() throws IOException {
        Path victim = tempDir.resolve("victim");
        Files.createDirectories(victim);
        Files.writeString(victim.resolve("important.txt"), "keep me");
        Files.createDirectories(root.resolve("Random-Folder"));

        assertThrows(InstanceException.class, () -> service.delete("../victim"));
        assertThrows(InstanceException.class, () -> service.delete("Random-Folder"));

        assertTrue(Files.exists(victim.resolve("important.txt")));
        assertTrue(Files.isDirectory(root.resolve("Random-Folder")));
    }

    @Test
    void corruptOrMismatchedMetadataIsIgnoredInTheList() throws IOException {
        service.create("Good", "1.21", "Vanilla", null);
        Path bad = root.resolve("Bad");
        Files.createDirectories(bad);
        Files.writeString(bad.resolve("instance.json"), "{ not json");
        Path liar = root.resolve("Liar");
        Files.createDirectories(liar);
        Files.writeString(liar.resolve("instance.json"),
                "{\"id\":\"SomethingElse\",\"name\":\"X\",\"minecraftVersion\":\"1.21\"}");

        assertEquals(1, service.list().size());
        assertEquals("Good", service.list().get(0).name());
    }
}
