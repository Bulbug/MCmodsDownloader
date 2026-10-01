package app.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InstalledContentStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileMeansNothingInstalled() {
        assertTrue(new InstalledContentStore(dir).load().isEmpty());
    }

    @Test
    void saveAndLoadRoundTrip() {
        InstalledContentStore store = new InstalledContentStore(dir);
        store.save(List.of(new InstalledMod("P1", "Sodium", "v1", "0.6.0", "sodium.jar", "abc", true, 123L,
                List.of("FAPI"), List.of("OTHER"))));

        List<InstalledMod> loaded = store.load();

        assertEquals(1, loaded.size());
        InstalledMod m = loaded.get(0);
        assertEquals("Sodium", m.title());
        assertTrue(m.explicit());
        assertEquals(List.of("FAPI"), m.requires());
        assertEquals(List.of("OTHER"), m.incompatibleWith());
    }

    @Test
    void missingListsInTheFileBecomeEmptyLists() throws IOException {
        Files.writeString(dir.resolve("installed-content.json"),
                "{\"mods\":[{\"projectId\":\"P1\",\"title\":\"T\",\"fileName\":\"t.jar\"}]}");
        InstalledMod m = new InstalledContentStore(dir).load().get(0);
        assertTrue(m.requires().isEmpty());
        assertTrue(m.incompatibleWith().isEmpty());
    }

    @Test
    void corruptFileIsKeptAndTreatedAsEmpty() throws IOException {
        Files.writeString(dir.resolve("installed-content.json"), "{ not json");
        assertTrue(new InstalledContentStore(dir).load().isEmpty());
        assertTrue(Files.exists(dir.resolve("installed-content.json.corrupt")));
    }

    @Test
    void entriesWithoutIdentityAreIgnored() throws IOException {
        Files.writeString(dir.resolve("installed-content.json"),
                "{\"mods\":[{\"title\":\"no ids\"},{\"projectId\":\"P1\",\"fileName\":\"a.jar\"}]}");
        assertEquals(1, new InstalledContentStore(dir).load().size());
    }
}
