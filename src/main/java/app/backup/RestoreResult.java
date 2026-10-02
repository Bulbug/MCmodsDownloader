package app.backup;

/**
 * @param unitsRestored folders/files that were put back
 * @param safetyBackup  automatic copy of what was replaced, or null if there was nothing to copy
 */
public record RestoreResult(int unitsRestored, BackupInfo safetyBackup) { }
