package app.minecraft;

import java.nio.file.Path;
import java.util.List;

/** Result of looking at a Minecraft folder. */
public record MinecraftInstallation(Path directory, boolean exists,
                                    boolean launcherProfilesFound,
                                    List<MinecraftVersionInfo> versions) { }
