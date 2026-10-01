package app.mods;

import app.api.ProjectVersion;
import app.api.ProjectVersion.VersionFile;

/**
 * One mod that the plan will install.
 *
 * @param targetFileName safe file name inside the mods folder (already sanitized)
 * @param neededBy       title of the mod that requires this one, or null if the user chose it
 */
public record PlannedMod(String projectId, String title, ProjectVersion version, VersionFile file,
                         String targetFileName, boolean chosen, String neededBy) { }
