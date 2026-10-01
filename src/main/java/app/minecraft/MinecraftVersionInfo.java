package app.minecraft;

/**
 * A version folder found in .minecraft/versions.
 *
 * @param inheritsFrom parent version id for modded versions, or null
 * @param loader       Vanilla, Fabric, Quilt, Forge, NeoForge or OptiFine
 * @param requiredJava minimum Java major version from the version file, 0 if unknown
 */
public record MinecraftVersionInfo(String id, String type, String inheritsFrom,
                                   String loader, int requiredJava) { }
