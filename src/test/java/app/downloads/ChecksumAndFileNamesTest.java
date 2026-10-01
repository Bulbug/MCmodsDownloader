package app.downloads;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumAndFileNamesTest {

    @TempDir
    Path tempDir;

    @Test
    void parseDetectsAlgorithmByLength() {
        assertEquals(Checksum.Algorithm.SHA1, Checksum.parse("a".repeat(40)).algorithm());
        assertEquals(Checksum.Algorithm.SHA256, Checksum.parse("B".repeat(64)).algorithm());
        assertEquals(Checksum.Algorithm.SHA512, Checksum.parse(" " + "c".repeat(128) + " ").algorithm());
        assertEquals("b".repeat(64), Checksum.parse("B".repeat(64)).hex());
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Checksum.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> Checksum.parse("z".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> Checksum.parse(null));
    }

    @Test
    void computeMatchesKnownValues() throws IOException {
        Path file = tempDir.resolve("hello.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", Checksum.compute(Checksum.Algorithm.SHA1, file));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Checksum.compute(Checksum.Algorithm.SHA256, file));
    }

    @Test
    void sanitizeKeepsNormalModNames() {
        assertEquals("sodium-fabric-0.6.0+mc1.21.jar", FileNames.sanitize("sodium-fabric-0.6.0+mc1.21.jar"));
    }

    @Test
    void sanitizeRemovesDangerousCharacters() {
        assertEquals("evil.jar", FileNames.sanitize("../evil.jar").replace("_", ""));
        assertFalse(FileNames.sanitize("a/b\\c:d*e?.jar").matches(".*[/\\\\:*?].*"));
        assertEquals("download", FileNames.sanitize("   "));
        assertEquals("download", FileNames.sanitize(null));
        assertEquals("_CON.txt", FileNames.sanitize("CON.txt"));
    }

    @Test
    void sanitizeShortensLongNamesButKeepsExtension() {
        String result = FileNames.sanitize("a".repeat(300) + ".jar");
        assertTrue(result.length() <= 120);
        assertTrue(result.endsWith(".jar"));
    }

    @Test
    void fromUrlUsesLastPathSegment() {
        assertEquals("mod-1.0.jar", FileNames.fromUrl(URI.create("https://cdn.example.com/data/abc/mod-1.0.jar?token=1")));
        assertEquals("download", FileNames.fromUrl(URI.create("https://example.com/")));
        assertEquals("a b.jar", FileNames.fromUrl(URI.create("https://example.com/a%20b.jar")));
    }

    @Test
    void blockedExtensions() {
        assertTrue(FileNames.hasBlockedExtension("setup.EXE"));
        assertTrue(FileNames.hasBlockedExtension("x.ps1"));
        assertFalse(FileNames.hasBlockedExtension("mod.jar"));
        assertFalse(FileNames.hasBlockedExtension("pack.zip"));
        assertFalse(FileNames.hasBlockedExtension("noextension"));
    }
}
