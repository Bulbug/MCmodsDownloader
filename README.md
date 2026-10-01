# Minecraft Manager

Status: Phase 1 (skeleton), Settings, Phase 2 (detection), Phase 3 (instances), Phase 4 (downloads), Phase 5 (Modrinth browse).

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
