package app.mods;

/**
 * A mod file shown in the "Installed mods" list.
 *
 * @param tracked    true if this app installed it (it is in installed-content.json)
 * @param explicit   true if the user chose it, false if it came in as a dependency
 * @param projectId  Modrinth project id, null for files this app did not install
 * @param fileExists false if the record says it is installed but the file is gone
 */
public record ManagedMod(String title, String versionNumber, String fileName, boolean tracked,
                         boolean explicit, String projectId, boolean fileExists, long sizeBytes) { }
