package app.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesMajorVersions() {
        assertEquals(21, JavaDetector.parseMajor("21.0.5"));
        assertEquals(8, JavaDetector.parseMajor("1.8.0_392"));
        assertEquals(17, JavaDetector.parseMajor("17"));
        assertEquals(22, JavaDetector.parseMajor("22-ea"));
        assertEquals(0, JavaDetector.parseMajor("unknown"));
        assertEquals(0, JavaDetector.parseMajor(null));
    }

    @Test
    void findsJavaHomeUnderSearchRootAndReadsReleaseFile() throws IOException {
        Path home = fakeJava(tempDir.resolve("root/jdk-21"), "21.0.5", "Eclipse Adoptium");

        List<JavaInstall> found = new JavaDetector(List.of(), List.of(tempDir.resolve("root"))).detect();

        assertEquals(1, found.size());
        assertEquals(21, found.get(0).major());
        assertEquals("Eclipse Adoptium", found.get(0).vendor());
        assertEquals(home.toAbsolutePath().normalize(), found.get(0).home().toAbsolutePath().normalize());
    }

    @Test
    void sameJavaFoundTwiceIsListedOnce() throws IOException {
        Path home = fakeJava(tempDir.resolve("root/jdk-17"), "17.0.9", "Microsoft");

        List<JavaInstall> found = new JavaDetector(List.of(home), List.of(tempDir.resolve("root"))).detect();

        assertEquals(1, found.size());
    }

    @Test
    void newestJavaComesFirst() throws IOException {
        fakeJava(tempDir.resolve("root/a"), "1.8.0_392", "Azul");
        fakeJava(tempDir.resolve("root/b"), "21.0.1", "Azul");

        List<JavaInstall> found = new JavaDetector(List.of(), List.of(tempDir.resolve("root"))).detect();

        assertEquals(List.of(21, 8), found.stream().map(JavaInstall::major).toList());
    }

    @Test
    void missingFoldersDoNotCrash() {
        List<JavaInstall> found = new JavaDetector(List.of(tempDir.resolve("nope")),
                List.of(tempDir.resolve("also-nope"))).detect();
        assertTrue(found.isEmpty());
    }

    private Path fakeJava(Path home, String version, String vendor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + vendor + "\"\n");
        return home;
    }
}
