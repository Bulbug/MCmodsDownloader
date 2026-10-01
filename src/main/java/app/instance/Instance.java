package app.instance;

/**
 * Metadata of one isolated Minecraft instance (stored as instance.json).
 *
 * @param id               folder name of the instance; unique and safe to use as a path segment
 * @param name             name shown to the user
 * @param loader           Vanilla, Fabric, Quilt, Forge or NeoForge
 * @param loaderVersion    loader version, or null if not chosen yet
 * @param createdAt        epoch milliseconds
 * @param lastPlayed       epoch milliseconds, 0 = never
 * @param javaPath         chosen Java home, or null for automatic
 * @param memoryMb         maximum memory for the game
 */
public record Instance(String id, String name, String minecraftVersion, String loader,
                       String loaderVersion, long createdAt, long lastPlayed,
                       String javaPath, int memoryMb) {

    public static final int DEFAULT_MEMORY_MB = 4096;

    public Instance withName(String newName) {
        return new Instance(id, newName, minecraftVersion, loader, loaderVersion,
                createdAt, lastPlayed, javaPath, memoryMb);
    }

    public Instance withIdentity(String newId, String newName, long newCreatedAt) {
        return new Instance(newId, newName, minecraftVersion, loader, loaderVersion,
                newCreatedAt, 0L, javaPath, memoryMb);
    }
}
