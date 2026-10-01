package app.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFolderIsReportedAsNotFound() {
        MinecraftInstallation result = new MinecraftDetector().detect(tempDir.resolve("nothing"));
        assertFalse(result.exists());
        assertTrue(result.versions().isEmpty());
    }

    @Test
    void readsVersionsLoadersAndJavaRequirement() throws IOException {
        Files.writeString(tempDir.resolve("launcher_profiles.json"), "{}");
        writeVersion("1.21.8", "{\"id\":\"1.21.8\",\"type\":\"release\",\"javaVersion\":{\"majorVersion\":21}}");
        // Modded version has no javaVersion of its own and inherits from its parent.
        writeVersion("fabric-loader-0.16.0-1.21.8",
                "{\"id\":\"fabric-loader-0.16.0-1.21.8\",\"type\":\"release\",\"inheritsFrom\":\"1.21.8\"}");
        writeVersion("1.16.5", "{\"id\":\"1.16.5\",\"type\":\"release\",\"javaVersion\":{\"majorVersion\":8}}");

        MinecraftInstallation result = new MinecraftDetector().detect(tempDir);

        assertTrue(result.exists());
        assertTrue(result.launcherProfilesFound());
        assertEquals(3, result.versions().size());

        MinecraftVersionInfo fabric = find(result, "fabric-loader-0.16.0-1.21.8");
        assertEquals("Fabric", fabric.loader());
        assertEquals(21, fabric.requiredJava());
        assertEquals(8, find(result, "1.16.5").requiredJava());
        assertEquals("Vanilla", find(result, "1.21.8").loader());
    }

    @Test
    void corruptVersionFileIsSkipped() throws IOException {
        writeVersion("broken", "{ not json");
        writeVersion("1.20.1", "{\"id\":\"1.20.1\",\"type\":\"release\"}");

        MinecraftInstallation result = new MinecraftDetector().detect(tempDir);

        assertEquals(1, result.versions().size());
        assertEquals("1.20.1", result.versions().get(0).id());
        assertEquals(0, result.versions().get(0).requiredJava());
    }

    @Test
    void neoForgeIsNotMistakenForForge() {
        assertEquals("NeoForge", MinecraftDetector.detectLoader("neoforge-21.1.0"));
        assertEquals("Forge", MinecraftDetector.detectLoader("1.20.1-forge-47.2.0"));
        assertEquals("Quilt", MinecraftDetector.detectLoader("quilt-loader-0.26.0-1.21"));
    }

    private void writeVersion(String id, String json) throws IOException {
        Path dir = tempDir.resolve("versions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json);
    }

    private static MinecraftVersionInfo find(MinecraftInstallation r, String id) {
        return r.versions().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }
}
