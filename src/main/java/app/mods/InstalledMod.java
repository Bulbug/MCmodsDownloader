package app.mods;

import java.util.List;

/**
 * A mod this app installed into an instance (stored in installed-content.json).
 *
 * @param explicit         true if the user chose it, false if it came in as a dependency
 * @param requires         project ids of the required mods that were present at install time
 * @param incompatibleWith project ids this mod declares as incompatible
 * @param publishedAt      when this version was published (ISO-8601), or null if unknown (older records)
 */
public record InstalledMod(String projectId, String title, String versionId, String versionNumber,
                           String fileName, String sha512, boolean explicit, long installedAt,
                           List<String> requires, List<String> incompatibleWith, String publishedAt) {

    public InstalledMod {
        requires = requires == null ? List.of() : List.copyOf(requires);
        incompatibleWith = incompatibleWith == null ? List.of() : List.copyOf(incompatibleWith);
    }

    /** Same as the full constructor with an unknown publish date. */
    public InstalledMod(String projectId, String title, String versionId, String versionNumber,
                        String fileName, String sha512, boolean explicit, long installedAt,
                        List<String> requires, List<String> incompatibleWith) {
        this(projectId, title, versionId, versionNumber, fileName, sha512, explicit, installedAt,
                requires, incompatibleWith, null);
    }
}
