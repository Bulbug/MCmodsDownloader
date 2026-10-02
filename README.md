# Minecraft Manager

Status: Phases 1-5, Settings, Phase 6 (install, remove, update and roll back mods).

## Requirements
- JDK 21 (jpackage on PATH: `jpackage --version`)
- Gradle 8.x (`gradle -v`)
- For the installer only: WiX Toolset 3.x

## Run from source
    gradle run
    gradle test

## Make the Windows .exe
- Portable folder: double-click `build-exe.bat` -> dist\MinecraftManager\MinecraftManager.exe
- Installer (needs WiX 3.x): double-click `build-installer.bat`

Logs: %APPDATA%\MinecraftManager\logs\app.log

## Modrinth
Search uses Modrinth's public API v2 (https://docs.modrinth.com). No account or key is needed.
Optional: put your email or GitHub name in Settings ("Contact for Modrinth"); it is added to the
User-Agent as Modrinth asks. Rate limit is 300 requests/minute; results are cached (Settings).

## Installing mods (Phase 6.1)
Mods page -> select a result and a version -> "Install to instance...". The app checks the instance's
Minecraft version and loader, resolves required dependencies, shows the plan, and only then downloads
into a staging folder. Everything is installed together or not at all. What was installed is recorded
in the instance folder (installed-content.json).

## Managing and removing mods (Phase 6.2)
Instances page -> select an instance -> "Installed mods". Removing a mod moves its file to
`<instance>/.removed/<time>/` (nothing is deleted), warns if other mods need it, and can also
remove dependency-only mods that nothing else uses. To restore a mod, move the file back into
`game/mods`.

## Updating mods and rolling back (Phase 6.3)
Instances page -> select an instance -> "Installed mods" -> "Check for updates". One request checks every
mod in the instance by the hash of the file on disk. Select a mod with an arrow in the Update column and
click "Update...", or use "Change version..." to pick any compatible version (including an older one to
roll back). The plan is shown first; the old file moves to `.removed`, and a failed update changes nothing.
