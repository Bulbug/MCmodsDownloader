package app.instance;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafePathsTest {

    @Test
    void normalNameBecomesReadableFolder() {
        assertEquals("Fabric-1.21.8", SafePaths.toFolderName("Fabric 1.21.8"));
    }

    @Test
    void separatorsAndTraversalTricksAreNeutralised() {
        String folder = SafePaths.toFolderName("../../Windows/System32");
        assertFalse(folder.contains("/"));
        assertFalse(folder.contains("\\"));
        assertFalse(folder.contains(".."));
        assertEquals("Windows-System32", folder);
    }

    @Test
    void windowsReservedNamesAreAvoided() {
        assertEquals("CON-instance", SafePaths.toFolderName("CON"));
        assertEquals("nul.txt-instance", SafePaths.toFolderName("nul.txt"));
    }

    @Test
    void emptyOrSymbolOnlyNamesGetAFallback() {
        assertEquals("instance", SafePaths.toFolderName("***"));
        assertEquals("instance", SafePaths.toFolderName("..."));
    }

    @Test
    void longNamesAreShortened() {
        assertTrue(SafePaths.toFolderName("a".repeat(200)).length() <= 48);
    }

    @Test
    void safeFolderNameCheckRejectsBadIds() {
        assertTrue(SafePaths.isSafeFolderName("Fabric-1.21.8"));
        assertFalse(SafePaths.isSafeFolderName("../evil"));
        assertFalse(SafePaths.isSafeFolderName("a/b"));
        assertFalse(SafePaths.isSafeFolderName(""));
        assertFalse(SafePaths.isSafeFolderName(null));
        assertFalse(SafePaths.isSafeFolderName("CON"));
    }

    @Test
    void resolveInsideRejectsEscapes() {
        Path root = Path.of("instances").toAbsolutePath();
        assertThrows(InstanceException.class, () -> SafePaths.resolveInside(root, "../outside"));
        assertThrows(InstanceException.class, () -> SafePaths.resolveInside(root, ".."));
        assertEquals(root.resolve("ok").normalize(), SafePaths.resolveInside(root, "ok"));
    }
}
