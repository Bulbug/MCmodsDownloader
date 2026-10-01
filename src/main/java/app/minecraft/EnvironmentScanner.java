package app.minecraft;

import java.nio.file.Path;
import java.util.List;

/** Runs all detection in one call. Call it from a background thread, never the UI thread. */
public final class EnvironmentScanner {

    private EnvironmentScanner() { }

    public record ScanResult(MinecraftInstallation minecraft, List<JavaInstall> javaInstalls) { }

    /** @param minecraftDirOverride folder from Settings, or null/blank to auto-detect */
    public static ScanResult scan(String minecraftDirOverride) {
        Path dir = (minecraftDirOverride == null || minecraftDirOverride.isBlank())
                ? MinecraftDetector.defaultDirectory()
                : Path.of(minecraftDirOverride.trim());
        MinecraftInstallation minecraft = new MinecraftDetector().detect(dir);
        List<JavaInstall> java = JavaDetector.forThisMachine(dir).detect();
        return new ScanResult(minecraft, java);
    }
}
