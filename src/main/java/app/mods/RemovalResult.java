package app.mods;

import java.nio.file.Path;
import java.util.List;

/** @param removedFolder where the files were moved to, or null if no file had to be moved */
public record RemovalResult(List<String> movedFiles, Path removedFolder) { }
