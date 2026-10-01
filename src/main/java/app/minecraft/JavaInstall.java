package app.minecraft;

import java.nio.file.Path;

/**
 * One Java runtime found on this computer.
 *
 * @param major   major version (8, 17, 21...). 0 if it could not be determined.
 * @param version full version text from the "release" file, or "unknown"
 * @param source  where it was found: "Environment" (JAVA_HOME/PATH), "Installed", ...
 */
public record JavaInstall(Path home, int major, String version, String vendor, String source) { }
