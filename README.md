# Minecraft Manager

Status: Phases 1-5, Settings, and Phase 6.1 (install mods into instances with dependency resolution).

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
