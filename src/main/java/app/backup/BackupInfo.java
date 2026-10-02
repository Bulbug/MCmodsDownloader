package app.backup;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * One backup file.
 *
 * @param valid  false if the file could not be read as a backup made by this app
 * @param note   free text shown in the list (for example "Automatic copy before restoring")
 */
public record BackupInfo(Path file, boolean valid, String instanceId, String instanceName,
                         String minecraftVersion, String loader, String loaderVersion,
                         long createdAt, long sizeBytes, List<BackupPart> parts,
                         boolean entireInstance, String note) {

    /** What the backup contains, in words. */
    public String contents() {
        if (!valid) return "Unreadable";
        if (entireInstance) return "Entire instance";
        if (parts.isEmpty()) return "Selected files";
        return parts.stream().map(BackupPart::label).collect(Collectors.joining(", "));
    }
}
