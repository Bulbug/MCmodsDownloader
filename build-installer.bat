@echo off
setlocal

echo === Building jars ===
if exist gradlew.bat (
    call gradlew.bat clean prepareJpackage || goto :error
) else (
    call gradle clean prepareJpackage || goto :error
)

if exist dist rmdir /s /q dist

echo === Creating installer (needs WiX Toolset 3.x) ===
jpackage ^
  --type exe ^
  --name MinecraftManager ^
  --app-version 0.1.0 ^
  --vendor "Kazu" ^
  --description "Minecraft mod and instance manager" ^
  --input build\jpackage-input ^
  --main-jar mc-manager.jar ^
  --main-class app.Launcher ^
  --dest dist ^
  --add-modules java.base,java.desktop,java.logging,java.xml,java.naming,java.sql,java.net.http,jdk.unsupported,jdk.jsobject,jdk.crypto.ec ^
  --java-options "-Xmx512m" ^
  --win-menu ^
  --win-shortcut ^
  --win-dir-chooser ^
  --win-per-user-install ^
  --win-menu-group "Minecraft Manager" || goto :error

echo.
echo Done: dist\MinecraftManager-0.1.0.exe
pause
exit /b 0

:error
echo Build failed.
pause
exit /b 1
